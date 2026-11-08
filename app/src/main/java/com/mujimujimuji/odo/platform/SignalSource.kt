package com.mujimujimuji.odo.platform

import com.mujimujimuji.odo.engine.Gear
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.engine.TurnSignal
import com.mujimujimuji.odo.engine.VehicleSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 엔진에 넣을 신호 묶음의 흐름. 실제 SDK 조합과 시나리오 재생이 이 인터페이스를 구현한다. */
interface SignalSource {
    val snapshots: Flow<VehicleSnapshot>
    val simulated: Boolean
}

/**
 * 개별 신호 Flow를 하나의 [VehicleSnapshot] 흐름으로 묶는다.
 * 속도·기어가 바뀔 때마다, 그리고 [tickMs]마다 최신 값으로 하나씩 낸다.
 */
class LiveSignalSource(
    private val vehicle: VehicleSignals,
    private val location: LocationSource,
    private val navi: NaviBridge,
    private val tickMs: Long = 1_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : SignalSource {
    override val simulated = false

    private data class Latest(
        val gear: Gear = Gear.UNKNOWN,
        val speedKmh: Double = 0.0,
        val location: LatLng? = null,
        val driverBelt: Boolean? = null,
        val turnSignal: TurnSignal? = null,
        val wiperOn: Boolean? = null,
        val odometerKm: Double? = null,
        val speedLimitKmh: Int? = null,
    )

    override val snapshots: Flow<VehicleSnapshot> = channelFlow {
        val latest = MutableStateFlow(Latest())
        suspend fun emitNow() {
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
                    odometerKm = l.odometerKm,
                    speedLimitKmh = l.speedLimitKmh,
                ),
            )
        }
        launch { vehicle.speedKmh.collect { v -> latest.update { it.copy(speedKmh = v) }; emitNow() } }
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
}
