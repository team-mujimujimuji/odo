package com.mujimujimuji.odo.ui.map

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.mujimujimuji.odo.ui.theme.OdoColors
import com.mujimujimuji.odo.ui.theme.OdoSize

/** 휴게소 픽토의 선 2종(테두리 1dp, 손잡이 1.5dp). 그리는 쪽이 밀도별로 `remember` 해 넘긴다 — 프레임마다 Stroke를 만들지 않게. */
class RestPictoStrokes(density: Float) {
    val ring = Stroke(1f * density)
    val handle = Stroke(1.5f * density)
}

/**
 * 휴게소 픽토(설계서 §4.2, 1종만): 36dp([OdoSize.picto]) `surface` 원 + 1dp `line` 테두리 + 20dp 컵 벡터(`ink`).
 * 화면 좌표에 그려 원근·축척과 무관하게 같은 크기. 글자 없음. 선은 [strokes]를 받아 쓰고 나머지는 값 클래스(Offset·Size)라 그릴 때 할당이 없다.
 */
fun DrawScope.drawRestPicto(center: Offset, colors: OdoColors, strokes: RestPictoStrokes) {
    val px = density
    val r = OdoSize.picto.toPx() / 2f
    drawCircle(colors.surface, r, center)
    drawCircle(colors.line, r - strokes.ring.width / 2f, center, style = strokes.ring)
    // 컵 몸체 11×9, 손잡이 반원, 받침 선 — 20dp 상자 안.
    drawRoundRect(
        colors.ink,
        topLeft = Offset(center.x - 7f * px, center.y - 6f * px),
        size = Size(11f * px, 9f * px),
        cornerRadius = CornerRadius(2.5f * px),
    )
    drawArc(
        colors.ink,
        startAngle = -90f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(center.x + 2f * px, center.y - 4.5f * px),
        size = Size(6f * px, 6f * px),
        style = strokes.handle,
    )
    drawLine(
        colors.ink,
        Offset(center.x - 8f * px, center.y + 6f * px),
        Offset(center.x + 6f * px, center.y + 6f * px),
        strokeWidth = strokes.handle.width,
        cap = StrokeCap.Round,
    )
}
