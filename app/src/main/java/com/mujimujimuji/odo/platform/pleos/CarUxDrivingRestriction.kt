package com.mujimujimuji.odo.platform.pleos

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import com.mujimujimuji.odo.platform.DrivingRestriction
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** AAOS 운전 중 UX 제한. 정차 0x0, 주행 중에는 distraction optimization이 요구된다. */
class CarUxDrivingRestriction(context: Context) : DrivingRestriction {
    private val car: Car? = runCatching { Car.createCar(context.applicationContext) }
        .onFailure { Log.w(TAG, "Car.createCar failed", it) }
        .getOrNull()

    override val restricted: Flow<Boolean> = callbackFlow {
        val manager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
        if (manager == null) {
            trySend(false)
        } else {
            runCatching {
                trySend(manager.currentCarUxRestrictions?.isRequiresDistractionOptimization == true)
                manager.registerListener { trySend(it.isRequiresDistractionOptimization) }
            }.onFailure { Log.w(TAG, "ux restriction failed", it); trySend(false) }
        }
        awaitClose { runCatching { manager?.unregisterListener() } }
    }.distinctUntilChanged()

    private companion object {
        const val TAG = "OdoUx"
    }
}
