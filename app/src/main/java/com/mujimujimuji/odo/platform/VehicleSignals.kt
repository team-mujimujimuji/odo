package com.mujimujimuji.odo.platform

import com.mujimujimuji.odo.engine.Gear
import com.mujimujimuji.odo.engine.TurnSignal
import kotlinx.coroutines.flow.Flow

/**
 * 차속 표본. [elapsedRealtimeMs]는 차량(VHAL)이 값을 만든 시각으로 SystemClock.elapsedRealtime과 같은 기준의 ms다.
 * 모르면 null이고, 그때는 앱이 받은 시각을 쓴다.
 */
data class SpeedSample(val kmh: Double, val elapsedRealtimeMs: Long? = null)

/** 차량 신호. 받을 수 없는 신호는 null을 흘려 엔진이 해당 판정을 건너뛰게 한다. */
interface VehicleSignals {
    val speedSamples: Flow<SpeedSample>
    val gear: Flow<Gear>
    val driverBelt: Flow<Boolean?>
    val turnSignal: Flow<TurnSignal?>
    val wiperOn: Flow<Boolean?>
    val odometerKm: Flow<Double?>
}
