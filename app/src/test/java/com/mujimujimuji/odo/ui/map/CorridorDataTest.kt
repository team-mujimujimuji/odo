package com.mujimujimuji.odo.ui.map

import com.mujimujimuji.odo.engine.Geo
import com.mujimujimuji.odo.engine.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos

/** 생성된 `assets/map/corridor.json`이 설계서 §4.1 형식을 지키고, 렌더가 기대는 질의가 맞는지 확인한다. */
class CorridorDataTest {
    private companion object {
        val data: CorridorData by lazy {
            val file = listOf("src/main/assets/", "app/src/main/assets/")
                .map { File(it + CorridorData.ASSET_PATH) }
                .first { it.exists() }
            CorridorData(file.readText())
        }
        val scenarioStart = LatLng(37.2384, 127.569596)
        val scenarioEnd = LatLng(37.463103, 128.13488)
    }

    private fun east(from: LatLng, m: Double) = LatLng(from.lat, from.lng + m / (111_320.0 * cos(Math.toRadians(from.lat))))

    @Test
    fun `형식·출처`() {
        assertEquals(1, data.version)
        assertEquals(CorridorData.ATTRIBUTION, data.attribution)
        assertTrue(data.attribution.contains("OpenStreetMap"))
        assertEquals(1_000, data.cellM)
        assertTrue(data.pointCount in 10_000..60_000)
    }

    @Test
    fun `레이어·도로 5등급·휴게소·경로`() {
        for (layer in MapLayer.entries) assertTrue(layer.name, data.features.any { it.layer == layer })
        for (cls in RoadClass.entries) assertTrue(cls.key, data.features.any { it.road == cls })
        assertTrue(data.pois.size >= 2)
        assertTrue(data.pois.all { it.type == "rest" && it.src.startsWith("OSM ") })
        assertTrue(data.route.size / 2 > 100)
        assertTrue(data.routeLengthM in 60_000.0..75_000.0)
    }

    @Test
    fun `시나리오 시작·끝은 코리도 안, 원점은 밖`() {
        assertTrue(data.contains(scenarioStart, 0.0))
        assertTrue(data.contains(scenarioEnd, 0.0))
        assertFalse(data.contains(MapCamera.NOWHERE, 2_000.0))
        assertTrue(Geo.distanceMeters(scenarioStart, data.routeStart) < 500.0)
        val routeEnd = data.toLatLng(data.route[data.route.size - 2].toDouble(), data.route[data.route.size - 1].toDouble())
        assertTrue(Geo.distanceMeters(scenarioEnd, routeEnd) < 1_500.0)
    }

    @Test
    fun `좌표 왕복`() {
        val p = LatLng(37.2, 127.8) // 원점 위도와 같아 동서 축척이 정확히 1
        val q = data.toLatLng(data.localX(p), data.localY(p))
        assertEquals(p.lat, q.lat, 1e-9)
        assertEquals(p.lng, q.lng, 1e-9)
        // 동쪽 1000 m 는 로컬 x 로 ≈1000
        assertEquals(1_000.0, data.localX(east(p, 1_000.0)) - data.localX(p), 1.0)
    }

    @Test
    fun `가시 집합은 중복 없이 경로 주변을 담는다`() {
        val set = VisibleSet(data.features.size)
        val x = data.route[0].toDouble()
        val y = data.route[1].toDouble()
        data.collectVisible(x - 2_000, y - 2_000, x + 2_000, y + 2_000, set)
        assertTrue(set.count > 10)
        assertTrue(set.count < data.features.size)
        val ids = set.ids.copyOf(set.count)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { data.features[it].road != null || data.features[it].isPolygon || data.features[it].layer == MapLayer.RIVER })
        // 두 번째 호출도 깨끗하게 시작한다.
        data.collectVisible(x - 100, y - 100, x + 100, y + 100, set)
        assertTrue(set.count <= ids.size)
    }

    @Test
    fun `경로 진행 거리·점·방향`() {
        val tmp = DoubleArray(2)
        data.routePoint(600.0, tmp)
        val fromStart = Geo.distanceMeters(data.routeStart, data.toLatLng(tmp[0], tmp[1]))
        assertTrue(fromStart in 100.0..600.5)
        assertEquals(600.0, data.routeProgress(tmp[0], tmp[1]), 1.0)
        assertEquals(0, data.routeIndexAfter(-1.0))
        assertEquals(1, data.routeIndexAfter(0.0))
        assertEquals(data.route.size / 2, data.routeIndexAfter(data.routeLengthM))
        // 끝점 지점의 진행 거리는 총 길이
        assertEquals(data.routeLengthM, data.routeProgress(data.route[data.route.size - 2].toDouble(), data.route[data.route.size - 1].toDouble()), 1e-6)
    }

    @Test
    fun `모의 미니카는 차의 경로 진행 기준 앞뒤 고정 거리`() {
        val tmp = DoubleArray(2)
        assertTrue(data.mockCarAt(10_000.0, 500.0, tmp))
        assertEquals(10_500.0, data.routeProgress(tmp[0], tmp[1]), 1.0)
        assertTrue(data.mockCarAt(10_000.0, -300.0, tmp))
        assertEquals(9_700.0, data.routeProgress(tmp[0], tmp[1]), 1.0)
        // 출발 직후·도착 직전: 경로 밖 자리는 그리지 않는다(시작점에 묶여 차와 겹치지 않게)
        assertFalse(data.mockCarAt(100.0, -300.0, tmp))
        assertFalse(data.mockCarAt(data.routeLengthM - 100.0, 500.0, tmp))
    }

    @Test
    fun `카메라 화면 좌표 - north-up 은 동쪽이 오른쪽, heading-up 은 진행방향이 위`() {
        val c = data.toLatLng(10_000.0, 10_000.0)
        val e = east(c, 200.0)
        val parked = MapCamera.parked(c).toScreenDp(data, e, 400f, 300f)
        assertEquals(210f, parked.x, 0.5f)
        assertEquals(150f, parked.y, 0.5f)
        val glance = MapCamera.glance(c, 90f).toScreenDp(data, e, 400f, 300f)
        assertEquals(200f, glance.x, 0.5f)
        assertEquals(300f * 0.65f - 200f / MapCamera.GLANCE_METERS_PER_DP, glance.y, 0.5f)
    }
}
