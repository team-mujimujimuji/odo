package com.mujimujimuji.odo.ui.map

import com.mujimujimuji.odo.ui.vehicle.VehicleMarkerScale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 정차 미니카 둘레의 휴게소 픽토 비우기([pictoHitsCar]): 픽토 원이 차 둘레 원에 걸치면 그리지 않는다. */
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
}
