package com.mujimujimuji.odo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoShape
import com.mujimujimuji.odo.ui.theme.OdoSpace
import com.mujimujimuji.odo.ui.theme.OdoType
import kotlinx.coroutines.delay

/**
 * 지도 하단 알림 1줄(설계서 §5.0) 40dp: `surface` + 1dp `line`, 본문 24. 6 s 뒤 자동 해제, 탭하면 즉시 해제.
 * 40dp는 최소 높이라 큰 글꼴에서는 글자만큼 자란다(자르지 않음). 홈에서만 그린다(HomeScreen이 패널 위 4dp에 붙임).
 */
@Composable
fun NoticeLine(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier, autoDismissMs: Long = AUTO_DISMISS_MS) {
    val c = LocalOdoColors.current
    LaunchedEffect(text) {
        delay(autoDismissMs)
        onDismiss()
    }
    Box(
        modifier
            .heightIn(min = NOTICE_HEIGHT)
            .background(c.surface, OdoShape.chip)
            .border(OdoShape.hairline, c.line, OdoShape.chip)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onDismiss)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = OdoSpace.l),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = OdoType.body, color = c.ink96, maxLines = 1, softWrap = false)
    }
}

private val NOTICE_HEIGHT = 40.dp
private const val AUTO_DISMISS_MS = 6_000L
