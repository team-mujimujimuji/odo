package com.mujimujimuji.odo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoShape
import com.mujimujimuji.odo.ui.theme.OdoSize
import com.mujimujimuji.odo.ui.theme.OdoSpace
import com.mujimujimuji.odo.ui.theme.OdoType

/** KeyStrip 셀 1개. 아이콘(32) 위, 라벨(24) 가운데, 캡션(20) 아래로 쌓는다 — 셀 안쪽 폭 123dp에 "데모 재생"이 아이콘과 나란히 들어가지 않아서다. */
data class Key(
    val label: String,
    val icon: OdoIcon? = null,
    val caption: String? = null,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val contentDescription: String? = null,
    val onClick: () -> Unit,
)

/**
 * 차량 하드키 열처럼 읽히는 버튼 행(설계서 §5.0). 가로 등분 셀, 셀 사이 1dp `line`, 리플 없음.
 * 눌림 = `ink` 8% 채움, 선택 = 하단 2dp `amber`, 비활성 = 글자 `ink` 56%.
 * 어느 셀이든 캡션이 있으면 모든 셀이 캡션 줄을 비워 두어 라벨 줄이 맞는다. 캡션 줄이 있으면 92dp(아이콘 32 + 라벨 28 + 캡션 24 + 위아래 4),
 * 없으면 [minHeight](기본 76dp). 글꼴 배율이 커지면 그만큼 커진다(글자를 조이거나 자르지 않는다).
 * 폭도 같다: 모든 셀 내용이 등분 폭에 들어가면 등분하고, 큰 글꼴에서 어느 라벨이 등분 폭을 넘으면(2륜 차고 탭 "탑케이스" @1.3)
 * 셀마다 제 내용 폭을 먼저 주고 남는 폭을 고루 나눈다([keyCellWidths]). 말줄임은 내용 폭 합이 줄 폭을 넘을 때만 남는 안전망이다.
 * [compact]는 차고 탭처럼 셀이 좁을 때 라벨을 caption 크기로 그린다.
 */
@Composable
fun KeyStrip(keys: List<Key>, modifier: Modifier = Modifier, minHeight: Dp = OdoSize.touch, compact: Boolean = false) {
    val reserveCaption = keys.any { it.caption != null }
    Layout(
        content = {
            keys.forEachIndexed { i, key ->
                if (i > 0) VerticalHairline()
                KeyCell(key, reserveCaption, compact, Modifier)
            }
        },
        modifier = modifier.heightIn(min = minHeight),
    ) { measurables, constraints ->
        // 자식 순서: 셀, 헤어라인, 셀, 헤어라인, … (짝수 = 셀)
        val cells = measurables.filterIndexed { i, _ -> i % 2 == 0 }
        val lines = measurables.filterIndexed { i, _ -> i % 2 == 1 }
        val lineW = OdoShape.hairline.roundToPx()
        val needs = cells.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val avail = if (constraints.hasBoundedWidth) constraints.maxWidth - lines.size * lineW else needs.sum()
        val widths = keyCellWidths(needs, avail)
        val tallest = cells.indices.maxOfOrNull { cells[it].minIntrinsicHeight(widths[it]) } ?: 0
        val h = tallest.coerceIn(constraints.minHeight, constraints.maxHeight)
        val cellPlaceables = cells.indices.map { cells[it].measure(Constraints.fixed(widths[it], h)) }
        val linePlaceables = lines.map { it.measure(Constraints.fixed(lineW, h)) }
        val w = (widths.sum() + lines.size * lineW).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(w, h) {
            var x = 0
            cellPlaceables.forEachIndexed { i, p ->
                p.place(x, 0)
                x += p.width
                if (i < linePlaceables.size) {
                    linePlaceables[i].place(x, 0)
                    x += lineW
                }
            }
        }
    }
}

/**
 * 셀 폭(px). 내용 폭([needs])이 모두 등분 폭 이하이거나, 합이 [avail]을 넘어 어차피 다 못 담으면 등분한다(하드키 열 모양 유지).
 * 그 사이면 각 셀에 제 내용 폭 + 남는 폭의 1/n. 나머지 px는 앞 셀부터 1씩 더해 합이 정확히 [avail]이 된다.
 */
internal fun keyCellWidths(needs: List<Int>, avail: Int): List<Int> {
    val n = needs.size
    if (n == 0) return emptyList()
    val share = avail / n
    val base = if (needs.all { it <= share } || needs.sum() > avail) {
        List(n) { share }
    } else {
        val spare = (avail - needs.sum()) / n
        needs.map { it + spare }
    }
    var rest = avail - base.sum()
    return base.map { if (rest > 0) { rest--; it + 1 } else it }
}

@Composable
private fun KeyCell(key: Key, reserveCaption: Boolean, compact: Boolean, modifier: Modifier) {
    val c = LocalOdoColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val inkColor = if (key.enabled) c.ink96 else c.ink.copy(alpha = DISABLED_ALPHA)
    val description = key.contentDescription ?: listOfNotNull(key.label, key.caption).joinToString(", ")
    Box(
        modifier
            .clickable(interaction, indication = null, enabled = key.enabled, role = Role.Button, onClick = key.onClick)
            .background(if (pressed) c.ink.copy(alpha = PRESSED_ALPHA) else Color.Transparent)
            .drawBehind {
                if (key.selected) {
                    val t = SELECTED_LINE.toPx()
                    drawLine(c.amber, Offset(0f, size.height - t / 2f), Offset(size.width, size.height - t / 2f), strokeWidth = t)
                }
            }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = OdoSpace.xs),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(vertical = OdoSpace.xs), horizontalAlignment = Alignment.CenterHorizontally) {
            if (key.icon != null) OdoIconGlyph(key.icon, tint = inkColor)
            Text(
                key.label,
                style = if (compact) KeyLabelCompact else KeyLabel,
                color = inkColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (reserveCaption) {
                Text(
                    key.caption ?: "",
                    style = KeyCaption,
                    color = c.inkDim,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 줄 높이만 역할 기본(32/26)보다 조금 조인다(28/24): 한글 글리프 상자(≈글자 크기)에 위아래 2dp씩 남아 겹치지 않는다.
 * 글자 크기 자체는 역할 그대로(body 24 / caption 20). 글자 크기까지 조이면(이전 76dp 안) 글리프가 줄 상자 밖으로 삐져나와 창 아래에서 잘렸다.
 * caption 쪽은 공용 변형 [OdoType.captionTight](차고 그림 칸과 같은 값)을 쓴다.
 */
private val KeyLabel: TextStyle = OdoType.body.copy(lineHeight = 28.sp)
private val KeyLabelCompact: TextStyle = OdoType.captionTight
private val KeyCaption: TextStyle = OdoType.captionTight
private val SELECTED_LINE = 2.dp

internal const val PRESSED_ALPHA = 0.08f
internal const val DISABLED_ALPHA = 0.56f
