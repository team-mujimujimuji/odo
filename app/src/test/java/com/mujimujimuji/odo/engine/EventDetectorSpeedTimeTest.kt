package com.mujimujimuji.odo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 실제 신호 경로(LiveSignalSource)의 스냅샷으로 급가속·급감속을 판정한다.
 * 차속은 초당 10번쯤 오고, 1초 틱과 기어 변경은 그때까지 받은 마지막 차속을 새 스냅샷 시각에 다시 싣는다.
 * 그 묵은 차속을 스냅샷 시각에 두면 1초 창이 묵은 만큼 짧아져 기준보다 약한 가감속이 걸린다.
 * 스냅샷에 실린 차속 시각([VehicleSnapshot.speedTimeMs])으로 이를 가려낸다.
 */
class EventDetectorSpeedTimeTest {

    private fun count(snaps: List<VehicleSnapshot>, type: RiskType): Int {
        val d = EventDetector()
        return snaps.sumOf { s -> d.onSample(s).count { it.type == type } }
    }

    /** 실제 경로의 스냅샷: [tMs]에 내보냈고, 실린 차속은 [speedAtMs]에 얻은 값이다(둘 다 T0 기준 ms). */
    private fun live(tMs: Long, speed: Double, speedAtMs: Long) =
        VehicleSnapshot(T0 + tMs, Gear.DRIVE, speed, driverBelt = true, speedTimeMs = T0 + speedAtMs)

    /**
     * 초당 10번 오는 매끄러운 차속(간격 100 ± [periodJitterMs] ms, 시드 고정)에 1초 틱을 처리 순서대로 섞는다.
     * 차속 표본은 얻은 순간 스냅샷이 되고, 틱(간격 1,000~1,003 ms, 위상 무작위)은 마지막 차속과 그 시각을 틱 시각에 싣는다.
     * 2초 정속 뒤 초당 [rate] km/h(감속은 음수)로 바뀌어 [to]에서 멈추고 3초 더 간다.
     */
    private fun tenHzWithTicks(seed: Int, from: Double, rate: Double, to: Double, periodJitterMs: Int = 10): List<VehicleSnapshot> {
        val rng = Random(seed)
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        fun v(t: Long) = if (t < 2_000) from else (from + rate * (t - 2_000) / 1000.0).coerceIn(lo, hi)
        val endMs = 2_000 + ((to - from) / rate * 1000).toLong() + 3_000
        val out = ArrayList<VehicleSnapshot>()
        var speedAt = rng.nextLong(0, 100)
        var tick = rng.nextLong(0, 1_000)
        var last: Long? = null
        while (speedAt <= endMs || tick <= endMs) {
            if (speedAt <= tick) {
                out += live(speedAt, v(speedAt), speedAt)
                last = speedAt
                speedAt += 100 + rng.nextInt(-periodJitterMs, periodJitterMs + 1)
            } else {
                last?.let { out += live(tick, v(it), it) }
                tick += 1_000 + rng.nextInt(0, 4)
            }
        }
        return out
    }

    /**
     * 실험실 재현: [tickMs] 틱이 2,000 ms에 얻은 차속을 싣고, 3,000 ms 다음 차속은 [tickMs] + 1,001 ms에 온다.
     * 틱을 다음 점(2,100 ms)에서 40 ms 넘게 떼어 둔다. 틱을 스냅샷 시각에 두면 그 사이가 가파른 계단으로 읽히지 않아
     * 1초 창이 묵은 만큼 그대로 짧아진다(차속 시각 처리가 없으면 이 테스트가 실패한다).
     */
    private fun staleTickCase(from: Double, rate: Double, tickMs: Long): List<VehicleSnapshot> {
        fun v(t: Long) = from + rate * t / 1000.0
        val out = ArrayList<VehicleSnapshot>()
        for (t in (0L..3_000L step 100L) + (tickMs + 1_001L)) {
            out += live(t, v(t), t)
            if (t == 2_000L) out += live(tickMs, v(2_000), 2_000)
        }
        return out
    }

    @Test
    fun `틱이 예전 차속을 새 시각에 다시 실어도 1초 창이 줄지 않는다`() {
        assertEquals("감속 7.2", 0, count(staleTickCase(100.0, -7.2, tickMs = 2_060), RiskType.HARSH_DECEL))
        assertEquals("가속 10.6", 0, count(staleTickCase(20.0, 10.6, tickMs = 2_045), RiskType.HARSH_ACCEL))
    }

