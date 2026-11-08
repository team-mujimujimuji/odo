package com.mujimujimuji.odo.platform.pleos

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.abs

/** Pleos SDK에 차속 API가 없어 AAOS 표준 차량 속성 PERF_VEHICLE_SPEED(m/s)를 쓴다. 권한: android.car.permission.CAR_SPEED */
class CarSpeed(context: Context) {
    private val car: Car? = runCatching { Car.createCar(context.applicationContext) }
        .onFailure { Log.w(TAG, "Car.createCar failed", it) }
        .getOrNull()

    val speedKmh: Flow<Double> = callbackFlow {
        val manager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                val mps = (value.value as? Float) ?: return
                trySend(abs(mps) * 3.6)
            }

            override fun onErrorEvent(propertyId: Int, zone: Int) {
                Log.w(TAG, "speed error $propertyId")
            }
        }
        val ok = runCatching {
            manager?.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, SAMPLE_RATE_HZ) == true
        }.onFailure { Log.w(TAG, "speed register failed", it) }.getOrDefault(false)
        if (!ok) Log.w(TAG, "speed not available")
        awaitClose { runCatching { manager?.unregisterCallback(callback) } }
    }

    fun release() {
        runCatching { car?.disconnect() }
    }

    private companion object {
        const val TAG = "OdoSpeed"
        const val SAMPLE_RATE_HZ = 10f
    }
}
