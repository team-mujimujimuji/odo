package com.mujimujimuji.odo.ui.map

import androidx.compose.ui.geometry.Offset
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.sim.Scenario
import com.mujimujimuji.odo.ui.vehicle.VehicleMarkerScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 정차 미니카 둘레의 휴게소 픽토 비우기([pictoHitsCar]): 픽토 원이 차 둘레 원에 걸치면 그리지 않는다.
 * 차가 서 있는 휴게소 픽토([standingPoiIndex])는 빼지 않고 차 옆으로 옮긴다([pictoBesideCar]).
 */
class PictoClearTest {
    private val half = 18f
    private val clear = 40f
    private val carX = 300f
    private val carY = 200f

    @Test
    fun `차 중심 반경 안 픽토는 뺀다`() {
        // 차 바로 밑(휴게소에 서 있는 차).
        assertTrue(pictoHitsCar(carX, carY, half, carX, carY, clear))
        // 반지름 합(58)보다 가까우면 어느 방향이든 뺀다.
        assertTrue(pictoHitsCar(carX + 57f, carY, half, carX, carY, clear))
        assertTrue(pictoHitsCar(carX, carY - 57f, half, carX, carY, clear))
        assertTrue(pictoHitsCar(carX + 40f, carY + 40f, half, carX, carY, clear)) // 56.6
    }

    @Test
    fun `반경 밖 픽토는 그린다`() {
        assertFalse(pictoHitsCar(carX + 58f, carY, half, carX, carY, clear)) // 딱 닿음은 걸치지 않음
        assertFalse(pictoHitsCar(carX - 60f, carY, half, carX, carY, clear))
        assertFalse(pictoHitsCar(carX + 42f, carY + 42f, half, carX, carY, clear)) // 59.4
        assertFalse(pictoHitsCar(carX + 300f, carY - 150f, half, carX, carY, clear))
    }

    @Test
    fun `홈 비울 반경은 가장 긴 차체의 범퍼까지 덮는다`() {
        val clearHome = CAR_CLEAR.value * VehicleMarkerScale.HOME // 밀도 1(px = dp)로 본다
        // 대형 Top 차체 반길이 62dp(top/body.webp 알파 범위 124dp의 반). 범퍼에 닿은 픽토(중심 62 + 18)는 어느 방향이든 뺀다.
        assertTrue(pictoHitsCar(carX + 62f + half, carY, half, carX, carY, clearHome))
        assertTrue(pictoHitsCar(carX, carY - 62f - half, half, carX, carY, clearHome))
        // 데모 경로(corridor.json, 정차 축척 20 m/dp): 트립 끝 휴게소 바로 앞 픽토(화면 +75, +8 dp)는 뺀다.
        assertTrue(pictoHitsCar(carX + 75f, carY + 8f, half, carX, carY, clearHome))
        // 출발 지점 홈에서 차 밑이 아닌 가장 가까운 픽토(화면 -48, +80 dp, 94dp)는 남긴다.
        assertFalse(pictoHitsCar(carX - 48f, carY + 80f, half, carX, carY, clearHome))
    }

    @Test
    fun `요약 비울 반경도 줄인 차체의 범퍼까지 덮는다`() {
        val clearSummary = CAR_CLEAR.value * VehicleMarkerScale.SUMMARY
        val bumper = 62f * VehicleMarkerScale.SUMMARY
        assertTrue(pictoHitsCar(carX + bumper + half, carY, half, carX, carY, clearSummary))
    }

    @Test
    fun `비울 반경이 0이면 언제나 그린다`() {
        assertFalse(pictoHitsCar(carX, carY, half, carX, carY, 0f))
        assertFalse(pictoHitsCar(carX + 1f, carY, half, carX, carY, 0f))
        // 차를 넘기지 않은 화면(글랜스·업적·차고)은 반경이 0이다.
        assertFalse(pictoHitsCar(0f, 0f, half, 0f, 0f, 0f))
    }

    @Test
    fun `서 있는 휴게소 픽토는 차와 같은 높이로 픽토 쪽 가로에 비움 원 바로 바깥으로 옮긴다`() {
        val r = clear + half + 1f
        // 거리: 반경 + 픽토 반지름 + 1 = 59, 높이는 차와 같다.
        assertEquals(Offset(carX + r, carY), pictoBesideCar(carX + 5f, carY - 20f, half, carX, carY, clear))
        assertEquals(Offset(carX - r, carY), pictoBesideCar(carX - 5f, carY - 20f, half, carX, carY, clear))
        assertEquals(Offset(carX - r, carY), pictoBesideCar(carX - 30f, carY + 25f, half, carX, carY, clear))
        assertFalse(pictoHitsCar(carX + r, carY, half, carX, carY, clear))
    }

