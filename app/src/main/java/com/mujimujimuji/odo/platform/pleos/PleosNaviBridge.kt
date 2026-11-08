package com.mujimujimuji.odo.platform.pleos

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.platform.NaviBridge
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Pleos NaviHelper 어댑터. 1차는 제한속도만 쓴다(안내 중일 때만 들어온다). */
class PleosNaviBridge(context: Context) : NaviBridge {
    private val navi = NaviHelper(context.applicationContext).apply {
        initialize({}, { Log.w(TAG, "navi init failed", it) })
    }

    override val speedLimitKmh: Flow<Int?> = callbackFlow {
        trySend(null)
        val listener = object : NaviHelperEventListener {
            override fun onCurrentRoadSpeedLimit(limit: Int) {
                trySend(limit.takeIf { it > 0 })
            }

            override fun onRouteEnded() {
                trySend(null)
            }
        }
        runCatching { navi.addListener(listener) }.onFailure { Log.w(TAG, "navi listener failed", it) }
        awaitClose { runCatching { navi.removeListener(listener) } }
    }.distinctUntilChanged()

    override fun sendDestination(name: String, location: LatLng) {
        Log.i(TAG, "sendDestination은 2차 범위")
    }

    fun release() {
        runCatching { navi.release() }
    }

    private companion object {
        const val TAG = "OdoNavi"
    }
}
