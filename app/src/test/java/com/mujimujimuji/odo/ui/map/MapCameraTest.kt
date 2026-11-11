package com.mujimujimuji.odo.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.mujimujimuji.odo.data.AchievementCatalog
import com.mujimujimuji.odo.drive.HeadingTracker
import com.mujimujimuji.odo.engine.EngineEvent
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.engine.TripSession
import com.mujimujimuji.odo.sim.DemoSeed
import com.mujimujimuji.odo.sim.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

/** 설계서 §4.3 카메라 표: 글랜스 6 m/dp 58°, 정차 20 m/dp north-up, 요약은 bbox 맞춤. */
class MapCameraTest {
    private val c = LatLng(37.3, 127.8)

    @Test
    fun `글랜스 카메라`() {
        val none = MapCamera.glance(null, null)
        assertEquals(MapCamera.NOWHERE, none.center)
        assertEquals(0f, none.headingDeg, 0f)
        assertFalse(none.northUp)
        assertEquals(MapCamera.GLANCE_METERS_PER_DP, none.metersPerDp, 0f)
        assertEquals(MapCamera.GLANCE_TILT_DEG, none.tiltDeg, 0f)
        val g = MapCamera.glance(c, 45f, tiltDeg = 0f)
        assertEquals(45f, g.headingDeg, 0f)
        assertEquals(0f, g.tiltDeg, 0f)
        // 미니카 기준점 (50 %, 65 %)
        val a = g.anchor(400f, 300f)
        assertEquals(200f, a.x, 0f)
        assertEquals(195f, a.y, 0f)
        // 글랜스 화면: 왼쪽 패널 264dp를 뺀 지도 영역 가로 중앙 → 656dp 폭에서 (460, 356)
        val shifted = MapCamera.glance(c, 0f, anchorX = (656f + 264f) / 2f / 656f).anchor(656f, 548f)
        assertEquals(460f, shifted.x, 0.01f)
        assertEquals(356.2f, shifted.y, 0.01f)
        // north-up은 anchorX와 무관하게 정중앙
        assertEquals(200f, MapCamera.parked(c).copy(anchorX = 0.9f).anchor(400f, 300f).x, 0f)
    }

    private val corridor: CorridorData by lazy {
        listOf("src/main/assets/", "app/src/main/assets/")
            .map { File(it + CorridorData.ASSET_PATH) }
            .first { it.exists() }
            .let { CorridorData(it.readText()) }
    }

    @Test
    fun `글랜스 카메라 방위는 경로 위면 경로 방위, heading이 경로와 크게 다르면 heading, 지도가 없으면 0`() {
        val start = corridor.routeStart
        val bearing = routeBearingDeg(corridor, start)!!
        // 데모 경로는 시작점에서 북쪽을 향하지 않는다(0이면 이 시험이 북쪽 위 첫 프레임을 못 잡는다).
        assertTrue("경로 시작 방위 $bearing", bearing > 1f && bearing < 359f)
        // 트립 시작 직후(heading 없음, 3 m 미만 이동): 첫 프레임부터 경로 방위.
        val first = glanceCamera(start, null, corridor, MapCamera.GLANCE_TILT_DEG, 0.7f)
        assertEquals(bearing, first.headingDeg, 0f)
        assertFalse(first.northUp)
        assertEquals(start, first.center)
        assertEquals(0.7f, first.anchorX, 0f)
        // heading이 경로 방위(약 100°)와 45° 넘게 다르면 heading 그대로(0°도 값이다).
        assertTrue("45°와 경로 방위의 차", angleDiffDeg(45f, bearing) > 45f)
        assertEquals(45f, glanceCamera(start, 45f, corridor, MapCamera.GLANCE_TILT_DEG, 0.5f).headingDeg, 0f)
        assertEquals(0f, glanceCamera(start, 0f, corridor, MapCamera.GLANCE_TILT_DEG, 0.5f).headingDeg, 0f)
        // 지도 데이터가 아직 없거나 위치가 없으면 0(북), 기존 glance와 같다.
        assertEquals(0f, glanceCamera(start, null, null, MapCamera.GLANCE_TILT_DEG, 0.5f).headingDeg, 0f)
        assertEquals(MapCamera.glance(null, null), glanceCamera(null, null, corridor, MapCamera.GLANCE_TILT_DEG, 0.5f))
    }

