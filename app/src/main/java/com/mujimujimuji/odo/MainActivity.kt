package com.mujimujimuji.odo

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mujimujimuji.odo.ui.OdoRoot
import com.mujimujimuji.odo.ui.theme.OdoTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as OdoApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.USE_FAKE_SIGNALS) startLiveSignals()
        setContent {
            OdoTheme { OdoRoot(app) }
        }
    }

    private fun startLiveSignals() {
        val missing = RUNTIME_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) app.controller.startLive() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 위치 권한을 거절해도 속도·기어만으로 트립과 XP는 돈다(장소 업적만 빠짐).
        app.controller.startLive()
    }

    private companion object {
        val RUNTIME_PERMISSIONS = listOf("android.car.permission.CAR_SPEED", "pleos.car.permission.FUSED_LOCATION")
    }
}
