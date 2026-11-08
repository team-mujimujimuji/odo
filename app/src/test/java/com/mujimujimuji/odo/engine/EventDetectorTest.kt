package com.mujimujimuji.odo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDetectorTest {

    private fun run(vararg samples: VehicleSnapshot): List<RiskEvent> {
        val d = EventDetector()
        return samples.flatMap { d.onSample(it) }
    }

    @Test
    fun `급가속 기준은 초당 11 km_h 이상`() {
        assertEquals(
            listOf(RiskType.HARSH_ACCEL),
            run(snap(0.0, 50.0), snap(1.0, 61.0)).map { it.type },
        )
        assertTrue(run(snap(0.0, 50.0), snap(1.0, 60.9)).isEmpty())
    }

    @Test
    fun `급감속 기준은 초당 7_5 km_h 이상`() {
        assertEquals(
            listOf(RiskType.HARSH_DECEL),
            run(snap(0.0, 100.0), snap(1.0, 92.5)).map { it.type },
        )
        assertTrue(run(snap(0.0, 100.0), snap(1.0, 92.6)).isEmpty())
    }

    @Test
    fun `샘플 간격으로 나눈 변화율을 쓴다`() {
        // 2초에 15 km/h 감소 = 초당 7.5
        assertEquals(1, run(snap(0.0, 100.0), snap(2.0, 85.0)).size)
        // 0.5초에 4 km/h 감소 = 초당 8
        assertEquals(1, run(snap(0.0, 100.0), snap(0.5, 96.0)).size)
    }

    @Test
    fun `이어지는 급감속은 한 번으로 센다`() {
        val events = run(
            snap(0.0, 100.0), snap(1.0, 90.0), snap(2.0, 80.0), snap(3.0, 70.0),
            snap(4.0, 69.0), snap(5.0, 60.0),
        )
        assertEquals(2, events.size)
    }

    @Test
    fun `신호가 끊긴 뒤 첫 값으로는 판정하지 않는다`() {
        assertTrue(run(snap(0.0, 100.0), snap(5.0, 0.0)).isEmpty())
    }

    @Test
    fun `과속은 제한속도를 알 때만 판정한다`() {
        assertTrue(run(snap(0.0, 150.0), snap(1.0, 150.0)).isEmpty())
        assertTrue(run(snap(0.0, 120.0, speedLimit = 100)).isEmpty())
        assertEquals(
            listOf(RiskType.SPEEDING),
            run(snap(0.0, 120.5, speedLimit = 100), snap(1.0, 121.0, speedLimit = 100)).map { it.type },
        )
    }

    @Test
    fun `벨트 미착용은 10 km_h 초과에서만, 신호가 있을 때만`() {
        assertTrue(run(snap(0.0, 10.0, driverBelt = false)).isEmpty())
        assertTrue(run(snap(0.0, 50.0, driverBelt = null)).isEmpty())
        assertEquals(
            listOf(RiskType.NO_SEATBELT),
            run(snap(0.0, 10.5, driverBelt = false), snap(1.0, 11.0, driverBelt = false)).map { it.type },
        )
    }
}