    @Test
    fun `글랜스 방위 - 경로 30 m 안에서 heading이 45° 안이면 경로 방위`() {
        val start = corridor.routeStart
        val bearing = routeBearingDeg(corridor, start)!!
        // 저역통과로 늦은 heading(경로와 30° 차)은 경로 방위로 바꾼다.
        assertEquals(bearing, glanceHeadingDeg((bearing + 30f) % 360f, corridor, start), 0f)
        assertEquals(bearing, glanceHeadingDeg((bearing + 330f) % 360f, corridor, start), 0f)
        assertEquals(bearing, glanceHeadingDeg(null, corridor, start), 0f)
        // 거꾸로 가면(180° 차) heading.
        val back = (bearing + 180f) % 360f
        assertEquals(back, glanceHeadingDeg(back, corridor, start), 0f)
    }

    @Test
    fun `글랜스 방위 - 경로에서 30 m 넘게 벗어나면 heading, heading이 없으면 300 m 안 경로 방위`() {
        val start = corridor.routeStart
        val bearing = routeBearingDeg(corridor, start)!!
        // 경로 시작점에서 진행 방향 오른쪽으로 100 m.
        val a = Math.toRadians(bearing + 90.0)
        val off = corridor.toLatLng(corridor.localX(start) + 100.0 * sin(a), corridor.localY(start) + 100.0 * cos(a))
        assertNull(routeBearingDeg(corridor, off, 30.0))
        val wide = routeBearingDeg(corridor, off)
        assertNotNull(wide)
        val h = (bearing + 10f) % 360f
        assertEquals(h, glanceHeadingDeg(h, corridor, off), 0f)
        assertEquals(wide!!, glanceHeadingDeg(null, corridor, off), 0f)
        // 지도·위치가 없으면 heading, 그것도 없으면 0.
        assertEquals(h, glanceHeadingDeg(h, null, off), 0f)
        assertEquals(h, glanceHeadingDeg(h, corridor, null), 0f)
        assertEquals(0f, glanceHeadingDeg(null, corridor, null), 0f)
    }

    @Test
    fun `방위 차는 0~180°, 360° 랩을 넘는다`() {
        assertEquals(2f, angleDiffDeg(359f, 1f), 1e-4f)
        assertEquals(2f, angleDiffDeg(1f, 359f), 1e-4f)
        assertEquals(0f, angleDiffDeg(10f, 370f), 1e-4f)
        assertEquals(180f, angleDiffDeg(0f, 180f), 1e-4f)
        assertEquals(90f, angleDiffDeg(-45f, 45f), 1e-4f)
    }

