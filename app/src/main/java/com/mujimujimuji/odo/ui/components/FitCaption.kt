package com.mujimujimuji.odo.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoType

/**
 * 캡션 1줄(`inkDim`). 글꼴 배율이 커서 [full]이 한 줄을 넘으면 잘리거나 말줄임되는 대신 [short]로 바꿔 다시 잰다
 * (넘침은 자르지 않고 문구로 푼다). 짧은 꼴이 없거나 그래도 넘치면 말줄임. 홈 다음 업적 캡션·요약 상한 캡션.
 */
@Composable
fun FitCaption(full: String, short: String?, modifier: Modifier = Modifier) {
    val c = LocalOdoColors.current
    var useShort by remember(full, short) { mutableStateOf(false) }
    Text(
        if (useShort && short != null) short else full,
        modifier,
        style = OdoType.caption,
        color = c.inkDim,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!useShort && short != null && it.hasVisualOverflow) useShort = true },
    )
}
