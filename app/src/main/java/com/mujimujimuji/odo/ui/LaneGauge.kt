package com.mujimujimuji.odo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.mujimujimuji.odo.ui.theme.Road

/** 중앙선 점선이 차오르는 게이지. 칸 사이 틈이 있어 한눈에 진행 정도를 읽는다. */
@Composable
fun LaneGauge(progress: Float, modifier: Modifier = Modifier, dashes: Int = 10) {
    Canvas(modifier) {
        val gap = size.height * 0.9f
        val dashW = (size.width - gap * (dashes - 1)) / dashes
        val filled = progress.coerceIn(0f, 1f) * dashes
        val r = CornerRadius(size.height * 0.25f)
        for (i in 0 until dashes) {
            val x = i * (dashW + gap)
            drawRoundRect(Road.Seam, Offset(x, 0f), Size(dashW, size.height), r)
            val part = (filled - i).coerceIn(0f, 1f)
            if (part > 0f) drawRoundRect(Road.CenterLine, Offset(x, 0f), Size(dashW * part, size.height), r)
        }
    }
}
