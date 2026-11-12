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
import kotlin.math.atan2
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

    private fun progress(at: LatLng): Double = corridor.routeProgress(corridor.localX(at), corridor.localY(at))

    private fun routeAt(s: Double): LatLng {
        val p = DoubleArray(2)
        corridor.routePoint(s, p)
        return corridor.toLatLng(p[0], p[1])
    }

    private fun chordBearing(from: Double, to: Double): Float {
        val a = DoubleArray(2)
        val b = DoubleArray(2)
        corridor.routePoint(from, a)
        corridor.routePoint(to, b)
        return ((Math.toDegrees(atan2(b[0] - a[0], b[1] - a[1])) + 360.0) % 360.0).toFloat()
    }

    @Test
    fun `글랜스 카메라 방위는 경로 위면 경로 위 글랜스 방위, heading이 경로와 크게 다르면 heading, 지도가 없으면 0`() {
        val start = corridor.routeStart
        val bearing = routeBearingDeg(corridor, start)!!
        val route = glanceRouteDeg(corridor, progress(start))!!
        // 데모 경로는 시작점에서 북쪽을 향하지 않는다(0이면 이 시험이 북쪽 위 첫 프레임을 못 잡는다).
        assertTrue("경로 시작 방위 $bearing", bearing > 1f && bearing < 359f)
        assertTrue("경로 위 글랜스 방위 $route", route > 1f && route < 359f)
        // 트립 시작 직후(heading 없음, 3 m 미만 이동): 첫 프레임부터 경로 위 글랜스 방위.
        val first = glanceCamera(start, null, corridor, MapCamera.GLANCE_TILT_DEG, 0.7f)
        assertEquals(route, first.headingDeg, 0f)
        assertFalse(first.northUp)
        assertEquals(start, first.center)
        assertEquals(0.7f, first.anchorX, 0f)
        // 틸트 0 폴백도 방위는 같다.
        assertEquals(route, glanceCamera(start, null, corridor, 0f, 0.7f).headingDeg, 0f)
        // heading이 차 밑 경로 방위(약 100°)와 45° 넘게 다르면 heading 그대로(0°도 값이다).
        assertTrue("45°와 경로 방위의 차", angleDiffDeg(45f, bearing) > 45f)
        assertEquals(45f, glanceCamera(start, 45f, corridor, MapCamera.GLANCE_TILT_DEG, 0.5f).headingDeg, 0f)
        assertEquals(0f, glanceCamera(start, 0f, corridor, MapCamera.GLANCE_TILT_DEG, 0.5f).headingDeg, 0f)
        // 지도 데이터가 아직 없거나 위치가 없으면 0(북), 기존 glance와 같다.
        assertEquals(0f, glanceCamera(start, null, null, MapCamera.GLANCE_TILT_DEG, 0.5f).headingDeg, 0f)
        assertEquals(MapCamera.glance(null, null), glanceCamera(null, null, corridor, MapCamera.GLANCE_TILT_DEG, 0.5f))
    }

    @Test
    fun `글랜스 방위 - 경로 30 m 안에서 heading이 차 밑 경로 방위와 45° 안이면 경로 위 글랜스 방위`() {
        val start = corridor.routeStart
        val bearing = routeBearingDeg(corridor, start)!!
        val route = glanceRouteDeg(corridor, progress(start))!!
        // 저역통과로 늦은 heading(경로와 30° 차)은 경로 위 글랜스 방위로 바꾼다.
        assertEquals(route, glanceHeadingDeg((bearing + 30f) % 360f, corridor, start), 0f)
        assertEquals(route, glanceHeadingDeg((bearing + 330f) % 360f, corridor, start), 0f)
        assertEquals(route, glanceHeadingDeg(null, corridor, start), 0f)
        // 거꾸로 가면(180° 차, 역주행이나 후진) heading.
        val back = (bearing + 180f) % 360f
        assertEquals(back, glanceHeadingDeg(back, corridor, start), 0f)
    }

    @Test
    fun `글랜스 방위 - heading 판정은 지도 방위가 아니라 차 밑 경로 방위와의 차로 한다`() {
        // 출발 S자(경로 처음 300 m)에서 지도 방위와 차 밑 경로 방위가 가장 크게 어긋나는 자리.
        val s = (0..300 step 5).map { it.toDouble() }
            .maxByOrNull { angleDiffDeg(glanceRouteDeg(corridor, it)!!, routeBearingDeg(corridor, routeAt(it), 30.0)!!) }!!
        val at = routeAt(s)
        val local = routeBearingDeg(corridor, at, 30.0)!!
        val view = glanceRouteDeg(corridor, progress(at))!!
        assertTrue("$s m: 지도 방위 $view, 차 밑 경로 방위 $local", angleDiffDeg(view, local) > 30f)
        // 지도 방위 쪽을 +로 본다.
        val side = if (signedAngleDeg(view, local) > 0f) 1f else -1f
        // 차 밑 경로와 40° 차(지도 방위와는 45° 넘게 차): 경로 위로 보고 지도 방위.
        val agree = (local - side * 40f + 360f) % 360f
        assertTrue("지도 방위와의 차", angleDiffDeg(agree, view) > 45f)
        assertEquals(view, glanceHeadingDeg(agree, corridor, at), 0f)
        // 차 밑 경로와 50° 차(지도 방위와는 45° 안): 경로와 다르게 가는 것으로 보고 heading.
        val wide = (local + side * 50f + 360f) % 360f
        assertTrue("지도 방위와의 차", angleDiffDeg(wide, view) < 45f)
        assertEquals(wide, glanceHeadingDeg(wide, corridor, at), 0f)
    }

    @Test
    fun `경로 구간 방위 - 구간 길이를 지킨 채 경로 처음과 끝에 붙인다`() {
        val len = corridor.routeLengthM
        val around = 2 * GLANCE_AROUND_HALF_M
        assertTrue(len > 2 * around)
        // 앞보기: 차 위치부터 700 m 앞까지, 끝 700 m 안에서는 마지막 700 m 구간 하나.
        assertEquals(chordBearing(0.0, GLANCE_LOOK_AHEAD_M), routeWindowDeg(corridor, 0.0, 0.0, GLANCE_LOOK_AHEAD_M)!!, 0f)
        val mid = len / 2
        assertEquals(chordBearing(mid, mid + GLANCE_LOOK_AHEAD_M), routeWindowDeg(corridor, mid, 0.0, GLANCE_LOOK_AHEAD_M)!!, 1e-3f)
        val lastAhead = chordBearing(len - GLANCE_LOOK_AHEAD_M, len)
        // 둘레: 앞뒤 600 m, 처음 600 m 안에서는 처음 1.2 km, 끝 600 m 안에서는 마지막 1.2 km.
        val firstAround = chordBearing(0.0, around)
        for (s in listOf(0.0, 100.0, 300.0, GLANCE_AROUND_HALF_M)) {
            assertEquals("처음 $s m", firstAround, routeWindowDeg(corridor, s, GLANCE_AROUND_HALF_M, GLANCE_AROUND_HALF_M)!!, 0f)
        }
        assertEquals(chordBearing(mid - GLANCE_AROUND_HALF_M, mid + GLANCE_AROUND_HALF_M), routeWindowDeg(corridor, mid, GLANCE_AROUND_HALF_M, GLANCE_AROUND_HALF_M)!!, 1e-3f)
        val lastAround = chordBearing(len - around, len)
        for (back in listOf(600.0, 300.0, 50.0, 0.0)) {
            assertEquals("끝 $back m 전 앞보기", lastAhead, routeWindowDeg(corridor, len - back, 0.0, GLANCE_LOOK_AHEAD_M)!!, 1e-3f)
            assertEquals("끝 $back m 전 둘레", lastAround, routeWindowDeg(corridor, len - back, GLANCE_AROUND_HALF_M, GLANCE_AROUND_HALF_M)!!, 1e-3f)
        }
        // 끝 구간에 들어서는 자리에서 끊기지 않는다.
        assertTrue(angleDiffDeg(routeWindowDeg(corridor, len - GLANCE_LOOK_AHEAD_M - 5.0, 0.0, GLANCE_LOOK_AHEAD_M)!!, lastAhead) < 1f)
        assertTrue(angleDiffDeg(routeWindowDeg(corridor, len - GLANCE_AROUND_HALF_M - 5.0, GLANCE_AROUND_HALF_M, GLANCE_AROUND_HALF_M)!!, lastAround) < 1f)
        // 경로보다 긴 구간은 처음과 끝을 잇는다.
        assertEquals(chordBearing(0.0, len), routeWindowDeg(corridor, mid, len, len)!!, 0f)
        // 경로 위치로 구간을 정하므로 지도 좌표를 거쳐도 같은 값이다.
        assertEquals(chordBearing(mid, mid + GLANCE_LOOK_AHEAD_M), routeWindowDeg(corridor, progress(routeAt(mid)), 0.0, GLANCE_LOOK_AHEAD_M)!!, 0.05f)
    }

    @Test
    fun `경로 위 글랜스 방위 - 앞보기 방위를 둘레 방위의 8° 안으로 묶는다`() {
        val len = corridor.routeLengthM
        var clamped = 0
        var free = 0
        var s = 0.0
        while (s <= len) {
            val ahead = routeWindowDeg(corridor, s, 0.0, GLANCE_LOOK_AHEAD_M)!!
            val around = routeWindowDeg(corridor, s, GLANCE_AROUND_HALF_M, GLANCE_AROUND_HALF_M)!!
            val view = glanceRouteDeg(corridor, s)!!
            assertTrue("$s m: 지도 $view, 둘레 $around", angleDiffDeg(view, around) <= GLANCE_AROUND_CLAMP_DEG + 1e-3f)
            val lead = angleDiffDeg(ahead, around)
            if (lead <= GLANCE_AROUND_CLAMP_DEG) {
                free++
                assertTrue("$s m: 지도 $view, 앞보기 $ahead", angleDiffDeg(view, ahead) < 1e-3f)
            } else {
                clamped++
                // 둘레 방위에서 앞보기 쪽으로 딱 8°.
                assertEquals("$s m", GLANCE_AROUND_CLAMP_DEG, angleDiffDeg(view, around), 1e-3f)
                assertEquals("$s m", lead - GLANCE_AROUND_CLAMP_DEG, angleDiffDeg(view, ahead), 1e-2f)
            }
            s += 50.0
        }
        assertTrue("묶은 자리 $clamped, 그대로 $free", clamped > 10 && free > 10)
        // 경로 끝 600 m 안에서는 두 구간이 모두 끝에 붙어 방위가 멈춘다.
        val end = glanceRouteDeg(corridor, len)!!
        for (back in listOf(600.0, 300.0, 50.0)) assertEquals("끝 $back m 전", end, glanceRouteDeg(corridor, len - back)!!, 1e-3f)
        // 방위 차 부호: 시계 방향 +, 360° 랩을 넘는다.
        assertEquals(2f, signedAngleDeg(1f, 359f), 1e-4f)
        assertEquals(-2f, signedAngleDeg(359f, 1f), 1e-4f)
        assertEquals(-90f, signedAngleDeg(-45f, 45f), 1e-4f)
        assertEquals(-180f, signedAngleDeg(0f, 180f), 1e-4f)
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
     * 데모 시나리오 재생(DriveController와 같이 TripStarted에서 heading을 비움): 주행 중 경로 30 m 안에서는 글랜스 방위가 경로 위 글랜스 방위다.
     * 샘플당 회전은 heading보다, 차 밑 경로 방위(앞뒤 30 m)보다 크지 않고, 데모 전체에서 [GLANCE_TURN_PER_SAMPLE_MAX_DEG] 안이다.
     * 출발 S자(경로 처음 330 m)는 120 m 안에서 꺾인다. 차 밑 경로 방위는 샘플당 8° 넘게 돌고, 차 지붕 위로 보이는 길
     * (차 앞 200~630 m)과 40° 넘게 어긋난다. 글랜스 방위는 샘플당 2° 안으로 돌고, 그 길과 5° 안이다.
     * 지도 방위와 차 밑 길의 차는 출발 S자에서 50° 안, 그 밖에서는 32° 안이다. 차 뒤 100~600 m 방향과의 차는 48° 안이다.
     */
    @Test
    fun `강릉 데모 주행 내내 글랜스 방위는 경로 위 글랜스 방위, 출발 S자에서 지도가 크게 돌지 않고 차 뒤 길도 크게 기울지 않는다`() {
        val zone = ZoneId.of("Asia/Seoul")
        val today = LocalDate.of(2026, 11, 8)
        val startMs = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        fun asset(path: String) = listOf("src/main/assets/", "app/src/main/assets/").map { File(it + path) }.first { it.exists() }.readText()
        val scenario = Scenario.parse(asset("scenarios/gangneung_trip.json"))
        val session = TripSession(AchievementCatalog.parse(asset("achievements.json")), DemoSeed.unlocked, DemoSeed.profile(today), zone)
        val tracker = HeadingTracker()
        // 출발 S자와 그 뒤 곧은 길(경로 처음 330 m).
        val rampM = 330.0
        var moving = 0
        var ramp = 0
        var lagMax = 0f
        var glanceTurnMax = 0f
        var headingTurnMax = 0f
        var localTurnMax = 0f
        var rampGlanceTurn = 0f
        var rampLocalTurn = 0f
        var rampGlanceFar = 0f
        var rampLocalFar = 0f
        var underMax = 0f
        var underAfterRamp = 0f
        var behindMax = 0f
        var prevGlance: Float? = null
        var prevHeading: Float? = null
        var prevLocal: Float? = null
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
                prevLocal = null
                continue
            }
            moving++
            val local = routeBearingDeg(corridor, at, 30.0)
            assertNotNull("${s.timeMs}: 경로에서 30 m 밖", local)
            val heading = tracker.headingDeg
            val glance = glanceHeadingDeg(heading, corridor, at)
            val p = progress(at)
            assertEquals("${s.timeMs}: heading $heading", glanceRouteDeg(corridor, p)!!, glance, 0f)
            if (heading != null) lagMax = maxOf(lagMax, angleDiffDeg(heading, local!!))
            val glanceTurn = prevGlance?.let { angleDiffDeg(it, glance) } ?: 0f
            val localTurn = prevLocal?.let { angleDiffDeg(it, local!!) } ?: 0f
            glanceTurnMax = maxOf(glanceTurnMax, glanceTurn)
            localTurnMax = maxOf(localTurnMax, localTurn)
            if (heading != null) prevHeading?.let { headingTurnMax = maxOf(headingTurnMax, angleDiffDeg(it, heading)) }
            val under = angleDiffDeg(glance, local!!)
            underMax = maxOf(underMax, under)
            if (p < rampM) {
                ramp++
                rampGlanceTurn = maxOf(rampGlanceTurn, glanceTurn)
                rampLocalTurn = maxOf(rampLocalTurn, localTurn)
                val far = chordBearing(p + 200.0, p + 630.0)
                rampGlanceFar = maxOf(rampGlanceFar, angleDiffDeg(glance, far))
                rampLocalFar = maxOf(rampLocalFar, angleDiffDeg(local, far))
            } else {
                underAfterRamp = maxOf(underAfterRamp, under)
            }
            if (p >= 700.0) behindMax = maxOf(behindMax, angleDiffDeg(glance, chordBearing(p - 600.0, p - 100.0)))
            prevGlance = glance
            prevHeading = heading
            prevLocal = local
        }
        assertTrue(moving > 1_000)
        assertTrue("출발 S자 샘플 $ramp", ramp > 20)
        assertTrue("heading 최대 지연 $lagMax", lagMax > 15f && lagMax <= 45f)
        assertTrue("샘플당 회전 글랜스 $glanceTurnMax, heading $headingTurnMax", glanceTurnMax <= headingTurnMax)
        assertTrue("샘플당 회전 글랜스 $glanceTurnMax, 차 밑 경로 $localTurnMax", glanceTurnMax <= localTurnMax)
        assertTrue("샘플당 회전 글랜스 $glanceTurnMax", glanceTurnMax <= GLANCE_TURN_PER_SAMPLE_MAX_DEG)
        assertTrue("출발 S자 샘플당 회전 글랜스 $rampGlanceTurn, 차 밑 경로 $rampLocalTurn", rampGlanceTurn <= 2f && rampLocalTurn > 8f)
        assertTrue("출발 S자 지붕 위 길과의 차 글랜스 $rampGlanceFar, 차 밑 경로 $rampLocalFar", rampGlanceFar <= 5f && rampLocalFar > 40f)
        assertTrue("지도 방위와 차 밑 길의 차 $underMax, 출발 S자 밖 $underAfterRamp", underMax <= 50f && underAfterRamp <= 32f)
        assertTrue("지도 방위와 차 뒤 길의 차 $behindMax", behindMax <= 48f)
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

/**
 * 데모 재생에서 글랜스 방위의 샘플당 회전 상한(°). 지금 값은 약 2.8°다(최고 100 km/h, 샘플당 약 28 m).
 * 같은 재생에서 샘플당 최대 회전은 차 밑 경로 방위 약 11°, heading 약 14°다. 시나리오나 경로 데이터를 다시 만들어 굽이 모양·속도가
 * 조금 바뀌어도 흔들리지 않게 약 25 % 여유를 둔다.
 */
private const val GLANCE_TURN_PER_SAMPLE_MAX_DEG = 3.5f
