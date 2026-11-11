package com.mujimujimuji.odo.platform

import com.mujimujimuji.odo.engine.EventDetector
import com.mujimujimuji.odo.engine.Gear
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.engine.RiskType
import com.mujimujimuji.odo.engine.TurnSignal
import com.mujimujimuji.odo.engine.VehicleSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * 실제 신호 조합([LiveSignalSource]). 차속을 한 번도 받지 못했으면(CAR_SPEED 거절, 등록 실패) 주행거리를 싣지 않아
 * 위험 판정 없이 거리와 XP가 쌓이지 않게 한다. 기어 같은 다른 신호는 그대로 흘러 주행 중 잠금은 유지된다.
 * 차속에는 차량이 그 값을 만든 시각을 벽시계로 바꿔 싣는다(급가속·급감속 판정용).
 *
 * 시계는 모두 가상 시간이다: 벽시계 [WALL0] + 지금, elapsedRealtime [ELAPSED0] + 지금. JVM에서 SystemClock을 부르지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSignalSourceTest {
    private class FakeVehicle(
        override val speedSamples: Flow<SpeedSample>,
        override val odometerKm: Flow<Double?>,
        override val gear: Flow<Gear> = MutableStateFlow(Gear.DRIVE),
    ) : VehicleSignals {
        override val driverBelt: Flow<Boolean?> = MutableStateFlow(null)
        override val turnSignal: Flow<TurnSignal?> = MutableStateFlow(null)
        override val wiperOn: Flow<Boolean?> = MutableStateFlow(null)
    }

    private object NoLocation : LocationSource {
        override val location: Flow<LatLng> = MutableSharedFlow()
    }

    private object NoNavi : NaviBridge {
        override val speedLimitKmh: Flow<Int?> = MutableStateFlow(null)
        override fun sendDestination(name: String, location: LatLng) = Unit
    }

    private fun TestScope.source(vehicle: VehicleSignals) = LiveSignalSource(
        vehicle, NoLocation, NoNavi, tickMs = 1_000L,
        clock = { WALL0 + testScheduler.currentTime },
        elapsedClock = { ELAPSED0 + testScheduler.currentTime },
    )

    @Test
    fun `차속을 한 번도 못 받으면 주행거리를 싣지 않는다`() = runTest {
        val vehicle = FakeVehicle(speedSamples = MutableSharedFlow(), odometerKm = MutableStateFlow(1234.5))

        val snaps = source(vehicle).snapshots.take(6).toList()

        assertEquals(Gear.DRIVE, snaps.last().gear) // 기어는 그대로 흘러 잠금이 걸린다
        assertTrue(snaps.joinToString { "${it.odometerKm}" }, snaps.all { it.odometerKm == null })
        assertTrue(snaps.joinToString { "${it.speedTimeMs}" }, snaps.all { it.speedTimeMs == null })
    }

    @Test
    fun `차속 표본을 하나라도 받으면 0 km·h여도 주행거리를 싣는다`() = runTest {
        val vehicle = FakeVehicle(speedSamples = MutableStateFlow(SpeedSample(0.0)), odometerKm = MutableStateFlow(1234.5))

        val snaps = source(vehicle).snapshots.take(6).toList()

        assertEquals(1234.5, snaps.last().odometerKm!!, 0.0)
    }

    @Test
    fun `차속이 늦게 들어오면 그때부터 주행거리를 싣는다`() = runTest {
        val speed = MutableSharedFlow<SpeedSample>(replay = 1)
        val vehicle = FakeVehicle(speedSamples = speed, odometerKm = MutableStateFlow(1234.5))
        val out = mutableListOf<VehicleSnapshot>()
        val job = launch { source(vehicle).snapshots.toList(out) }

        advanceTimeBy(2_500)
        runCurrent()
        assertTrue(out.size >= 3)
        assertTrue(out.all { it.odometerKm == null })

        speed.emit(SpeedSample(30.0))
        runCurrent()
        assertEquals(30.0, out.last().speedKmh, 0.0)
        assertEquals(1234.5, out.last().odometerKm!!, 0.0)
        job.cancel()
    }

    @Test
    fun `차량이 차속을 만든 시각을 벽시계로 바꿔 싣고 틱은 그 시각을 그대로 싣는다`() = runTest {
        val speed = MutableSharedFlow<SpeedSample>(replay = 1)
        val out = mutableListOf<VehicleSnapshot>()
        val job = launch { source(FakeVehicle(speed, MutableStateFlow(null))).snapshots.toList(out) }

        advanceTimeBy(2_500)
        runCurrent()
        speed.emit(SpeedSample(50.0, ELAPSED0 + 2_500 - 40)) // 차량이 40 ms 전에 만든 값
        runCurrent()
        assertEquals(WALL0 + 2_500, out.last().timeMs)
        assertEquals(WALL0 + 2_460, out.last().speedTimeMs)

        advanceTimeBy(1_000) // 3,000 ms 틱
        runCurrent()
        assertEquals(WALL0 + 3_000, out.last().timeMs)
        assertEquals(50.0, out.last().speedKmh, 0.0)
        assertEquals(WALL0 + 2_460, out.last().speedTimeMs)
        job.cancel()
    }

    @Test
    fun `차량 시각이 미래이거나 너무 오래됐거나 없으면 받은 시각을 싣는다`() = runTest {
        val speed = MutableSharedFlow<SpeedSample>(replay = 1)
        val out = mutableListOf<VehicleSnapshot>()
        val job = launch { source(FakeVehicle(speed, MutableStateFlow(null))).snapshots.toList(out) }
        advanceTimeBy(5_000)
        runCurrent()
        val now = WALL0 + 5_000
        val elapsedNow = ELAPSED0 + 5_000

        val cases = linkedMapOf(
            "미래 10 ms" to elapsedNow + 10,
            "나이 1,001 ms" to elapsedNow - 1_001,
            "0(시계 기준이 다름)" to 0L,
            "모름" to null,
            "나이 1,000 ms(경계, 믿음)" to elapsedNow - 1_000,
            "나이 0 ms" to elapsedNow,
        )
        val actual = cases.mapValues { (_, made) ->
            speed.emit(SpeedSample(60.0, made))
            runCurrent()
            out.last().timeMs to out.last().speedTimeMs
        }

        val expected = cases.mapValues { (name, _) -> now to if (name.startsWith("나이 1,000")) now - 1_000 else now }
        assertEquals(expected, actual)
        job.cancel()
    }

    /**
     * 실제 앱 경로 전체: 차량은 100 ms마다 정확한 시각을 붙여 차속을 만들고, 앱은 100 ± [jitterMs] ms 뒤에 받는다
     * (차량 서비스·스레드 사정). [LiveSignalSource]의 1초 틱도 섞인다. 만든 시각(SpeedSample.elapsedRealtimeMs)이 정확하면
     * 받는 시각의 흔들림이 1초 창에 들어가지 않는다. 3초 정속 100 km/h → 초당 [rate]로 30 km/h까지 → 3초 정속.
     */
    private suspend fun TestScope.decelEvents(rate: Double, jitterMs: Int, seed: Int): Int {
        val rng = Random(seed)
        return decelEvents(rate) { g -> g + 100 + rng.nextInt(-jitterMs, jitterMs + 1) }
    }

    /** [decelEvents]의 바탕. 차량이 [phaseMs] + 100 ms × n에 만든 차속을 앱은 [receivedAt](만든 시각) 뒤 순서대로 받는다. */
    private suspend fun TestScope.decelEvents(rate: Double, phaseMs: Long = 0, receivedAt: (Long) -> Long): Int {
        val base = testScheduler.currentTime
        fun v(gen: Long) = if (gen < 3_000) 100.0 else maxOf(30.0, 100.0 + rate * (gen - 3_000) / 1000.0)
        val gens = (0L..3_000L + (70.0 / -rate * 1000).toLong() + 3_000L step 100L).map { it + phaseMs }
        var prev = 0L
        val recvs = gens.map { g -> maxOf(prev, receivedAt(g)).also { prev = it } }
        val speed = flow {
            for (i in gens.indices) {
                delay(base + recvs[i] - testScheduler.currentTime)
                emit(SpeedSample(v(gens[i]), ELAPSED0 + base + gens[i]))
            }
        }
        val detector = EventDetector()
        var decels = 0
        val job = launch {
            source(FakeVehicle(speed, MutableStateFlow(null))).snapshots.collect { s ->
                decels += detector.onSample(s).count { it.type == RiskType.HARSH_DECEL }
            }
        }
        advanceTimeBy(recvs.last() + 2_000)
        runCurrent()
        job.cancel()
        return decels
    }

    @Test
    fun `받는 시각이 흔들려도 차량 시각으로 판정해 감속 7_0은 0건 8_0은 1건`() = runTest {
        val actual = linkedMapOf(
            "감속 7.0" to E2E_CASES.map { (j, seed) -> decelEvents(-7.0, j, seed) },
            "감속 8.0" to E2E_CASES.map { (j, seed) -> decelEvents(-8.0, j, seed) },
        )
        val expected = linkedMapOf("감속 7.0" to E2E_CASES.map { 0 }, "감속 8.0" to E2E_CASES.map { 1 })
        assertEquals("흔들림·시드 $E2E_CASES", expected, actual)
    }

    /**
     * 차속 콜백을 받는 스레드가 1.1초 멈췄다 풀리면 그동안 온 차속이 1 ms 간격으로 몰려 들어온다.
     * 1초 틱은 다른 스레드라 멈춘 동안에도 예전 차속을 다시 싣는다. 몰려 온 차속도 차량이 만든 시각에 두어야 1초 창이
     * 줄지 않고, 멈춤 앞뒤의 같은 감속을 두 번 세지 않는다. 멈춤 시작은 감속 시작(3,000 ms) 뒤 1초 안의 17곳, 표본 위상은 4가지다.
     */
    @Test
    fun `차속이 1_1초 멈췄다 몰려 들어와도 감속 5는 0건 8은 1건`() = runTest {
        fun stalled(start: Long): (Long) -> Long = { g ->
            val n = g + 20
            if (n >= start && n < start + 1_100) start + 1_100 + (n - start) / 100 else n
        }
        val runs = listOf(0L, 25L, 50L, 75L).flatMap { ph -> (3_000L until 4_000L step 62L).map { ph to it } }
        val actual = linkedMapOf(
            "감속 5" to runs.map { (ph, start) -> decelEvents(-5.0, ph, stalled(start)) },
            "감속 8" to runs.map { (ph, start) -> decelEvents(-8.0, ph, stalled(start)) },
        )
        val expected = linkedMapOf("감속 5" to runs.map { 0 }, "감속 8" to runs.map { 1 })
        assertEquals("(표본 위상, 멈춤 시작) $runs", expected, actual)
    }

    /**
     * 앱은 이 흐름을 Dispatchers.Default에서 모은다(OdoApp). 차속·기어·틱이 서로 다른 스레드에서 시각을 읽고 보내므로,
     * 읽기와 보내기가 한 묶음이 아니면 늦게 읽은 시각이 먼저 나가 시각이 거꾸로 간다. 그러면 급가속·급감속 판정이 어긋난다.
     * 고치기 전에는 4만 개 중 85개가 거꾸로 갔다.
     */
    @Test
    fun `여러 스레드에서 신호가 겹쳐 와도 스냅샷 시각은 거꾸로 가지 않는다`() = runBlocking {
        val n = 20_000
        val vehicle = FakeVehicle(
            speedSamples = flow { repeat(n) { emit(SpeedSample((it % 120).toDouble())) } },
            odometerKm = MutableStateFlow(null),
            gear = flow { repeat(n) { emit(if (it % 2 == 0) Gear.DRIVE else Gear.REVERSE) } },
        )
        val calls = AtomicLong()
        val src = LiveSignalSource(
            vehicle, NoLocation, NoNavi, tickMs = 1L,
            clock = { calls.incrementAndGet() }, elapsedClock = { ELAPSED0 },
        )

        val times = withContext(Dispatchers.Default) {
            withTimeout(60_000) { src.snapshots.take(2 * n).map { it.timeMs }.toList() }
        }

        val reversed = times.zipWithNext().count { (a, b) -> b <= a }
        assertEquals("시각이 거꾸로 간 스냅샷 수", 0, reversed)
    }

    private companion object {
        const val WALL0 = 1_800_000_000_000L
        const val ELAPSED0 = 50_000L

        /** (받는 시각 흔들림 ms, 시드) */
        val E2E_CASES = listOf(30 to 1, 30 to 2, 100 to 1, 100 to 2, 100 to 3)
    }
}
