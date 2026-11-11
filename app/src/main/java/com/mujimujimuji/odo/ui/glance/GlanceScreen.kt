package com.mujimujimuji.odo.ui.glance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mujimujimuji.odo.R
import com.mujimujimuji.odo.cosmetic.Loadout
import com.mujimujimuji.odo.drive.DriveUi
import com.mujimujimuji.odo.ui.components.Gauge
import com.mujimujimuji.odo.ui.components.Medal
import com.mujimujimuji.odo.ui.components.OdoPanel
import com.mujimujimuji.odo.ui.components.PanelOutline
import com.mujimujimuji.odo.ui.map.GlanceMap
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoShape
import com.mujimujimuji.odo.ui.theme.OdoSize
import com.mujimujimuji.odo.ui.theme.OdoSpace
import com.mujimujimuji.odo.ui.theme.OdoType

/**
 * 글랜스(주행 중, 잠금, 설계서 §5.2): 숫자 1개(오늘 안전 km) + 게이지 1개(다음 업적) + 장면(지도·미니카).
 * 문단·리스트·버튼·위험 표시·시간 압박·트윈 없음. 데모 칩도 없다. 입력 차단은 OdoRoot의 DriveLockOverlay가 맡는다.
 */
@Composable
fun GlanceScreen(ui: DriveUi, loadout: Loadout, modifier: Modifier = Modifier) {
    val c = LocalOdoColors.current
    BoxWithConstraints(modifier.fillMaxSize().background(c.ground)) {
        // 미니카 = 지도 영역(패널 오른쪽) 가로 중앙(656dp에서 x 460, 설계서 §4.3). 지도는 패널 뒤까지 꽉 채워 그린다.
        val anchorX = if (maxWidth > PANEL_WIDTH) ((maxWidth + PANEL_WIDTH) / 2f) / maxWidth else 0.5f
        GlanceMap(ui, loadout, Modifier.fillMaxSize(), anchorX = anchorX)
        GlancePanel(
            ui,
            Modifier
                .align(Alignment.TopStart)
                .padding(vertical = PANEL_INSET)
                .width(PANEL_WIDTH)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun GlancePanel(ui: DriveUi, modifier: Modifier) {
    val c = LocalOdoColors.current
    val km = ui.todaySafeKm
    // 주행 중 게이지는 뒤로 가지 않는다(DriveController의 GlanceGaugeLatch: 목표가 바뀌거나 열려도 정차 전까지 유지).
    val gauge = ui.glance
    val progress = gauge.progress
    val label = stringResource(R.string.glance_label)
    val unit = stringResource(R.string.unit_km)
    val numberDesc = "$label $km $unit"
    // 접근성 문구만(보이는 글 없음). 목표가 없으면(거리 업적을 다 열었으면) 홈과 같은 말로 읽고, 메달은 같은 말을 두 번 읽지 않게 설명을 두지 않는다.
    val gaugeDesc = if (gauge.medalOpen) {
        stringResource(R.string.home_next_goal_done)
    } else {
        stringResource(R.string.glance_gauge_desc, (progress * 100).toInt().coerceIn(0, 100))
    }
    OdoPanel(modifier, shape = OdoShape.panelEdgeLeft, outline = PanelOutline.OpenLeft) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = OdoSpace.xl, end = OdoSpace.xl, top = OdoSpace.xxl, bottom = BOTTOM_INSET),
        ) {
            Text(label, style = OdoType.label, color = c.inkDim, maxLines = 1, softWrap = false)
            Spacer(Modifier.weight(GAP_LABEL_TO_NUMBER))
            // 단위는 숫자 뒤 같은 기준선(한국어 어순 "30 km"). 숫자는 tnum이라 자릿수가 같으면 km가 흔들리지 않는다.
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = numberDesc }) {
                Text(km.toString(), Modifier.alignByBaseline(), style = OdoType.numeralL, color = c.ink96, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(OdoSpace.s))
                Text(unit, Modifier.alignByBaseline(), style = OdoType.label, color = c.inkDim, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.weight(GAP_NUMBER_TO_GAUGE))
            Gauge(
                progress = progress,
                thickness = OdoSize.gaugeDrive,
                modifier = Modifier.fillMaxWidth().height(OdoSize.medal),
                contentDescription = gaugeDesc,
                endIcon = { Medal(unlocked = gauge.medalOpen) },
            )
        }
    }
}

private val PANEL_INSET = 24.dp
private val PANEL_WIDTH = 264.dp
private val BOTTOM_INSET = 96.dp

/** 라벨 ↔ 숫자 ↔ 게이지 사이 빈 공간 비율(배율 1.0에서 128dp : 76dp). 글꼴이 커지면 비율대로 줄어든다. */
private const val GAP_LABEL_TO_NUMBER = 128f
private const val GAP_NUMBER_TO_GAUGE = 76f
