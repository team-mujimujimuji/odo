package com.mujimujimuji.odo.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoShape
import com.mujimujimuji.odo.ui.theme.OdoSpace
import com.mujimujimuji.odo.ui.theme.OdoType
import kotlinx.coroutines.withTimeoutOrNull

/**
 * fake 빌드 전용 표시 칩(설계서 §5.0): 1dp `line` 외곽선, 캡션 20, 36dp. 글랜스에는 두지 않는다.
 * [onLongPress]가 있으면 700 ms 길게 누르기(주야 강제 전환, 캡처용).
 */
@Composable
fun DemoChip(text: String, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null) {
    val c = LocalOdoColors.current
    val gesture = if (onLongPress != null) {
        Modifier.pointerInput(onLongPress) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val up = withTimeoutOrNull(LONG_PRESS_MS) { waitForUpOrCancellation() }
                if (up == null) onLongPress()
            }
        }
    } else {
        Modifier
    }
    Box(
        modifier
            .height(CHIP_HEIGHT)
            .border(OdoShape.hairline, c.line, OdoShape.chip)
            .then(gesture)
            .padding(horizontal = OdoSpace.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = OdoType.caption, color = c.inkDim, maxLines = 1, softWrap = false)
    }
}

private val CHIP_HEIGHT = 36.dp
private const val LONG_PRESS_MS = 700L
