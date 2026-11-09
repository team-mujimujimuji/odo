package com.mujimujimuji.odo.ui.phonelink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.mujimujimuji.odo.R
import com.mujimujimuji.odo.cosmetic.Loadout
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.platform.PhoneLinkState
import com.mujimujimuji.odo.ui.components.Hairline
import com.mujimujimuji.odo.ui.components.Key
import com.mujimujimuji.odo.ui.components.KeyStrip
import com.mujimujimuji.odo.ui.components.OdoIcon
import com.mujimujimuji.odo.ui.components.OdoIconGlyph
import com.mujimujimuji.odo.ui.components.OdoPanel
import com.mujimujimuji.odo.ui.components.PanelHeader
import com.mujimujimuji.odo.ui.map.CorridorData
import com.mujimujimuji.odo.ui.map.ScrimmedScene
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoShape
import com.mujimujimuji.odo.ui.theme.OdoSpace
import com.mujimujimuji.odo.ui.theme.OdoType
import com.mujimujimuji.odo.ui.vehicle.OdoVehicle
import com.mujimujimuji.odo.ui.vehicle.VehicleView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 휴대폰 연결(설계서 §5.6). 들어오면 코드를 발급하고(Idle → Presenting), 확인됨 1.5 s → 연결됨.
 * 만료는 조용히 재발급(카운트다운·만료 표시 없음). 주행 시작 시 OdoRoot가 대기를 접고 홈으로 돌린다.
 * 공유 범위는 글로 못 박는다: 트립 요약·업적·미니카 꾸미기만, 차량 신호 원본·위치 기록은 공유하지 않는다.
 * 이 두 줄은 판 아래 전체 폭에 한 줄씩 둔다(오른쪽 열 248dp에서는 "미니카 / 꾸미기"처럼 의미 단위가 꺾였다).
 * 확인됨은 QR 판을 그대로 두고 모듈만 흐린 위에 체크 원판을 겹치고, 연결됨은 틀 없이 내 미니카를 판 폭(272)으로 보여 준다
 * (앰버는 쓰지 않는다 — 앰버 4곳 규칙, 설계서 §1.1).
 */
@Composable
fun PhoneLinkScreen(
    state: PhoneLinkState,
    loadout: Loadout,
    data: CorridorData?,
    center: LatLng?,
    onStartPairing: () -> Unit,
    onDisconnect: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalOdoColors.current
    LaunchedEffect(state) { if (state is PhoneLinkState.Idle) onStartPairing() }
    // 확인됨(1.5 s)에도 같은 QR 판을 흐리게 남기려고 마지막으로 보여 준 QR 내용을 기억한다.
    val lastPayload = remember { mutableStateOf<String?>(null) }
    if (state is PhoneLinkState.Presenting) SideEffect { lastPayload.value = state.qrPayload }
    Box(modifier.fillMaxSize().background(c.ground)) {
        ScrimmedScene(data, center, Modifier.fillMaxSize())
        OdoPanel(Modifier.align(Alignment.Center).size(PANEL_W, PANEL_H)) {
            Column(Modifier.fillMaxSize()) {
                PanelHeader(stringResource(R.string.phone_title), null, onHome)
                Hairline()
                Column(Modifier.fillMaxSize().padding(OdoSpace.xl)) {
                    Row(Modifier.fillMaxWidth().height(PLATE)) {
                        LeftPlate(state, loadout, lastPayload.value, Modifier.size(PLATE))
                        Spacer(Modifier.width(OdoSpace.xl))
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            when (state) {
                                is PhoneLinkState.Presenting -> PresentingColumn(state.code)
                                PhoneLinkState.Confirmed -> Text(stringResource(R.string.phone_confirmed), style = OdoType.body, color = c.ink96)
                                is PhoneLinkState.Linked -> LinkedColumn(state, onDisconnect)
                                is PhoneLinkState.Failed -> Text(stringResource(R.string.phone_failed), style = OdoType.body, color = c.inkDim)
                                PhoneLinkState.Idle -> Unit
                            }
                        }
                    }
                    // 공유 범위 두 줄은 발급·확인됨(1.5 s)·연결됨 내내 같은 자리에 둔다(확인됨에서 잠깐 사라졌다 다시 나오지 않게).
                    if (state is PhoneLinkState.Presenting || state == PhoneLinkState.Confirmed || state is PhoneLinkState.Linked) {
                        Spacer(Modifier.weight(1f))
                        ShareScope()
                    }
                }
            }
        }
    }
}