    @Test
    fun `완만한 감속 7_0과 가속 10_5는 1초 틱과 주기 흔들림이 있어도 0건`() {
        val actual = mapOf(
            "감속 7.0" to SEEDS.map { count(tenHzWithTicks(it, 100.0, -7.0, 30.0), RiskType.HARSH_DECEL) },
            "가속 10.5" to SEEDS.map { count(tenHzWithTicks(it, 20.0, 10.5, 100.0), RiskType.HARSH_ACCEL) },
        )
        assertEquals(actual.mapValues { (_, v) -> v.map { 0 } }, actual)
    }

    @Test
    fun `기준을 조금 넘는 감속 7_6·8_0과 가속 11_2는 1초 틱과 주기 흔들림이 있어도 정확히 1건`() {
        val actual = mapOf(
            "감속 7.6" to SEEDS.map { count(tenHzWithTicks(it, 100.0, -7.6, 30.0), RiskType.HARSH_DECEL) },
            "감속 8.0" to SEEDS.map { count(tenHzWithTicks(it, 100.0, -8.0, 30.0), RiskType.HARSH_DECEL) },
            "가속 11.2" to SEEDS.map { count(tenHzWithTicks(it, 20.0, 11.2, 100.0), RiskType.HARSH_ACCEL) },
        )
        assertEquals(actual.mapValues { (_, v) -> v.map { 1 } }, actual)
    }

    /**
     * 값이 바뀔 때만 오는 차속(에뮬레이터 VHAL): 정속 동안 차속 표본은 없고 틱만 마지막 값과 그 시각을 싣는다.
     * 차속을 얻은 지 [Thresholds.SPEED_HOLD_CONFIRM_MS] 넘게 지난 틱은 "그때까지 같은 속도"라는 확인이라 틱 시각의 기록이 된다.
     * 그러지 않으면 정속 뒤 첫 차속이 3초 넘게 끊긴 것으로 보여 기록이 지워지고, 바로 뒤 1초 급제동을 놓친다.
     */
    @Test
    fun `값이 바뀔 때만 오는 차속도 몇 초 정속 뒤 1초 급제동을 잡는다`() {
        assertEquals(500L, Thresholds.SPEED_HOLD_CONFIRM_MS)
        val snaps = ArrayList<VehicleSnapshot>()
        snaps += live(0, 100.0, 0)
        for (t in 550L..4_550L step 1_000L) snaps += live(t, 100.0, 0) // 틱만 온다
        for (k in 1..10) { // 1초 동안 10 Hz로 100 → 85
            val t = 5_000L + 100L * k
            snaps += live(t, 100.0 - 1.5 * k, t)
            if (k == 5) snaps += live(5_550, 100.0 - 1.5 * k, t)
        }
        snaps += live(6_550, 85.0, 6_000)
        snaps += live(7_550, 85.0, 6_000)
        assertEquals(1, count(snaps, RiskType.HARSH_DECEL))
    }

    /**
     * 값이 바뀔 때만 오는 차속. 4,550 ms 틱이 0 ms 차속(100)을 정속 확인 점으로 둔 뒤, 차량이 4,530 ms에 만든 98.5를 앱이
     * 4,580 ms에 받는다(틱이 차량이 만든 때와 앱이 받은 때 사이에 끼었다). 차속 시각이 앞 기록 점보다 이르지만 스냅샷 시각은
     * 앞으로 갔으니 벽시계가 거꾸로 간 것이 아니다. 기록을 버리면 0.9초 동안 판정을 못 해 바로 그 급제동을 놓친다.
     */
    @Test
    fun `차속 시각이 바로 앞 정속 확인 점보다 일러도 기록을 버리지 않고 그 뒤 급제동을 잡는다`() {
        val snaps = ArrayList<VehicleSnapshot>()
        snaps += live(0, 100.0, 0)
        for (t in 550L..4_550L step 1_000L) snaps += live(t, 100.0, 0)
        for (k in 0..9) { // 0.9초 동안 98.5 → 85, 받는 데 50 ms
            val made = 4_530L + 100L * k
            snaps += live(made + 50, 98.5 - 1.5 * k, made)
        }
        snaps += live(5_550, 85.0, 5_430)
        snaps += live(6_550, 85.0, 5_430)
        snaps += live(7_550, 85.0, 5_430)
        assertEquals(1, count(snaps, RiskType.HARSH_DECEL))
    }

