package com.mujimujimuji.odo.platform

import com.mujimujimuji.odo.engine.Gear
import com.mujimujimuji.odo.engine.TurnSignal
import kotlinx.coroutines.flow.Flow

/** 차량 신호. 받을 수 없는 신호는 null을 흘려 엔진이 해당 판정을 건너뛰게 한다. */
interface VehicleSignals {
    val speedKmh: Flow<Double>
    val gear: Flow<Gear>
    val driverBelt: Flow<Boolean?>
    val turnSignal: Flow<TurnSignal?>
    val wiperOn: Flow<Boolean?>
    val odometerKm: Flow<Double?>
}
