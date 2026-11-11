package com.mujimujimuji.odo.platform

import android.os.SystemClock
import com.mujimujimuji.odo.engine.Gear
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.engine.Thresholds
import com.mujimujimuji.odo.engine.TurnSignal
import com.mujimujimuji.odo.engine.VehicleSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 엔진에 넣을 신호 묶음의 흐름. 실제 SDK 조합과 시나리오 재생이 이 인터페이스를 구현한다. */
interface SignalSource {
    val snapshots: Flow<VehicleSnapshot>
    val simulated: Boolean
}

/**
 * 개별 신호 Flow를 하나의 [VehicleSnapshot] 흐름으로 묶는다.
 * 속도·기어가 바뀔 때마다, 그리고 [tickMs]마다 최신 값으로 하나씩 낸다.
 *
 * 차속을 한 번도 받지 못했으면(CAR_SPEED 거절, 등록 실패) 주행거리를 싣지 않는다. 속도 0만 보는 감지기로는
 * 위험 판정을 할 수 없는데 주행거리로 거리만 쌓이면 판정 없는 XP가 붙기 때문이다. 이때 거리와 XP는 0이고,
 * 기어는 그대로 실려 주행 중 잠금은 유지된다.
 *
 * 차속에는 차량이 그 값을 만든 시각을 벽시계로 바꿔 싣는다([VehicleSnapshot.speedTimeMs]). 틱·기어 변경 스냅샷도
 * 마지막 차속과 그 시각을 그대로 싣는다. 급가속·급감속 판정이 묵은 차속과 받는 시각의 흔들림을 가려내는 데 쓴다.
 * [elapsedClock]은 차량 시각과 같은 기준(SystemClock.elapsedRealtime)의 지금 시각이다.
 */
class LiveSignalSource(
    private val vehicle: VehicleSignals,
    private val location: LocationSource,
    private val navi: NaviBridge,
    private val tickMs: Long = 1_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
) : SignalSource {
    override val simulated = false

    private data class Latest(
        val gear: Gear = Gear.UNKNOWN,
        val speedKmh: Double = 0.0,
        /** [speedKmh]를 차량이 만든 시각(벽시계 ms). 차속을 아직 못 받았으면 null. */
        val speedTimeMs: Long? = null,
        /** 차속 표본을 하나라도 받았는지. 기본값 0.0과 '속도를 모름'을 가른다. */
        val speedSeen: Boolean = false,
        val location: LatLng? = null,
        val driverBelt: Boolean? = null,
        val turnSignal: TurnSignal? = null,
        val wiperOn: Boolean? = null,
        val odometerKm: Double? = null,
        val speedLimitKmh: Int? = null,
    )

    override val snapshots: Flow<VehicleSnapshot> = channelFlow {
        val latest = MutableStateFlow(Latest())
        // 앱은 이 흐름을 Dispatchers.Default에서 모아 차속·기어·틱이 서로 다른 스레드에서 emitNow를 부른다.
        // 최신값·시각 읽기와 보내기를 한 묶음으로 해야 늦게 읽은 시각이 먼저 나가지 않는다.
        // 시각이 거꾸로 가면 급가속·급감속 판정(EventDetector)이 어긋난다.
        val emitLock = Mutex()
        suspend fun emitNow() = emitLock.withLock {
            val l = latest.value
            send(
                VehicleSnapshot(
                    timeMs = clock(),
                    gear = l.gear,
                    speedKmh = l.speedKmh,
                    location = l.location,
                    driverBelt = l.driverBelt,
                    allBelts = null, // 동승자 탑승 신호가 없어 1차는 생략
                    turnSignal = l.turnSignal,
                    wiperOn = l.wiperOn,
                    odometerKm = l.odometerKm.takeIf { l.speedSeen },
                    speedLimitKmh = l.speedLimitKmh,
                    speedTimeMs = l.speedTimeMs,
                ),
            )
        }
        launch {
            vehicle.speedSamples.collect { v ->
                val madeMs = wallTimeOf(v)
                latest.update { it.copy(speedKmh = v.kmh, speedTimeMs = madeMs, speedSeen = true) }
                emitNow()
            }
        }
        launch { vehicle.gear.collect { g -> latest.update { it.copy(gear = g) }; emitNow() } }
        launch { vehicle.driverBelt.collect { b -> latest.update { it.copy(driverBelt = b) } } }
        launch { vehicle.turnSignal.collect { t -> latest.update { it.copy(turnSignal = t) } } }
        launch { vehicle.wiperOn.collect { w -> latest.update { it.copy(wiperOn = w) } } }
        launch { vehicle.odometerKm.collect { o -> latest.update { it.copy(odometerKm = o) } } }
        launch { location.location.collect { p -> latest.update { it.copy(location = p) } } }
        launch { navi.speedLimitKmh.collect { s -> latest.update { it.copy(speedLimitKmh = s) } } }
        while (true) {
            emitNow()
            delay(tickMs)
        }
    }

    /**
     * 차량이 차속을 만든 시각을 벽시계로 바꾼다. 받은 순간의 나이(elapsedRealtime 기준)를 지금 벽시계에서 뺀다.
     * 차량 시각이 없거나, 미래이거나, [Thresholds.MAX_SPEED_SAMPLE_AGE_MS]보다 오래됐으면 시계 기준이 어긋난 것으로 보고 받은 시각을 쓴다.
     */
    private fun wallTimeOf(v: SpeedSample): Long {
        val now = clock()
        val made = v.elapsedRealtimeMs ?: return now
        val age = elapsedClock() - made
        return if (age in 0..Thresholds.MAX_SPEED_SAMPLE_AGE_MS) now - age else now
    }
}
