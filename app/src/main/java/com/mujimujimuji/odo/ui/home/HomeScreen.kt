package com.mujimujimuji.odo.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mujimujimuji.odo.R
import com.mujimujimuji.odo.cosmetic.CosmeticCatalog
import com.mujimujimuji.odo.cosmetic.Loadout
import com.mujimujimuji.odo.cosmetic.Slot
import com.mujimujimuji.odo.drive.DriveUi
import com.mujimujimuji.odo.engine.AchievementRule
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.engine.LevelTable
import com.mujimujimuji.odo.ui.components.DemoChip
import com.mujimujimuji.odo.ui.components.FitCaption
import com.mujimujimuji.odo.ui.components.Gauge
import com.mujimujimuji.odo.ui.components.Hairline
import com.mujimujimuji.odo.ui.components.Key
import com.mujimujimuji.odo.ui.components.KeyStrip
import com.mujimujimuji.odo.ui.components.NoticeLine
import com.mujimujimuji.odo.ui.components.OdoIcon
import com.mujimujimuji.odo.ui.components.OdoPanel
import com.mujimujimuji.odo.ui.components.PanelOutline
import com.mujimujimuji.odo.ui.map.CorridorData
import com.mujimujimuji.odo.ui.map.NorthUpScene
import com.mujimujimuji.odo.ui.map.bandAnchor
import com.mujimujimuji.odo.ui.map.homeHeadingDeg
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoShape
import com.mujimujimuji.odo.ui.theme.OdoSize
import com.mujimujimuji.odo.ui.theme.OdoSpace
import com.mujimujimuji.odo.ui.theme.OdoType

/** 홈 패널이 보여 주는 집계값. 모두 기기 안 Room에서 온다. */
data class HomeStats(
    val weeklySafeKm: Int,
    val yesterdaySafeKm: Int,
    val streakDays: Int,
    val unlockedCount: Int,
    val achievementTotal: Int,
    val collected: Int,
    val collectibleTotal: Int,
    val phoneLinked: Boolean,
)

/**
 * 홈(정차) — 설계서 §5.1. 풀블리드 north-up 지도, 지도 위 현재 위치에 Top 미니카(진행 방향으로 회전), 아래 패널 1장(이번 주 안전 주행 + 레벨 + 하드키 열).
 * 현재 위치는 패널(과 알림 줄)에 가리지 않는 지도 띠의 가운데에 온다(656×548 창에서 y 172dp, 에셋 계약 v2 '배치').
 * 패널은 통계 167dp + 헤어라인 + KeyStrip(캡션 줄 포함 92dp) = 260dp로 바닥에 붙는다(설계서 252dp에서 +8: KeyStrip 셀이 76dp에 세 줄을 담지 못해서다).
 * 글꼴 배율이 커지면 KeyStrip만큼 위로 자란다. 알림 1줄(NoticeLine 592×40, [notice]가 있을 때만)은 패널 위 4dp에 붙인다 —
 * 1.0 캡처에서 알림 y 247–287, 사이 4, 패널 선 291(fix_r3_notice_10). 패널이 자라면 함께 올라가 패널 선과 겹치지 않는다. 데모 칩은 fake 빌드에서만.
 */
@Composable
fun HomeScreen(
    ui: DriveUi,
    loadout: Loadout,
    stats: HomeStats,
    data: CorridorData?,
    center: LatLng?,
    demoChipText: String?,
    onDemoChipLongPress: (() -> Unit)?,
    onAchievements: () -> Unit,
    onGarage: () -> Unit,
    onPhone: () -> Unit,
    onStartDemo: () -> Unit,
    onResetDemo: () -> Unit,
    modifier: Modifier = Modifier,
    notice: String? = null,
    onDismissNotice: () -> Unit = {},
) {
    val c = LocalOdoColors.current
    val colorLabel = CosmeticCatalog.item(Slot.COLOR, loadout.colorId).label
    val heroDesc = stringResource(R.string.home_hero_desc, loadout.vehicle.label, colorLabel)
    // 패널 자리(px): 그 위 가장자리에 걸치는 휴게소 픽토를 지도에서 뺀다(반만 보이는 픽토 방지).
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var panelH by remember { mutableIntStateOf(0) }
    val cover = if (panelH > 0 && boxSize.height > 0) {
        Rect(0f, (boxSize.height - panelH).toFloat(), boxSize.width.toFloat(), boxSize.height.toFloat())
    } else {
        Rect.Zero
    }
    // 미니카 자리 = 현재 위치: 위 캡션·칩 줄과 아래 열(알림 줄 + 패널) 사이, 지도가 보이는 띠의 가운데에 둔다.
    var bottomH by remember { mutableIntStateOf(0) }
    val bandTopPx = with(LocalDensity.current) { HOME_BAND_TOP.toPx() }
    val anchor = if (boxSize.height > 0 && bottomH > 0) {
        bandAnchor(boxSize.height.toFloat(), bandTopPx, bottomH.toFloat())
    } else {
        HOME_ANCHOR_NOMINAL
    }
    val heading = remember(ui.headingDeg, data, center) { homeHeadingDeg(ui.headingDeg, data, center) }
    Box(modifier.fillMaxSize().background(c.ground).onSizeChanged { boxSize = it }) {
        NorthUpScene(
            data,
            center,
            Modifier.fillMaxSize(),
            cover = cover,
            attributionTop = true,
            anchor = anchor,
            headingDeg = heading,
            minicar = loadout,
            minicarDesc = heroDesc,
        )
        if (demoChipText != null) {
            DemoChip(
                demoChipText,
                Modifier.align(Alignment.TopEnd).padding(top = OdoSpace.xl, end = OdoSpace.margin),
                onLongPress = onDemoChipLongPress,
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomH = it.height },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (notice != null) {
                NoticeLine(text = notice, onDismiss = onDismissNotice, modifier = Modifier.width(NOTICE_W))
                Spacer(Modifier.height(OdoSpace.xs))
            }
            OdoPanel(
                Modifier.fillMaxWidth().onSizeChanged { panelH = it.height },
                shape = OdoShape.key,
                outline = PanelOutline.Top,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(STATS_H)
                            .padding(start = OdoSpace.margin, end = OdoSpace.margin, top = OdoSpace.m),
                    ) {
                        SafeKmColumn(ui, stats, Modifier.weight(1f).fillMaxHeight())
                        Spacer(Modifier.width(OdoSpace.xxl))
                        LevelColumn(ui, Modifier.width(LEVEL_W).fillMaxHeight())
                    }
                    Hairline()
                    HomeKeys(ui, stats, onAchievements, onGarage, onPhone, onStartDemo, onResetDemo)
                }
            }
        }
    }
}

