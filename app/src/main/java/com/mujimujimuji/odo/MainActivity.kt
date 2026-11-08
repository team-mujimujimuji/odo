package com.mujimujimuji.odo

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.mujimujimuji.odo.platform.pleos.SdkProbe

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.USE_FAKE_SIGNALS) startProbe()
        setContent {
            MaterialTheme {
                PlaceholderScreen()
            }
        }
    }

    private fun startProbe() {
        val missing = RUNTIME_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        SdkProbe.start(this, lifecycleScope)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) recreate()
    }

    private companion object {
        val RUNTIME_PERMISSIONS = listOf("android.car.permission.CAR_SPEED", "pleos.car.permission.FUSED_LOCATION")
    }
}

// 1단계 확인용 빈 화면. 5단계에서 홈·글랜스·트립 요약으로 바꾼다.
@Composable
private fun PlaceholderScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1F3A)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "오도", color = Color.White, fontSize = 48.sp)
        Text(
            text = "v${BuildConfig.VERSION_NAME} · 신호: ${if (BuildConfig.USE_FAKE_SIGNALS) "시나리오 재생" else "Pleos SDK"}",
            color = Color(0xFF9DB7E0),
            fontSize = 20.sp,
        )
    }
}