    @Test
    fun `차 위로 비스듬한 픽토도 위로 올리지 않는다`() {
        // 홈 지도 위 출처 캡션은 차보다 위에 있다. 차 바로 위에 가까운 픽토도 차 높이 그대로 가로로 옮긴다.
        val clearHome = CAR_CLEAR.value * VehicleMarkerScale.HOME
        val moved = pictoBesideCar(carX - 3f, carY - 30f, half, carX, carY, clearHome)
        assertEquals(carY, moved.y, 0f)
        assertEquals(carX - (clearHome + half + 1f), moved.x, 0.01f)
    }

    @Test
    fun `차와 가로 위치가 같은 픽토는 오른쪽으로 옮긴다`() {
        val r = clear + half + 1f
        assertEquals(Offset(carX + r, carY), pictoBesideCar(carX, carY, half, carX, carY, clear))
        assertEquals(Offset(carX + r, carY), pictoBesideCar(carX, carY - 30f, half, carX, carY, clear))
        assertEquals(Offset(carX + r, carY), pictoBesideCar(carX, carY + 30f, half, carX, carY, clear))
        assertEquals(Offset(carX - r, carY), pictoBesideCar(carX - 0.2f, carY + 0.2f, half, carX, carY, clear))
    }

    @Test
    fun `차 둘레에 걸치지 않는 픽토는 그대로 둔다`() {
        assertEquals(Offset(carX + 58f, carY), pictoBesideCar(carX + 58f, carY, half, carX, carY, clear))
        assertEquals(Offset(carX - 200f, carY + 90f), pictoBesideCar(carX - 200f, carY + 90f, half, carX, carY, clear))
        // 반경 0(차를 넘기지 않은 화면)도 그대로.
        assertEquals(Offset(carX, carY), pictoBesideCar(carX, carY, half, carX, carY, 0f))
    }

    @Test
    fun `어느 방향에서 옮겨도 차 둘레에 걸치지 않고 차 높이에 남는다`() {
        val clearHome = CAR_CLEAR.value * VehicleMarkerScale.HOME
        for (k in 0 until 8) {
            val a = Math.toRadians(k * 45.0)
            val sx = carX + (30.0 * cos(a)).toFloat()
            val sy = carY + (30.0 * sin(a)).toFloat()
            for (c in floatArrayOf(clear, clearHome, CAR_CLEAR.value * VehicleMarkerScale.SUMMARY)) {
                val moved = pictoBesideCar(sx, sy, half, carX, carY, c)
                assertFalse("${k * 45}° 반경 $c → $moved", pictoHitsCar(moved.x, moved.y, half, carX, carY, c))
                assertEquals(c + half + 1f, hypot(moved.x - carX, moved.y - carY), 0.01f)
                assertEquals(carY, moved.y, 0f)
                assertEquals("${k * 45}° 쪽", sx < carX, moved.x < carX)
            }
        }
    }

    /** 데모 경로(corridor.json)와 시나리오: 출발·트립 끝 정차 지점의 휴게소를 찾고, 휴게소 밖에서는 찾지 않는다. */
    @Test
    fun `데모 정차 지점의 서 있는 휴게소`() {
        val corridor = listOf("src/main/assets/", "app/src/main/assets/")
            .map { File(it + CorridorData.ASSET_PATH) }
            .first { it.exists() }
            .let { CorridorData(it.readText()) }
        val scenario = listOf("src/main/assets/", "app/src/main/assets/")
            .map { File(it + "scenarios/gangneung_trip.json") }
            .first { it.exists() }
            .let { Scenario.parse(it.readText()) }
        fun standingSrc(p: LatLng): String? =
            standingPoiIndex(corridor.pois, corridor.localX(p), corridor.localY(p)).takeIf { it >= 0 }?.let { corridor.pois[it].src }
        val first = scenario.samples.first().location!!
        val last = scenario.samples.last().location!!
        // 출발(홈): 131 m, 트립 끝 휴게소: 198 m.
        assertEquals("OSM way 344480537", standingSrc(first))
        assertEquals("OSM node 414680790", standingSrc(last))
        // 트립 끝에서 2 km 떨어진 곳에는 휴게소가 없다.
        val away = corridor.toLatLng(corridor.localX(last) + 2_000.0, corridor.localY(last))
        assertNull(standingSrc(away))
        // 반경을 줄이면 찾지 않는다.
        assertEquals(-1, standingPoiIndex(corridor.pois, corridor.localX(first), corridor.localY(first), maxM = 100.0))
        assertEquals(-1, standingPoiIndex(emptyList(), 0.0, 0.0))
    }
}
