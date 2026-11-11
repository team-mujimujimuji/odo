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
    fun `샘플 간격이 달라도 1초 동안의 변화로 본다`() {
        // 2초에 15 km/h 감소 = 초당 7.5
        assertEquals(1, run(snap(0.0, 100.0), snap(2.0, 85.0)).size)
        // 0.5초 간격으로 4 km/h씩 감소 = 1초에 8
        assertEquals(1, run(snap(0.0, 100.0), snap(0.5, 96.0), snap(1.0, 92.0)).size)
        // 0.5초에 4 km/h 줄고 멈추면 1초에 4라서 급감속이 아니다
        assertTrue(run(snap(0.0, 100.0), snap(0.5, 96.0), snap(1.0, 96.0), snap(1.5, 96.0)).isEmpty())
    }

    /** 0.1초 간격 샘플. [speedAt]은 0.1초 단위 칸 번호를 받아 그때의 속도를 준다. */
    private fun tenHz(steps: Int, speedAt: (Int) -> Double): Array<VehicleSnapshot> =
        Array(steps + 1) { i -> snap(i / 10.0, speedAt(i)) }

    @Test
    fun `초당 10번 들어오는 계단 모양 속도도 완만하면 급가속·급감속이 아니다`() {
        // 실제 차속 신호: 값은 1초에 한 번 3 km/h씩 바뀌고 샘플은 0.1초마다 온다(초당 3 km/h).
        val up = tenHz(330) { i -> 3.0 * (i / 10) }
        assertTrue(run(*up).isEmpty())
        val down = tenHz(330) { i -> 99.0 - 3.0 * (i / 10) }
        assertTrue(run(*down).isEmpty())
    }

    @Test
    fun `초당 10번 샘플에서도 기준은 1초에 11과 7_5 km_h`() {
        // 2초 정속 뒤 0.1초에 1.1 km/h씩(초당 11) 2초 동안 가속
        val accel = tenHz(40) { i -> 50.0 + 1.1 * (i - 20).coerceAtLeast(0) }
        assertEquals(listOf(RiskType.HARSH_ACCEL), run(*accel).map { it.type })
        // 초당 10.5는 아니다
        assertTrue(run(*tenHz(40) { i -> 50.0 + 1.05 * (i - 20).coerceAtLeast(0) }).isEmpty())

        // 0.1초에 0.75 km/h씩(초당 7.5) 2초 동안 감속
        val decel = tenHz(40) { i -> 100.0 - 0.75 * (i - 20).coerceAtLeast(0) }
        assertEquals(listOf(RiskType.HARSH_DECEL), run(*decel).map { it.type })
        // 초당 7은 아니다
        assertTrue(run(*tenHz(40) { i -> 100.0 - 0.7 * (i - 20).coerceAtLeast(0) }).isEmpty())
    }

    /**
     * 실제 신호 경로의 모양: 0.1초 간격 속도 샘플 사이에 1초마다 틱 샘플(그때의 마지막 속도)이 [offsetMs] 늦게 끼어든다.
     * offsetMs가 0이면 틱이 속도 샘플과 같은 시각에 온다.
     */
    private fun withTicks(samples: Array<VehicleSnapshot>, offsetMs: Long): Array<VehicleSnapshot> =
        samples.withIndex().flatMap { (i, s) ->
            if (i % 10 == 0) listOf(s, s.copy(timeMs = s.timeMs + offsetMs)) else listOf(s)
        }.toTypedArray()

    @Test
    fun `초당 10번 샘플에 1초 틱이 섞여도 판정은 같다`() {
        for (offset in listOf(0L, 50L)) {
            val msg = "틱 +$offset ms"
            // 완만한 계단(초당 3 km/h)은 이벤트가 없다.
            assertTrue(msg, run(*withTicks(tenHz(330) { i -> 3.0 * (i / 10) }, offset)).isEmpty())
            assertTrue(msg, run(*withTicks(tenHz(330) { i -> 99.0 - 3.0 * (i / 10) }, offset)).isEmpty())
            // 초당 11 가속과 초당 7.5 감속은 한 번씩.
            assertEquals(
                msg, listOf(RiskType.HARSH_ACCEL),
                run(*withTicks(tenHz(40) { i -> 50.0 + 1.1 * (i - 20).coerceAtLeast(0) }, offset)).map { it.type },
            )
            assertEquals(
                msg, listOf(RiskType.HARSH_DECEL),
                run(*withTicks(tenHz(40) { i -> 100.0 - 0.75 * (i - 20).coerceAtLeast(0) }, offset)).map { it.type },
            )
            // 초당 10.5 가속과 초당 7 감속은 아니다.
            assertTrue(msg, run(*withTicks(tenHz(40) { i -> 50.0 + 1.05 * (i - 20).coerceAtLeast(0) }, offset)).isEmpty())
            assertTrue(msg, run(*withTicks(tenHz(40) { i -> 100.0 - 0.7 * (i - 20).coerceAtLeast(0) }, offset)).isEmpty())
        }
    }

    @Test
    fun `샘플 간격 3,000 ms까지는 판정하고 3,001 ms부터는 끊긴 것으로 본다`() {
        assertEquals(3_000L, Thresholds.MAX_ACCEL_SAMPLE_GAP_MS)
        // 3초에 30 km/h 감소 = 초당 10. 3,001 ms여도 초당 7.5를 넘으니, 판정하지 않는 이유는 끊김뿐이다.
        fun after(gapMs: Long) = run(snap(0.0, 100.0), snap(0.0, 70.0).copy(timeMs = T0 + gapMs)).map { it.type }
        assertEquals(listOf(RiskType.HARSH_DECEL), after(3_000))
        assertTrue(after(3_001).isEmpty())
    }

    @Test
    fun `짧은 속도 튐은 1초로 보면 급가속이 아니다`() {
        // 0.1초 사이에 2 km/h 올랐다가 그대로 간다. 이웃 샘플만 보면 초당 20이지만 1초에 2다.
        assertTrue(run(*tenHz(40) { i -> if (i < 20) 60.0 else 62.0 }).isEmpty())
    }

    @Test
    fun `기준 근처에서 흔들리는 한 번의 제동은 한 번으로 센다`() {
        // 0.1초에 0.9씩 1초 → 0.2씩 0.3초(잠깐 풀림) → 다시 0.9씩 1.5초. 1초 변화량이 7.5 아래로 0.8초쯤 내려갔다 돌아온다.
        var v = 120.0
        val speeds = ArrayList<Double>()
        repeat(11) { speeds += v }
        repeat(10) { v -= 0.9; speeds += v }
        repeat(3) { v -= 0.2; speeds += v }
        repeat(15) { v -= 0.9; speeds += v }
        val events = run(*tenHz(speeds.size - 1) { i -> speeds[i] })
        assertEquals(listOf(RiskType.HARSH_DECEL), events.map { it.type })
    }

    @Test
    fun `같은 시각의 샘플이 겹쳐도 한 번으로 센다`() {
        val events = run(snap(0.0, 100.0), snap(1.0, 90.0), snap(1.0, 90.0), snap(2.0, 80.0))
        assertEquals(1, events.size)
    }

    @Test
    fun `시각이 거꾸로 가면 기록을 버리고 다시 시작한다`() {
        assertTrue(run(snap(10.0, 100.0), snap(5.0, 50.0), snap(6.0, 50.0), snap(7.0, 50.0)).isEmpty())
        // 다시 시작한 뒤에는 정상 판정한다
        val events = run(snap(10.0, 100.0), snap(5.0, 50.0), snap(6.0, 50.0), snap(7.0, 40.0))
        assertEquals(listOf(RiskType.HARSH_DECEL), events.map { it.type })
        // 거꾸로 간 뒤에도 앞 기록이 1초 창에 남는 경우: 기록을 버리지 않으면 10.5초에 9.5초 속도를 100으로 보간해 거짓 급감속이 난다.
        assertTrue(run(snap(9.0, 100.0), snap(10.0, 100.0), snap(9.5, 60.0), snap(10.5, 60.0)).isEmpty())
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
    fun `과속과 벨트 미착용은 이어지는 동안 진행 중으로 본다`() {
        val d = EventDetector()
        d.onSample(snap(0.0, 115.0, speedLimit = 100))
        assertTrue(!d.ongoing)
        assertEquals(1, d.onSample(snap(1.0, 121.0, speedLimit = 100)).size)
        assertTrue(d.ongoing)
        assertTrue(d.onSample(snap(2.0, 125.0, speedLimit = 100)).isEmpty()) // 이벤트는 한 번만
        assertTrue(d.ongoing)
        d.onSample(snap(3.0, 120.0, speedLimit = 100))
        assertTrue(!d.ongoing)

        d.onSample(snap(4.0, 120.0, speedLimit = 100, driverBelt = false))
        assertTrue(d.ongoing)
        d.onSample(snap(5.0, 120.0, speedLimit = 100, driverBelt = true))
        assertTrue(!d.ongoing)
        // 급감속은 순간 이벤트라 진행 중으로 치지 않는다
        assertEquals(1, d.onSample(snap(6.0, 110.0, speedLimit = 100)).size)
        assertTrue(!d.ongoing)
    }

    @Test
    fun `트립이 시작되면 이어지던 위반을 그 트립에서 한 번 다시 센다`() {
        val d = EventDetector()
        // 제한 100에서 125 km/h, 벨트 미체결, 초당 12 km/h 가속이 이어지는 중
        fun at(t: Int) = snap(t.toDouble(), 125.0 + 12.0 * t, speedLimit = 100, driverBelt = false)
        d.onSample(at(0))
        assertEquals(
            setOf(RiskType.HARSH_ACCEL),
            d.onSample(at(1)).map { it.type }.toSet(),
        )
        assertTrue(d.onSample(at(2)).isEmpty())

        d.onTripStart()
        assertEquals(
            setOf(RiskType.HARSH_ACCEL, RiskType.SPEEDING, RiskType.NO_SEATBELT),
            d.onSample(at(3)).map { it.type }.toSet(),
        )
        assertTrue(d.onSample(at(4)).isEmpty()) // 다시 한 번씩만
    }

    @Test
    fun `트립 시작은 속도 기록을 지우지 않는다`() {
        val d = EventDetector()
        d.onSample(snap(0.0, 50.0))
        d.onTripStart()
        assertEquals(listOf(RiskType.HARSH_ACCEL), d.onSample(snap(1.0, 61.0)).map { it.type })
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
