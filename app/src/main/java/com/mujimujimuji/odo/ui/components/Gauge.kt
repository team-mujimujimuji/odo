package com.mujimujimuji.odo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoSpace
import kotlin.math.max

/**
 * 진행 게이지(설계서 §5.0). 트윈 없음 — 값이 바뀌면 즉시 그린다. 주행 12dp, 정차 4dp.
 * [endIcon]이 있으면 게이지 끝 8dp 뒤에 둔다(글랜스 메달).
 */
@Composable
fun Gauge(
    progress: Float,
    thickness: Dp,
    modifier: Modifier = Modifier,
    trackColor: Color = LocalOdoColors.current.track,
    fillColor: Color = LocalOdoColors.current.amber,
    contentDescription: String? = null,
    endIcon: (@Composable () -> Unit)? = null,
) {
    val p = progress.coerceIn(0f, 1f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(
            Modifier
                .weight(1f)
                .height(thickness)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(p, 0f..1f)
                    if (contentDescription != null) this.contentDescription = contentDescription
                },
        ) {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(trackColor, cornerRadius = r)
            if (p > 0f) {
                drawRoundRect(fillColor, size = Size(max(size.width * p, size.height), size.height), cornerRadius = r)
            }
        }
        if (endIcon != null) {
            Spacer(Modifier.width(OdoSpace.s))
            endIcon()
        }
    }
}
