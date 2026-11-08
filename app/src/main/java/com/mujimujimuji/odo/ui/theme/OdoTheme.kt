package com.mujimujimuji.odo.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.LineBreak

/** 도로 색: 아스팔트 바탕, 흰 차선 글자, 노란 중앙선 강조 하나. */
object Road {
    val Asphalt = Color(0xFF1F2834)
    val Shoulder = Color(0xFF2A3544)
    val Seam = Color(0xFF3A4757)
    val Lane = Color(0xFFEEF1F5)
    val Muted = Color(0xFF93A1B3)
    val CenterLine = Color(0xFFF2C230)
    val OnCenterLine = Color(0xFF231B00)
}

@Composable
fun OdoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Road.CenterLine,
            onPrimary = Road.OnCenterLine,
            background = Road.Asphalt,
            onBackground = Road.Lane,
            surface = Road.Shoulder,
            onSurface = Road.Lane,
            onSurfaceVariant = Road.Muted,
            outline = Road.Seam,
        ),
    ) {
        // 한국어를 글자 단위가 아니라 띄어쓰기 단위로 줄바꿈한다.
        ProvideTextStyle(LocalTextStyle.current.copy(lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)), content)
    }
}
