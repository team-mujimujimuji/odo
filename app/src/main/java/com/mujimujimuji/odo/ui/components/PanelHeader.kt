package com.mujimujimuji.odo.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mujimujimuji.odo.R
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoSize
import com.mujimujimuji.odo.ui.theme.OdoSpace
import com.mujimujimuji.odo.ui.theme.OdoType

/**
 * 정차 서브 화면(업적·차고·휴대폰) 공용 머리줄 76dp: 제목(title) + 캡션(caption, 선택) + 오른쪽 끝 "홈" 하드키 120dp.
 * 아래 1dp 헤어라인은 부르는 쪽이 둔다.
 */
@Composable
fun PanelHeader(title: String, caption: String?, onHome: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalOdoColors.current
    Row(modifier.fillMaxWidth().height(OdoSize.touch)) {
        Row(Modifier.weight(1f).fillMaxWidth().padding(start = OdoSpace.xl, end = OdoSpace.l, top = OdoSpace.l)) {
            Text(title, Modifier.alignByBaseline().semantics { heading() }, style = OdoType.title, color = c.ink96, maxLines = 1)
            if (caption != null) {
                Spacer(Modifier.width(OdoSpace.m))
                Text(
                    caption,
                    Modifier.alignByBaseline(),
                    style = OdoType.caption,
                    color = c.inkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        VerticalHairline()
        KeyStrip(
            listOf(Key(stringResource(R.string.key_home), OdoIcon.Home, onClick = onHome)),
            Modifier.width(HOME_KEY_WIDTH),
        )
    }
}

private val HOME_KEY_WIDTH = 120.dp
