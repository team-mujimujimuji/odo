package com.mujimujimuji.odo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoShape
import com.mujimujimuji.odo.ui.theme.OdoSize
import com.mujimujimuji.odo.ui.theme.OdoSpace
import com.mujimujimuji.odo.ui.theme.OdoType

/**
 * 차고 선택 칩(설계서 §5.0): 76dp 높이, 최소 폭 120, 모서리 12, 좌우 안쪽 여백 12. 선택 = 2dp `amber` 테두리,
 * 잠김 = 1dp `inkDim` 외곽선 + 이름 아래 "Lv N" 캡션(2열 칩에서도 이름 옆으로 밀려 넘치지 않게), 그 밖 = 1dp `line`.
 * [swatch]는 24dp 원 + 1dp `ink` 38% 링(어두운 페인트도 바탕과 구분), [leading]은 썸네일 글리프. 리플 없음, 눌림 = `ink` 8%.
 */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    locked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    swatch: Color? = null,
    lockedCaption: String? = null,
    contentDescription: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = LocalOdoColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val borderWidth = if (selected) 2.dp else OdoShape.hairline
    val borderColor = when {
        selected -> c.amber
        locked -> c.inkDim
        else -> c.line
    }
    val labelColor = if (locked) c.ink.copy(alpha = DISABLED_ALPHA) else c.ink96
    val description = contentDescription ?: label
    Row(
        modifier
            .height(OdoSize.touch)
            .widthIn(min = CHIP_MIN_WIDTH)
            .clip(OdoShape.chip)
            .selectable(selected, interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .background(if (pressed) c.ink.copy(alpha = PRESSED_ALPHA) else c.ink.copy(alpha = 0f))
            .border(borderWidth, borderColor, OdoShape.chip)
            .semantics(mergeDescendants = true) { this.contentDescription = description }
            .padding(horizontal = OdoSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (swatch != null) {
            val ring = c.ink.copy(alpha = SWATCH_RING_ALPHA)
            Canvas(Modifier.size(SWATCH_SIZE)) {
                drawCircle(swatch)
                drawCircle(ring, radius = size.minDimension / 2f - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            }
            Spacer(Modifier.width(OdoSpace.m))
        } else if (leading != null) {
            leading()
            Spacer(Modifier.width(OdoSpace.m))
        }
        Column {
            Text(label, style = OdoType.body, color = labelColor, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            if (locked && lockedCaption != null) {
                Text(lockedCaption, style = OdoType.caption, color = c.inkDim, maxLines = 1, softWrap = false)
            }
        }
    }
}

private val CHIP_MIN_WIDTH = 120.dp
private val SWATCH_SIZE = 24.dp
/** 모든 견본(램프 칩·차고 페인트 점)의 1dp 안쪽 링 = `ink` 38%(설계서 §1.1). 어두운 견본·잠겨 흐린 견본도 테두리가 보인다. */
internal const val SWATCH_RING_ALPHA = 0.38f
