package com.mujimujimuji.odo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoShape

/** 패널 테두리 모양: 사방 · 위쪽 1줄만(홈 하단 패널) · 왼쪽 가장자리 부착(글랜스, 상·우·하만). */
enum class PanelOutline { All, Top, OpenLeft }

/**
 * 정차·주행 공용 패널(설계서 §5.0): `surface` 채움 + 1dp `line` 헤어라인, 그림자 0. 패널 안에 패널을 두지 않는다.
 */
@Composable
fun OdoPanel(
    modifier: Modifier = Modifier,
    shape: Shape = OdoShape.panel,
    outline: PanelOutline = PanelOutline.All,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = LocalOdoColors.current
    Box(
        modifier
            .background(c.surface, shape)
            .panelOutline(c.line, shape, outline),
        content = content,
    )
}

/** 1dp 가로 헤어라인. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(OdoShape.hairline).background(LocalOdoColors.current.line))
}

/** 1dp 세로 헤어라인. */
@Composable
fun VerticalHairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight().width(OdoShape.hairline).background(LocalOdoColors.current.line))
}

private fun Modifier.panelOutline(color: Color, shape: Shape, outline: PanelOutline): Modifier = when (outline) {
    PanelOutline.All -> border(OdoShape.hairline, color, shape)
    PanelOutline.Top -> drawBehind {
        val y = OdoShape.hairline.toPx() / 2f
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = OdoShape.hairline.toPx())
    }
    // 글랜스에서는 지도와 같은 레이어라 프레임마다 다시 그린다 → 경로·선은 크기가 바뀔 때만 만든다(drawWithCache).
    PanelOutline.OpenLeft -> drawWithCache {
        val sw = OdoShape.hairline.toPx()
        val half = sw / 2f
        val r = 16.dp.toPx()
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(0f, half)
            lineTo(w - r - half, half)
            arcTo(Rect(w - 2 * r - half, half, w - half, 2 * r + half), -90f, 90f, false)
            lineTo(w - half, h - r - half)
            arcTo(Rect(w - 2 * r - half, h - 2 * r - half, w - half, h - half), 0f, 90f, false)
            lineTo(0f, h - half)
        }
        val stroke = Stroke(width = sw)
        onDrawBehind { drawPath(path, color, style = stroke) }
    }
}