@Composable
private fun SafeKmColumn(ui: DriveUi, stats: HomeStats, modifier: Modifier) {
    val c = LocalOdoColors.current
    val unit = stringResource(R.string.unit_km)
    val goal = ui.goal
    // 거리 업적이면 남은 km. 글꼴 배율 1.3 등으로 한 줄을 넘으면 제목을 뺀 짧은 꼴로 바꾼다(말줄임 대신, impeccable §4-14).
    val remainingKm = when (val rule = goal?.achievement?.rule) {
        is AchievementRule.SafeDistance -> (rule.km - ui.profile.totalSafeKm).coerceAtLeast(0)
        is AchievementRule.NightSafeDistance -> (rule.km - ui.profile.nightSafeKm).coerceAtLeast(0)
        is AchievementRule.RainSafeDistance -> (rule.km - ui.profile.rainSafeKm).coerceAtLeast(0)
        else -> null
    }
    val goalCaption = when {
        goal == null -> stringResource(R.string.home_next_goal_done)
        remainingKm != null -> stringResource(R.string.home_next_goal, goal.achievement.title, remainingKm)
        else -> stringResource(R.string.home_next_goal_plain, goal.achievement.title)
    }
    val goalCaptionShort = remainingKm?.let { stringResource(R.string.home_next_goal_short, it) }
    // 연속 기록을 한 번도 시작하지 않은 새 프로필(실신호 빌드 첫 실행)에는 '다시 1일째예요'를 띄우지 않는다(끊겼을 때만, 설계서 §5.1).
    val streak = when {
        stats.streakDays > 0 -> stringResource(R.string.home_streak_days, stats.streakDays)
        ui.profile.streak.lastDate == null -> null
        else -> stringResource(R.string.home_streak_reset)
    }
    // 행 높이는 고정(52 / 4 / 32 / 32 / 4 / 26 = 150, 위 여백 12 → 162 ≤ STATS_H). 오른쪽 열의 게이지와 같은 y(120)에 게이지가 놓인다.
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(TITLE_ROW_H)) {
            Text(stringResource(R.string.home_week_title), Modifier.alignByBaseline(), style = OdoType.body, color = c.inkDim, maxLines = 1)
            Spacer(Modifier.width(OdoSpace.l))
            Text(stats.weeklySafeKm.toString(), Modifier.alignByBaseline(), style = OdoType.numeralM, color = c.ink96, maxLines = 1)
            Spacer(Modifier.width(OdoSpace.s))
            Text(unit, Modifier.alignByBaseline(), style = OdoType.title, color = c.inkDim, maxLines = 1)
        }
        Spacer(Modifier.height(OdoSpace.xs))
        StatLine(stringResource(R.string.home_today), "${ui.todaySafeKm} $unit", null)
        StatLine(stringResource(R.string.home_yesterday), "${stats.yesterdaySafeKm} $unit", streak)
        Gauge(progress = goal?.progress ?: 1f, thickness = OdoSize.gaugeParked, modifier = Modifier.fillMaxWidth())
        FitCaption(goalCaption, goalCaptionShort)
    }
}

/**
 * 오늘/어제 한 줄(설계서 §5.1): 라벨 body 24 `inkDim` + 값 body 24를 고정 폭 열에 오른쪽 정렬(tnum이라 "0 km"와 "42 km"의 km가 세로로 맞는다).
 * 두 열 모두 최소 폭이라 큰 글꼴에서는 글자만큼 넓어진다(자르지 않음). 스트릭 캡션은 줄 끝.
 */
