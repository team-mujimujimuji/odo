package com.mujimujimuji.odo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.Composable
import com.mujimujimuji.odo.ui.theme.Road

/** 옆에서 본 미니카. 자작 도형(외부 이미지 없음). */
@Composable
fun Minicar(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val wheelR = h * 0.17f
        val bodyTop = h * 0.42f
        val bodyBottom = h - wheelR * 0.9f
        // 지붕(캐빈)
        val cabin = Path().apply {
            moveTo(w * 0.24f, bodyTop + 2f)
            lineTo(w * 0.36f, h * 0.12f)
            lineTo(w * 0.66f, h * 0.12f)
            lineTo(w * 0.80f, bodyTop + 2f)
            close()
        }
        drawPath(cabin, lerp(color, Color.Black, 0.12f))
        val glass = Path().apply {
            moveTo(w * 0.30f, bodyTop)
            lineTo(w * 0.385f, h * 0.18f)
            lineTo(w * 0.505f, h * 0.18f)
            lineTo(w * 0.505f, bodyTop)
            close()
            moveTo(w * 0.535f, bodyTop)
            lineTo(w * 0.535f, h * 0.18f)
            lineTo(w * 0.645f, h * 0.18f)
            lineTo(w * 0.745f, bodyTop)
            close()
        }
        drawPath(glass, Color(0xFF233142))
        // 차체
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.04f, bodyTop),
            size = Size(w * 0.92f, bodyBottom - bodyTop),
            cornerRadius = CornerRadius(h * 0.14f),
        )
        drawRoundRect(
            color = lerp(color, Color.White, 0.35f),
            topLeft = Offset(w * 0.06f, bodyTop + (bodyBottom - bodyTop) * 0.28f),
            size = Size(w * 0.88f, h * 0.025f),
            cornerRadius = CornerRadius(h * 0.02f),
        )
        // 전조등·후미등
        drawRoundRect(Road.CenterLine, Offset(w * 0.90f, bodyTop + h * 0.06f), Size(w * 0.05f, h * 0.06f), CornerRadius(4f))
        drawRoundRect(Color(0xFFE5484D), Offset(w * 0.05f, bodyTop + h * 0.06f), Size(w * 0.035f, h * 0.06f), CornerRadius(4f))
        // 바퀴
        for (cx in listOf(w * 0.24f, w * 0.76f)) {
            val c = Offset(cx, h - wheelR)
            drawCircle(Color(0xFF141A22), wheelR, c)
            drawCircle(Color(0xFFB8C2CF), wheelR * 0.45f, c)
        }
    }
}
