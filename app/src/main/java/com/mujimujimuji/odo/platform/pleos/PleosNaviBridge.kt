package com.mujimujimuji.odo.platform.pleos

import ai.pleos.playground.navi.data.DestinationArrivedInfo
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

            // 안내가 끝나면 제한속도를 모르는 상태(null, 과속 판정 생략)로 돌린다. 마지막 값이 남으면
            // 안내 없이 달리는 다른 도로에서 옛 제한속도로 과속을 잡는다. SDK는 취소·도착을 이 두 콜백으로만 알린다.
            override fun onRouteCancelled() {
                trySend(null)
            }

            override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
                trySend(null)
            }

            // NaviHelper 3.0.5에서 폐기 예정(대체: onRoutingCompleted)인 경로 탐색 완료 콜백이다. 안내 종료가 아니다.
            // 지워도 과속 판정을 덜 하는 쪽이라 남겨 둔다. onRoutingCompleted에서는 지우지 않는다(안내 시작 직후라).
            @Suppress("OVERRIDE_DEPRECATION")
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