    /**
     * 데모 시나리오 재생(DriveController와 같이 TripStarted에서 heading을 비움): 주행 중 경로 30 m 안에서는 글랜스 방위가 경로 방위이고,
     * 샘플당 회전이 heading보다 크지 않다. 출발 램프 굽이에서 heading은 경로보다 15° 넘게 늦은 적이 있다(고친 문제).
     */
    @Test
    fun `강릉 데모 주행 내내 글랜스 방위는 경로 방위, 회전은 heading보다 작다`() {
        val zone = ZoneId.of("Asia/Seoul")
        val today = LocalDate.of(2026, 11, 8)
        val startMs = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        fun asset(path: String) = listOf("src/main/assets/", "app/src/main/assets/").map { File(it + path) }.first { it.exists() }.readText()
        val scenario = Scenario.parse(asset("scenarios/gangneung_trip.json"))
        val session = TripSession(AchievementCatalog.parse(asset("achievements.json")), DemoSeed.unlocked, DemoSeed.profile(today), zone)
        val tracker = HeadingTracker()
        var moving = 0
        var lagMax = 0f
        var glanceTurnMax = 0f
        var headingTurnMax = 0f
        var prevGlance: Float? = null
        var prevHeading: Float? = null
        for (s in scenario.timeline(startMs)) {
            val events = session.onSample(s)
            tracker.update(s)
            if (events.any { it is EngineEvent.TripStarted }) {
                tracker.reset()
                tracker.update(s)
            }
            val at = tracker.position
            if (s.speedKmh <= 0.0 || at == null) {
                prevGlance = null
                prevHeading = null
                continue
            }
            moving++
            val route = routeBearingDeg(corridor, at, 30.0)
            assertNotNull("${s.timeMs}: 경로에서 30 m 밖", route)
            val heading = tracker.headingDeg
            val glance = glanceHeadingDeg(heading, corridor, at)
            assertEquals("${s.timeMs}: heading $heading", route!!, glance, 0f)
            if (heading != null) lagMax = maxOf(lagMax, angleDiffDeg(heading, route))
            prevGlance?.let { glanceTurnMax = maxOf(glanceTurnMax, angleDiffDeg(it, glance)) }
            if (heading != null) prevHeading?.let { headingTurnMax = maxOf(headingTurnMax, angleDiffDeg(it, heading)) }
            prevGlance = glance
            prevHeading = heading
        }
        assertTrue(moving > 1_000)
        assertTrue("heading 최대 지연 $lagMax", lagMax > 15f && lagMax <= 45f)
        assertTrue("샘플당 회전 글랜스 $glanceTurnMax, heading $headingTurnMax", glanceTurnMax <= headingTurnMax)
    }

    @Test
    fun `정차 카메라`() {
        val p = MapCamera.parked(c)
        assertTrue(p.northUp)
        assertEquals(MapCamera.PARKED_METERS_PER_DP, p.metersPerDp, 0f)
        assertEquals(0f, p.tiltDeg, 0f)
        val a = p.anchor(400f, 300f)
        assertEquals(200f, a.x, 0f)
        assertEquals(150f, a.y, 0f)
        // 패널이 가운데를 가리면 보이는 띠 가운데로(트립 요약 넓은 창)
        val band = p.copy(parkedAnchor = Offset(0.9f, 0.5f)).anchor(400f, 300f)
        assertEquals(360f, band.x, 0.001f)
        assertEquals(150f, band.y, 0.001f)
    }

    @Test
    fun `bbox 맞춤 - 가로 2 km 세로 1 km 를 600x400 dp 에 48 dp 여백으로`() {
        val dLng = 2_000.0 / (111_320.0 * cos(Math.toRadians(c.lat)))
        val dLat = 1_000.0 / 111_320.0
        val pts = listOf(
            LatLng(c.lat - dLat / 2, c.lng - dLng / 2),
            LatLng(c.lat + dLat / 2, c.lng + dLng / 2),
            c,
        )
        val cam = MapCamera.fit(pts, 600f, 400f, paddingDp = 48f, headingDeg = 123f)
        assertTrue(cam.northUp)
        assertEquals(0f, cam.tiltDeg, 0f)
        assertEquals(123f, cam.headingDeg, 0f)
        assertEquals(c.lat, cam.center.lat, 1e-9)
        assertEquals(c.lng, cam.center.lng, 1e-9)
        assertEquals(2_000f / (600f - 96f), cam.metersPerDp, 0.05f)
    }

    @Test
    fun `요약 지도 띠 - 패널을 뺀 자리, 없으면 null`() {
        // 가림 없음 → 캔버스 전체
        assertEquals(Rect(0f, 0f, 600f, 400f), visibleBand(600f, 400f, 0f, 0f))
        // 658×552 창: 패널 592×484 둘레 33dp뿐 → 경로를 담을 띠 없음
        assertNull(visibleBand(658f, 552f, 592f, 484f))
        // 1024×552 창: 오른쪽 216dp 띠
        assertEquals(Rect(808f, 0f, 1024f, 552f), visibleBand(1024f, 552f, 592f, 484f))
    }

    @Test
    fun `bbox 맞춤 - 너무 작은 범위는 최소 축척`() {
        val cam = MapCamera.fit(listOf(c), 600f, 400f)
        assertEquals(2f, cam.metersPerDp, 0f)
        assertEquals(c, cam.center)
    }
}
