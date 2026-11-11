package com.mujimujimuji.odo.platform.pleos

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.util.Log
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.platform.LocationSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Pleos FusedLocation 어댑터. 위치는 기기 안에서만 쓰고 저장·전송하지 않는다(README '안전·프라이버시 원칙', 설계서 §11). */
class PleosLocationSource(context: Context) : LocationSource {
    private val fused = FusedLocation(context.applicationContext).apply { initialize() }

    override val location: Flow<LatLng> = callbackFlow {
        val listener = object : FusedLocationListener {
            override fun onChanged(data: LocationData?) {
                if (data != null) trySend(LatLng(data.latitude, data.longitude))
            }

            override fun onError(e: Exception) {
                Log.w(TAG, "location error", e)
            }
        }
        runCatching { fused.registerFusedLocationCallback(listener) }
            .onFailure { Log.w(TAG, "location register failed", it) }
        awaitClose { runCatching { fused.unregisterFusedLocationCallback(listener) } }
    }

    fun release() {
        runCatching { fused.release() }
    }

    private companion object {
        const val TAG = "OdoLocation"
    }
}