@Composable
private fun LeftPlate(state: PhoneLinkState, loadout: Loadout, lastPayload: String?, modifier: Modifier) {
    val c = LocalOdoColors.current
    when (state) {
        is PhoneLinkState.Presenting -> QrPlate(state.qrPayload, modifier)
        PhoneLinkState.Confirmed -> Box(modifier, contentAlignment = Alignment.Center) {
            if (lastPayload != null) {
                QrPlate(lastPayload, dimmed = true)
            } else {
                Box(Modifier.fillMaxSize().background(c.qrPlate, OdoShape.panel))
            }
            Box(Modifier.size(CHECK_DISC).background(c.qrModule, CircleShape), contentAlignment = Alignment.Center) {
                OdoIconGlyph(OdoIcon.Check, tint = c.qrPlate, size = CHECK_GLYPH)
            }
        }
        is PhoneLinkState.Linked -> Box(modifier, contentAlignment = Alignment.Center) {
            OdoVehicle(loadout, VehicleView.Hero, Modifier.size(LINKED_HERO_W, LINKED_HERO_H))
        }
        else -> Box(modifier.border(OdoShape.hairline, c.line, OdoShape.panel))
    }
}

@Composable
private fun ColumnScope.PresentingColumn(code: String) {
    val c = LocalOdoColors.current
    CodeLine(code)
    Spacer(Modifier.height(OdoSpace.l))
    Text(stringResource(R.string.phone_scan), style = OdoType.body, color = c.ink96)
    Spacer(Modifier.height(OdoSpace.xs))
    Text(stringResource(R.string.phone_or_code), style = OdoType.body, color = c.inkDim)
    Spacer(Modifier.weight(1f))
    Text(stringResource(R.string.phone_app_pending), style = OdoType.caption, color = c.inkDim)
}

/**
 * 연결 코드 한 줄: numeralM(48, tnum, 설계서 §5.6). Roboto Bold 대문자 8자 + 하이픈은 약 256dp라 오른쪽 열(약 248dp)을
 * 조금 넘는다 → 넘칠 때만 열 폭에 맞게 글자 크기를 줄인다(글꼴 배율 1.3의 비선형 확대까지 다시 재서 맞춘다).
 */
@Composable
private fun CodeLine(code: String) {
    val c = LocalOdoColors.current
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxPx = constraints.maxWidth
        val style = remember(code, maxPx, measurer) { fitWidth(measurer, code, OdoType.numeralM, maxPx) }
        Text(code, style = style, color = c.ink96, maxLines = 1, softWrap = false)
    }
}

private fun fitWidth(measurer: TextMeasurer, text: String, base: TextStyle, maxPx: Int): TextStyle {
    var style = base
    repeat(FIT_PASSES) {
        val width = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
        if (width <= maxPx) return style
        style = style.copy(fontSize = style.fontSize * (maxPx.toFloat() / width * FIT_MARGIN))
    }
    return style
}

@Composable
private fun ShareScope() {
    val c = LocalOdoColors.current
    Text(stringResource(R.string.phone_share_yes), style = OdoType.caption, color = c.inkDim)
    Spacer(Modifier.height(OdoSpace.xs))
    Text(stringResource(R.string.phone_share_no), style = OdoType.caption, color = c.inkDim)
}

@Composable
private fun LinkedColumn(state: PhoneLinkState.Linked, onDisconnect: () -> Unit) {
    val c = LocalOdoColors.current
    var confirm by remember { mutableStateOf(false) }
    val pattern = stringResource(R.string.phone_date_format)
    val since = remember(state.linkedAtMs, pattern) {
        DateTimeFormatter.ofPattern(pattern, Locale.KOREA)
            .format(Instant.ofEpochMilli(state.linkedAtMs).atZone(ZoneId.systemDefault()).toLocalDate())
    }
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.phone_linked_since, since), style = OdoType.title, color = c.ink96, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (confirm) {
            Text(stringResource(R.string.phone_unlink_confirm), style = OdoType.body, color = c.ink96)
            Spacer(Modifier.height(OdoSpace.s))
            Hairline()
            KeyStrip(
                listOf(
                    Key(stringResource(R.string.phone_key_unlink), OdoIcon.Unlink, onClick = onDisconnect),
                    // 두 셀 모두 아이콘을 두어 라벨 줄 높이를 맞춘다(한쪽만 아이콘이면 라벨이 약 15dp 어긋난다).
                    Key(stringResource(R.string.phone_key_cancel), OdoIcon.Close, onClick = { confirm = false }),
                ),
                Modifier.fillMaxWidth(),
            )
        } else {
            Hairline()
            KeyStrip(
                listOf(Key(stringResource(R.string.phone_key_unlink), OdoIcon.Unlink, onClick = { confirm = true })),
                Modifier.fillMaxWidth(),
            )
        }
    }
}

private val PANEL_W = 592.dp
private val PANEL_H = 484.dp
private val PLATE = 272.dp
// 연결됨: 틀 없이 판 폭 그대로의 3:2 히어로(차고·홈과 같은 비율).
private val LINKED_HERO_W = 272.dp
private val LINKED_HERO_H = 181.dp
// 확인됨: 흐린 QR 위 체크 원판(qrModule) + 흰 판색 체크.
private val CHECK_DISC = 96.dp
private val CHECK_GLYPH = 56.dp
private const val FIT_PASSES = 3
private const val FIT_MARGIN = 0.99f