@Composable
private fun StatLine(label: String, value: String, trailing: String?) {
    val c = LocalOdoColors.current
    Row(Modifier.fillMaxWidth().height(STAT_ROW_H), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.widthIn(min = STAT_LABEL_W), style = OdoType.body, color = c.inkDim, maxLines = 1, softWrap = false)
        Text(
            value,
            Modifier.widthIn(min = STAT_VALUE_W),
            style = OdoType.body,
            color = c.ink96,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.End,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(trailing, style = OdoType.caption, color = c.inkDim, maxLines = 1)
        }
    }
}

@Composable
private fun LevelColumn(ui: DriveUi, modifier: Modifier) {
    val c = LocalOdoColors.current
    val xp = ui.profile.totalXp
    val level = LevelTable.levelForXp(xp)
    val span = LevelTable.progress(xp)
    val caption = if (span != null) {
        stringResource(R.string.home_next_level, level + 1, span.second - xp)
    } else {
        stringResource(R.string.home_max_level)
    }
    val progress = if (span != null && span.second > span.first) {
        ((xp - span.first).toFloat() / (span.second - span.first)).coerceIn(0f, 1f)
    } else {
        1f
    }
    // 행 격자 52 / 36 / 32 = 120 → 캡션은 왼쪽 '어제' 행, 게이지는 왼쪽 열과 같은 y(120)에 놓인다.
    // 칭호는 Lv와 같은 베이스라인(설계서 §5.1)이 아니라 둘째 줄에 둔다: "초보 딱지 뗌"(Lv 5)부터 224dp 한 줄에 안 들어가 말줄임됐다.
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(TITLE_ROW_H)) {
            Text(stringResource(R.string.level_short, level), Modifier.alignByBaseline(), style = OdoType.numeralM, color = c.ink96, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().height(LEVEL_TITLE_ROW_H), verticalAlignment = Alignment.CenterVertically) {
            Text(LevelTable.titleForLevel(level), style = OdoType.title, color = c.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().height(STAT_ROW_H), verticalAlignment = Alignment.CenterVertically) {
            Text(caption, style = OdoType.caption, color = c.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Gauge(progress = progress, thickness = OdoSize.gaugeParked, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun HomeKeys(
    ui: DriveUi,
    stats: HomeStats,
    onAchievements: () -> Unit,
    onGarage: () -> Unit,
    onPhone: () -> Unit,
    onStartDemo: () -> Unit,
    onResetDemo: () -> Unit,
) {
    val achievements = Key(
        label = stringResource(R.string.home_key_achievements),
        icon = OdoIcon.Medal,
        caption = stringResource(R.string.count_of_total, stats.unlockedCount, stats.achievementTotal),
        onClick = onAchievements,
    )
    val garage = Key(
        label = stringResource(R.string.home_key_garage),
        icon = OdoIcon.Garage,
        caption = stringResource(R.string.count_of_total, stats.collected, stats.collectibleTotal),
        onClick = onGarage,
    )
    val phone = Key(
        label = stringResource(R.string.home_key_phone),
        icon = OdoIcon.Phone,
        caption = stringResource(if (stats.phoneLinked) R.string.home_phone_linked else R.string.home_phone_unlinked),
        onClick = onPhone,
    )
    val keys = if (ui.simulated) {
        listOf(
            achievements,
            garage,
            phone,
            Key(
                label = stringResource(if (ui.demoPlaying) R.string.home_key_demo_playing else R.string.home_key_demo),
                icon = OdoIcon.Play,
                enabled = !ui.demoPlaying,
                onClick = onStartDemo,
            ),
            Key(label = stringResource(R.string.home_key_reset), icon = OdoIcon.Reset, onClick = onResetDemo),
        )
    } else {
        listOf(achievements, garage, phone)
    }
    KeyStrip(keys, Modifier.fillMaxWidth())
}

/** 지도가 보이는 띠의 위 끝: 위 여백 24 아래 출처 캡션(줄 26)·데모 칩(36) 줄. 표준 창에서 계약의 y 172dp가 나오게 56으로 잡았다. */
private val HOME_BAND_TOP = 56.dp
/** 크기를 재기 전(첫 프레임)의 미니카 자리: 표준 창 656×548dp, 패널 260dp → 띠 56~288dp의 가운데 y 172dp. */
private val HOME_ANCHOR_NOMINAL = Offset(0.5f, 172f / 548f)
private val STATS_H = 167.dp
private val TITLE_ROW_H = 52.dp // numeralM 줄 높이
private val LEVEL_TITLE_ROW_H = 36.dp // 칭호(title 28/36) 줄 높이
private val LEVEL_W = 224.dp
private val STAT_ROW_H = 32.dp
private val STAT_LABEL_W = 56.dp
private val STAT_VALUE_W = 96.dp // "300 km"(body 24, tnum)이 들어가는 값 열
private val NOTICE_W = 592.dp
