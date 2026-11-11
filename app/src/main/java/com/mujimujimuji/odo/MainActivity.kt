package com.mujimujimuji.odo

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.mujimujimuji.odo.ui.OdoRoot
import com.mujimujimuji.odo.ui.map.CorridorStore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as OdoApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.USE_FAKE_SIGNALS) startLiveSignals()
        // 코리도 지도(assets)를 미리 읽어 첫 글랜스가 계기 모드로 깜빡이지 않게 한다. 네트워크 없음.
        lifecycleScope.launch { CorridorStore.load(applicationContext) }
        // 테마(밤/낮)는 OdoRoot가 잠금 상태를 보고 고른다(주행 중 전환 없음).
        setContent { OdoRoot(app) }
    }

    private fun startLiveSignals() {
        val missing = RUNTIME_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) app.controller.startLive() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 거절해도 신호 수집은 시작한다. 기어 D/R로 거는 주행 중 잠금(README '안전·프라이버시 원칙')이 스냅샷에 달려 있다.
        // 위치를 거절하면: 트립과 XP는 돌지만 장소·도로 업적은 판정하지 않고, 지도는 계기 모드가 된다.
        // 차속을 거절하면: 위험 판정을 할 수 없어 거리와 XP가 쌓이지 않는다(LiveSignalSource).
        // 나중에 설정에서 허용하면 앱을 다시 실행해야 반영된다.
        permissions.forEachIndexed { i, p ->
            if (grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED) Log.w(TAG, "permission denied: $p")
        }
        app.controller.startLive()
    }

    private companion object {
        const val TAG = "OdoMain"
        val RUNTIME_PERMISSIONS = listOf("android.car.permission.CAR_SPEED", "pleos.car.permission.FUSED_LOCATION")
    }
}
