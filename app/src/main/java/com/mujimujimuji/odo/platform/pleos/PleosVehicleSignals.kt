package com.mujimujimuji.odo.platform.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.util.Log
import com.mujimujimuji.odo.engine.Gear
import com.mujimujimuji.odo.engine.TurnSignal
import com.mujimujimuji.odo.platform.SpeedSample
import com.mujimujimuji.odo.platform.VehicleSignals
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Pleos Vehicle SDK 어댑터. 속도는 Pleos SDK에 없어 AAOS 차량 속성([CarSpeed])에서 받는다.
 * 신호 구독이 실패하면 null을 흘려 해당 판정을 건너뛴다.
 */
class PleosVehicleSignals(context: Context, private val speed: CarSpeed) : VehicleSignals {
    private val vehicle = Vehicle(context.applicationContext).apply { initialize() }
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private val windshield = WindowArea(WindowValue.FRONT_WINDSHIELD)

    override val speedSamples: Flow<SpeedSample> get() = speed.samples

    override val gear: Flow<Gear> = callbackFlow {
        val listener = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) {
                trySend(gear?.toEngineGear() ?: Gear.UNKNOWN)
            }

            override fun onFailed(e: Exception) {
                Log.w(TAG, "gear failed", e)
                trySend(Gear.UNKNOWN)
            }
        }
        runCatching { vehicle.getGear().registerGearSelection(listener) }
            .onFailure { Log.w(TAG, "gear register failed", it) }
        awaitClose { runCatching { vehicle.getGear().unregisterGearSelection(listener) } }
    }.distinctUntilChanged()

    override val driverBelt: Flow<Boolean?> = callbackFlow {
        val listener = object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) {
                trySend(buckled)
            }

            override fun onFailed(e: Exception) {
                Log.w(TAG, "belt failed", e)
                trySend(null)
            }
        }
        runCatching { vehicle.getSeat().registerSeatBeltBuckledState(driverSeat, listener) }
            .onFailure { Log.w(TAG, "belt register failed", it); trySend(null) }
        awaitClose { runCatching { vehicle.getSeat().unregisterSeatBeltBuckledState(driverSeat, listener) } }
    }.distinctUntilChanged()

    override val turnSignal: Flow<TurnSignal?> = callbackFlow {
        val listener = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
                trySend(
                    when (value) {
                        TurnSignalValue.LEFT -> TurnSignal.LEFT
                        TurnSignalValue.RIGHT -> TurnSignal.RIGHT
                        else -> TurnSignal.OFF
                    },
                )
            }

            override fun onFailed(e: Exception) {
                Log.w(TAG, "turn signal failed", e)
                trySend(null)
            }
        }
        runCatching { vehicle.getTurnSignal().registerTurnSignalState(listener) }
            .onFailure { Log.w(TAG, "turn signal register failed", it); trySend(null) }
        awaitClose { runCatching { vehicle.getTurnSignal().unregisterTurnSignalState(listener) } }
    }.distinctUntilChanged()

    override val wiperOn: Flow<Boolean?> = callbackFlow {
        val listener = object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) {
                trySend(state?.let { it == WiperState.ON })
            }

            override fun onFailed(e: Exception) {
                Log.w(TAG, "wiper failed", e)
                trySend(null)
            }
        }
        runCatching { vehicle.getWiper().registerWiperState(windshield, listener) }
            .onFailure { Log.w(TAG, "wiper register failed", it); trySend(null) }
        awaitClose { runCatching { vehicle.getWiper().unregisterWiperState(windshield, listener) } }
    }.distinctUntilChanged()

    override val odometerKm: Flow<Double?> = callbackFlow {
        val listener = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(km: Float?) {
                trySend(km?.toDouble())
            }

            override fun onFailed(e: Exception) {
                Log.w(TAG, "odometer failed", e)
                trySend(null)
            }
        }
        runCatching { vehicle.getOdometer().registerDistanceDriven(listener) }
            .onFailure { Log.w(TAG, "odometer register failed", it); trySend(null) }
        awaitClose { runCatching { vehicle.getOdometer().unregisterDistanceDriven(listener) } }
    }.distinctUntilChanged()

    fun release() {
        runCatching { vehicle.release() }
        speed.release()
    }

    private fun VehicleGear.toEngineGear(): Gear = when (this) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE // D와 수동 1~9단
    }

    private companion object {
        const val TAG = "OdoVehicle"
    }
}
