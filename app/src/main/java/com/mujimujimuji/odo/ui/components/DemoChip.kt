package com.mujimujimuji.odo.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mujimujimuji.odo.R
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoShape
import com.mujimujimuji.odo.ui.theme.OdoSpace
import com.mujimujimuji.odo.ui.theme.OdoType

/**
 * fake 빌드 전용 표시 칩(설계서 §5.0): `ground` 60 % 바탕 + 1dp `line` 외곽선, 캡션 20, 36dp. 글랜스에는 두지 않는다.
 * [onLongPress]가 있으면 700 ms 길게 누르기(주야 강제 전환, 캡처용). 화면 낭독기에는 길게 누르기 동작 "주야 바꾸기"로 알린다.
 */
@Composable
fun DemoChip(text: String, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null) {
    val c = LocalOdoColors.current
    val actionLabel = stringResource(R.string.demo_chip_action)
    val gesture = if (onLongPress != null) {
        Modifier.pointerInput(onLongPress) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                // 시간 초과만 길게 누르기다. 700 ms 전에 떼거나 칩 밖으로 밀면(취소) waitForUpOrCancellation()이 null을 주므로 결과를 따로 본다.
                val timedOut = withTimeoutOrNull(LONG_PRESS_MS) { waitForUpOrCancellation(); true } == null
                if (timedOut) onLongPress()
            }
        }.semantics(mergeDescendants = true) { onLongClick(label = actionLabel) { onLongPress(); true } }
    } else {
        Modifier
    }
    Box(
        modifier
            .height(CHIP_HEIGHT)
            .background(c.ground.copy(alpha = SCRIM_ALPHA), OdoShape.chip)
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

/** 지도 위 칩 바탕 = `ground` 60 %(홈 ODbL 출처 캡션과 같은 스크림, 도로 위 inkDim 5:1 안팎). */
private const val SCRIM_ALPHA = 0.6f
