package com.mujimujimuji.odo.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.mujimujimuji.odo.engine.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos

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

    @Test
    fun `글랜스 카메라 방위는 heading, 없으면 경로 방위, 지도가 없으면 0`() {
        val corridor = listOf("src/main/assets/", "app/src/main/assets/")
            .map { File(it + CorridorData.ASSET_PATH) }
            .first { it.exists() }
            .let { CorridorData(it.readText()) }
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
        // heading이 생기면 그 값 그대로(0°도 값이다).
        assertEquals(45f, glanceCamera(start, 45f, corridor, MapCamera.GLANCE_TILT_DEG, 0.5f).headingDeg, 0f)
        assertEquals(0f, glanceCamera(start, 0f, corridor, MapCamera.GLANCE_TILT_DEG, 0.5f).headingDeg, 0f)
        // 지도 데이터가 아직 없거나 위치가 없으면 0(북), 기존 glance와 같다.
        assertEquals(0f, glanceCamera(start, null, null, MapCamera.GLANCE_TILT_DEG, 0.5f).headingDeg, 0f)
        assertEquals(MapCamera.glance(null, null), glanceCamera(null, null, corridor, MapCamera.GLANCE_TILT_DEG, 0.5f))
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