    @Test
    fun `스냅샷 시각이 거꾸로 가면 차속 시각이 있어도 기록을 버리고 다시 센다`() {
        // 9초에 급감속 1건(초당 10) 뒤 벽시계가 10초 → 5초로 돌아갔다. 버리지 않으면 앞 이벤트 시각(9초)이 남아
        // 7초의 급감속을 같은 행동으로 보고 놓친다.
        val back = listOf(
            live(8_000, 110.0, 7_980), live(9_000, 100.0, 8_980), live(10_000, 100.0, 9_980),
            live(5_000, 50.0, 4_980), live(6_000, 50.0, 5_980), live(7_000, 40.0, 6_980),
        )
        assertEquals(2, count(back, RiskType.HARSH_DECEL))
        // 거꾸로 간 뒤 앞 기록이 1초 창에 남으면 거짓 급감속이 난다(EventDetectorTest의 시각 역행과 같은 모양).
        val stale = listOf(live(9_000, 100.0, 8_980), live(10_000, 100.0, 9_980), live(9_500, 60.0, 9_480), live(10_500, 60.0, 10_480))
        val d = EventDetector()
        assertTrue(stale.flatMap { d.onSample(it) }.isEmpty())
    }

    @Test
    fun `차속 시각이 스냅샷 시각보다 늦으면 스냅샷 시각으로 본다`() {
        // 차속 시각이 미래로 나와도 1초 창을 넓히지 않는다: 1초에 8 km/h 감소는 급감속이다.
        assertEquals(1, count(listOf(live(0, 100.0, 0), live(1_000, 92.0, 1_500)), RiskType.HARSH_DECEL))
    }

    /** 차량이 만든 시각(ms)의 차속 [v]와 마지막 표본을 만들 시각 [endMs]. */
    private class Profile(val endMs: Long, val v: (Long) -> Double)

    /** 2초 정속 뒤 초당 [rate] km/h(감속은 음수)로 바뀌어 [to]에서 멈추고 3초 더 간다. */
    private fun ramp(from: Double, rate: Double, to: Double): Profile {
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        return Profile(2_000 + ((to - from) / rate * 1000).toLong() + 3_000) { t ->
            if (t < 2_000) from else (from + rate * (t - 2_000) / 1000.0).coerceIn(lo, hi)
        }
    }

    /** 3초 정속 100 km/h → 1.0초 동안 8.5 km/h 감속 → 3초 정속. */
    private val brake = Profile(7_000) { t -> 100.0 - 8.5 * (t - 3_000).coerceIn(0, 1_000) / 1000.0 }

    /**
     * 차량은 100 ± 10 ms마다 정확한 시각을 붙여 차속을 만들고, 앱은 표본마다 [latency]만큼 늦게 받는다(순서는 지킨다).
     * 실제 경로(LiveSignalSource)를 흉내 낸다: 받은 차속에는 나이가 1,000 ms 이하이면 만든 시각, 아니면 받은 시각을 싣고,
     * 1초 틱(간격 1,000~1,003 ms, 위상 무작위)은 마지막으로 받은 차속과 그 시각을 다시 싣는다.
     */
    private fun delivered(seed: Int, p: Profile, latency: (Random) -> Long): List<VehicleSnapshot> {
        val rng = Random(seed)
        val made = ArrayList<Long>()
        var m = rng.nextLong(0, 100)
        while (m <= p.endMs) {
            made += m
            m += 100 + rng.nextInt(-10, 11)
        }
        var prev = 0L
        val recv = made.map { maxOf(prev, it + latency(rng)).also { r -> prev = r } }
        val out = ArrayList<VehicleSnapshot>()
        var tick = rng.nextLong(0, 1_000)
        var last: VehicleSnapshot? = null
        var i = 0
        while (i < made.size || tick <= recv.last()) {
            if (i < made.size && recv[i] <= tick) {
                val speedAt = if (recv[i] - made[i] <= 1_000) made[i] else recv[i]
                out += live(recv[i], p.v(made[i]), speedAt).also { last = it }
                i++
            } else {
                last?.let { out += it.copy(timeMs = T0 + tick) }
                tick += 1_000 + rng.nextInt(0, 4)
            }
        }
        return out
    }

    /**
     * 새 차속이 0.5초 넘게 늦게 와도(차량 서비스·스레드 사정) 차량이 만든 시각에 둔다.
     * 0.5초 규칙은 틱·기어 변경이 예전 차속을 다시 실은 스냅샷에만 쓴다. 새 차속까지 받은 시각에 두면 늦은 만큼 1초 창이
     * 어긋나, 기준 미만 감속이 급감속이 되거나(지연 600) 한 번의 감속을 두 번 세고 1초 급제동을 놓친다(지연 350 ± 250).
     */
    @Test
    fun `새 차속이 0_5초 넘게 늦게 와도 차량이 만든 시각으로 판정한다`() {
        val cases = listOf(
            Triple("감속 7.0", ramp(100.0, -7.0, 30.0), RiskType.HARSH_DECEL) to 0,
            Triple("감속 8.0", ramp(100.0, -8.0, 30.0), RiskType.HARSH_DECEL) to 1,
            Triple("1.0초 8.5 제동", brake, RiskType.HARSH_DECEL) to 1,
            Triple("가속 10.5", ramp(20.0, 10.5, 100.0), RiskType.HARSH_ACCEL) to 0,
            Triple("가속 11.2", ramp(20.0, 11.2, 100.0), RiskType.HARSH_ACCEL) to 1,
        )
        val actual = LinkedHashMap<String, List<Int>>()
        val expected = LinkedHashMap<String, List<Int>>()
        for ((model, latency) in LATENCIES) for ((case, want) in cases) {
            val (name, profile, type) = case
            actual["$model · $name"] = SEEDS.map { count(delivered(it, profile, latency), type) }
            expected["$model · $name"] = SEEDS.map { want }
        }
        assertEquals(expected, actual)
    }

    /**
     * 에뮬레이터 Virtual Console 차속: 값은 [step]씩 계단으로 [steps]번 바뀌고(간격 1,040~1,100 ms),
     * 앱은 100 또는 200 ms 간격으로 뜬 표본을 1~30 ms 뒤에 받는다. 표본 시각은 값이 바뀐 때가 아니라 표본을 뜬 때라서,
     * 바뀐 값을 처음 실은 표본만 보고는 그 앞 표본 간격 어디서 바뀌었는지 모른다. 1초 틱이 마지막 차속을 다시 싣는다.
     */
    private fun gridSteps(seed: Int, from: Double, step: Double, steps: Int): List<VehicleSnapshot> {
        val rng = Random(seed)
        val changes = ArrayList<Long>()
        var c = 2_000 + rng.nextLong(0, 100)
        repeat(steps) {
            changes += c
            c += rng.nextLong(1_040, 1_101)
        }
        fun v(t: Long) = from + step * changes.count { it <= t }
        val endMs = changes.last() + 3_000
        val out = ArrayList<VehicleSnapshot>()
        var sampleAt = rng.nextLong(0, 100)
        var recvAt = sampleAt + rng.nextLong(1, 31)
        var tick = rng.nextLong(0, 1_000)
        var last: VehicleSnapshot? = null
        while (sampleAt <= endMs) {
            if (recvAt <= tick) {
                out += live(recvAt, v(sampleAt), sampleAt).also { last = it }
                sampleAt += if (rng.nextBoolean()) 100 else 200
                recvAt = maxOf(recvAt, sampleAt + rng.nextLong(1, 31))
            } else {
                last?.let { out += it.copy(timeMs = T0 + tick) }
                tick += 1_000 + rng.nextInt(0, 4)
            }
        }
        return out
    }

    @Test
    fun `1초 넘게 떨어진 계단은 표본 간격이 고르지 않아도 한 1초 창에 합쳐 보지 않는다`() {
        val actual = linkedMapOf(
            "−6 계단 10번" to SEEDS.map { count(gridSteps(it, 100.0, -6.0, 10), RiskType.HARSH_DECEL) },
            "−7 계단 8번" to SEEDS.map { count(gridSteps(it, 100.0, -7.0, 8), RiskType.HARSH_DECEL) },
            "+6 계단 10번" to SEEDS.map { count(gridSteps(it, 40.0, 6.0, 10), RiskType.HARSH_ACCEL) },
            "+10 계단 6번" to SEEDS.map { count(gridSteps(it, 40.0, 10.0, 6), RiskType.HARSH_ACCEL) },
        )
        assertEquals(actual.mapValues { (_, v) -> v.map { 0 } }, actual)
    }

    @Test
    fun `한 번에 기준 넘게 바뀐 계단은 표본 간격이 고르지 않아도 정확히 1건`() {
        val actual = linkedMapOf(
            "100 → 92" to SEEDS.map { count(gridSteps(it, 100.0, -8.0, 1), RiskType.HARSH_DECEL) },
            "60 → 40" to SEEDS.map { count(gridSteps(it, 60.0, -20.0, 1), RiskType.HARSH_DECEL) },
            "30 → 42" to SEEDS.map { count(gridSteps(it, 30.0, 12.0, 1), RiskType.HARSH_ACCEL) },
            "30 → 45" to SEEDS.map { count(gridSteps(it, 30.0, 15.0, 1), RiskType.HARSH_ACCEL) },
        )
        assertEquals(actual.mapValues { (_, v) -> v.map { 1 } }, actual)
    }

    private companion object {
        val SEEDS = (1..8).toList()

        /** 표본마다 앱이 받기까지 걸리는 시간(ms). */
        val LATENCIES: Map<String, (Random) -> Long> = linkedMapOf(
            "지연 600" to { _ -> 600L },
            "지연 600 ± 50" to { r -> 600L + r.nextInt(-50, 51) },
            "지연 350 ± 250" to { r -> 350L + r.nextInt(-250, 251) },
        )
    }
}
