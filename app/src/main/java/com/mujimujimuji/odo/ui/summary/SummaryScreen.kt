package com.mujimujimuji.odo.ui.summary

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.mujimujimuji.odo.R
import com.mujimujimuji.odo.cosmetic.CosmeticCatalog
import com.mujimujimuji.odo.cosmetic.Loadout
import com.mujimujimuji.odo.cosmetic.VehicleType
import com.mujimujimuji.odo.engine.AchievementDef
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.engine.LevelTable
import com.mujimujimuji.odo.engine.RiskType
import com.mujimujimuji.odo.engine.Thresholds
import com.mujimujimuji.odo.engine.TripSummary
import com.mujimujimuji.odo.ui.components.CosmeticGlyph
import com.mujimujimuji.odo.ui.components.FitCaption
import com.mujimujimuji.odo.ui.components.Gauge
import com.mujimujimuji.odo.ui.components.Hairline
import com.mujimujimuji.odo.ui.components.Key
import com.mujimujimuji.odo.ui.components.KeyStrip
import com.mujimujimuji.odo.ui.components.Medal
import com.mujimujimuji.odo.ui.components.OdoDurations
import com.mujimujimuji.odo.ui.components.OdoEasing
import com.mujimujimuji.odo.ui.components.OdoIcon
import com.mujimujimuji.odo.ui.components.OdoPanel
import com.mujimujimuji.odo.ui.components.medalTierOf
import com.mujimujimuji.odo.ui.components.rememberMotionScale
import com.mujimujimuji.odo.ui.components.scaledBy
import com.mujimujimuji.odo.ui.map.CorridorData
import com.mujimujimuji.odo.ui.map.TripFitScene
import com.mujimujimuji.odo.ui.text.josa
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoShape
import com.mujimujimuji.odo.ui.theme.OdoSize
import com.mujimujimuji.odo.ui.theme.OdoSpace
import com.mujimujimuji.odo.ui.theme.OdoType
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 트립 요약(정차 직후) — 설계서 §5.3, 3박자.
 * 0 ms 숫자 → 600 ms 레벨업 카드(0.96→1.0 + 알파 450 ms) → 1500 ms 업적 행(150 ms 페이드, 동시). 패널을 누르면 끝까지 건너뛴다.
 * 효과음은 박자마다 1번(XP·레벨업·업적). 애니메이터 배율 0이면 전부 즉시. 감점·"XP에서 뺐어요"류 문구 없음, 위험 건수는 중립 캡션으로만.
 */
@Composable
fun SummaryScreen(
    summary: TripSummary,
    later: List<AchievementDef>,
    loadout: Loadout,
    data: CorridorData?,
    path: List<LatLng>,
    totalXp: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalOdoColors.current
    val motion = rememberMotionScale()
    val earcons = rememberEarcons()
    val leveledUp = summary.levelAfter > summary.levelBefore
    val achievements = remember(summary, later) { (summary.achievements + later).distinctBy { it.id } }
    // 애니메이터 배율 0(설계서 §8 "위 모든 값 0", 카드 즉시 표시): 첫 프레임부터 끝 박자로 그린다.
    // 0에서 시작해 LaunchedEffect가 올리면 첫 프레임(부하 시 1 s 넘게 보임)에 오른쪽 열이 비었다.
    var beat by remember { mutableIntStateOf(if (motion == 0f) 2 else 0) }

    LaunchedEffect(summary) {
        earcons.play(Earcon.Xp)
        if (motion == 0f) {
            // 소리는 박자마다 1번 그대로(시간차만 0), 예전 delay(0) 경로와 같다.
            if (leveledUp) earcons.play(Earcon.LevelUp)
            if (achievements.isNotEmpty()) earcons.play(Earcon.Achievement)
            return@LaunchedEffect
        }
        delay((BEAT_CARD_MS * motion).toLong())
        if (beat < 1) {
            beat = 1
            if (leveledUp) earcons.play(Earcon.LevelUp)
        }
        delay(((BEAT_ROWS_MS - BEAT_CARD_MS) * motion).toLong())
        if (beat < 2) {
            beat = 2
            if (achievements.isNotEmpty()) earcons.play(Earcon.Achievement)
        }
    }

    Box(modifier.fillMaxSize().background(c.ground)) {
        TripFitScene(data, path, loadout, Modifier.fillMaxSize(), covered = DpSize(PANEL_W, PANEL_H))
        OdoPanel(
            Modifier
                .align(Alignment.Center)
                .size(PANEL_W, PANEL_H)
                .pointerInput(Unit) { detectTapGestures { if (beat < 2) beat = 2 } },
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = OdoSpace.l)) {
                Facts(summary, Modifier.width(LEFT_W).fillMaxHeight().padding(vertical = OdoSpace.l))
                Column(Modifier.width(RIGHT_W).fillMaxHeight()) {
                    Spacer(Modifier.height(OdoSpace.l))
                    if (leveledUp) LevelUpCard(summary, loadout.vehicle, visible = beat >= 1, motion) else NextLevel(summary.levelAfter, totalXp)
                    Spacer(Modifier.height(OdoSpace.l))
                    AchievementRows(achievements, visible = beat >= 2, motion, Modifier.fillMaxWidth().weight(1f))
                    Hairline()
                    KeyStrip(listOf(Key(stringResource(R.string.key_home), OdoIcon.Home, onClick = onClose)), Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Facts(summary: TripSummary, modifier: Modifier) {
    val c = LocalOdoColors.current
    val unit = stringResource(R.string.unit_km)
    val levelText = if (summary.levelAfter > summary.levelBefore) {
        stringResource(R.string.summary_level_change, summary.levelBefore, summary.levelAfter)
    } else {
        stringResource(R.string.summary_level_same, summary.levelAfter)
    }
    val riskNames = mapOf(
        RiskType.HARSH_ACCEL to stringResource(R.string.risk_harsh_accel),
        RiskType.HARSH_DECEL to stringResource(R.string.risk_harsh_decel),
        RiskType.SPEEDING to stringResource(R.string.risk_speeding),
        RiskType.NO_SEATBELT to stringResource(R.string.risk_no_seatbelt),
    )
    val events = RiskType.entries.mapNotNull { t ->
        summary.eventCounts[t]?.takeIf { it > 0 }?.let { "${riskNames[t]} $it" }
    }.joinToString(" · ")
    Column(modifier) {
        Text(stringResource(R.string.summary_title), Modifier.semantics { heading() }, style = OdoType.title, color = c.ink96)
        Spacer(Modifier.height(OdoSpace.l))
        FactNumber(stringResource(R.string.summary_safe), summary.safeKm.toString(), unit)
        Spacer(Modifier.height(OdoSpace.m))
        FactNumber(stringResource(R.string.summary_xp), stringResource(R.string.summary_xp_value, summary.xpEarned), null)
        Spacer(Modifier.height(OdoSpace.m))
        Text(stringResource(R.string.summary_level), style = OdoType.caption, color = c.inkDim)
        Text(levelText, style = OdoType.body, color = c.ink96)
        Spacer(Modifier.height(OdoSpace.m))
        if (summary.excludedSegments > 0) {
            Text(stringResource(R.string.summary_excluded, summary.excludedSegments), style = OdoType.caption, color = c.inkDim, maxLines = 1)
        }
        if (events.isNotEmpty()) {
            Text(events, style = OdoType.caption, color = c.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (summary.capReached) {
            // 1.3배 글꼴에서 "오늘 상한 300 XP를"에서 잘렸다 → 넘치면 짧은 꼴로. 줄을 늘리면 아래 '주행 n km'가 밀린다.
            FitCaption(
                stringResource(R.string.summary_cap_reached, josa("${Thresholds.DAILY_XP_CAP} XP", "을/를")),
                stringResource(R.string.summary_cap_reached_short),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.summary_distance, summary.distanceKm.roundToInt()), style = OdoType.caption, color = c.inkDim, maxLines = 1)
    }
}

@Composable
private fun FactNumber(label: String, value: String, unit: String?) {
    val c = LocalOdoColors.current
    Column {
        Text(label, style = OdoType.caption, color = c.inkDim)
        Row {
            Text(value, Modifier.alignByBaseline(), style = OdoType.numeralM, color = c.ink96, maxLines = 1)
            if (unit != null) {
                Spacer(Modifier.width(OdoSpace.s))
                Text(unit, Modifier.alignByBaseline(), style = OdoType.title, color = c.inkDim, maxLines = 1)
            }
        }
    }
}

/**
 * 레벨업 카드: 1dp `amber` 테두리 한 겹(카드 속 카드 아님). "Lv 5" + 칭호, 그 아래 열린 꾸미기를 사물로 한 줄
 * (색 = 페인트 점, 휠·램프·루프·번호판 = 차고 글리프 28dp, P8 "성장은 사물로 말한다") + 열린 수.
 * 글리프는 장식이라 줄 전체에 이름 목록을 의미 설명으로 붙인다. 루프 글리프는 지금 차종([vehicle]) 실루엣 위에 그린다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LevelUpCard(summary: TripSummary, vehicle: VehicleType, visible: Boolean, motion: Float) {
    val c = LocalOdoColors.current
    val t by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(OdoDurations.LEVEL_UP_MS.scaledBy(motion), easing = OdoEasing),
        label = "levelup",
    )
    val unlocks = remember(summary) {
        CosmeticCatalog.items.filter { it.id != CosmeticCatalog.NONE && it.unlockLevel in (summary.levelBefore + 1)..summary.levelAfter }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = t
                scaleX = CARD_SCALE_FROM + (1f - CARD_SCALE_FROM) * t
                scaleY = scaleX
            }
            .border(OdoShape.hairline, c.amber, OdoShape.chip)
            .padding(OdoSpace.l),
    ) {
        Row {
            Text(stringResource(R.string.level_short, summary.levelAfter), Modifier.alignByBaseline(), style = OdoType.numeralM, color = c.ink96)
            Spacer(Modifier.width(OdoSpace.m))
            Text(
                LevelTable.titleForLevel(summary.levelAfter),
                Modifier.alignByBaseline(),
                style = OdoType.title,
                color = c.ink96,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (unlocks.isNotEmpty()) {
            val names = unlocks.joinToString(", ") { it.label }
            Spacer(Modifier.height(OdoSpace.s))
            FlowRow(
                Modifier.fillMaxWidth().semantics { contentDescription = names },
                horizontalArrangement = Arrangement.spacedBy(OdoSpace.s),
                verticalArrangement = Arrangement.spacedBy(OdoSpace.s),
            ) {
                for (item in unlocks) CosmeticGlyph(item, vehicle, size = UNLOCK_GLYPH)
            }
            Spacer(Modifier.height(OdoSpace.xs))
        }
        when {
            unlocks.size > 1 -> Text(stringResource(R.string.summary_levelup_unlocks, unlocks.size), style = OdoType.body, color = c.inkDim, maxLines = 1)
            unlocks.size == 1 -> Text(
                stringResource(R.string.summary_levelup_unlock_one, josa(unlocks[0].label, "이/가")),
                style = OdoType.body,
                color = c.inkDim,
                maxLines = 1,
            )
        }
    }
}

/** 레벨이 그대로일 때: 트립 뒤 누적 XP(`totalXp`) 기준으로 다음 레벨까지 남은 XP와 4dp 게이지. */
@Composable
private fun NextLevel(level: Int, totalXp: Int) {
    val c = LocalOdoColors.current
    val span = LevelTable.progress(totalXp)
    Column(Modifier.fillMaxWidth()) {
        if (span != null) {
            val (start, next) = span
            val progress = if (next > start) ((totalXp - start).toFloat() / (next - start)).coerceIn(0f, 1f) else 1f
            Text(
                stringResource(R.string.summary_next_level, level + 1, (next - totalXp).coerceAtLeast(0)),
                style = OdoType.caption,
                color = c.inkDim,
                maxLines = 1,
            )
            Spacer(Modifier.height(OdoSpace.xs))
            Gauge(progress = progress, thickness = OdoSize.gaugeParked, modifier = Modifier.fillMaxWidth())
        } else {
            Text(stringResource(R.string.home_max_level), style = OdoType.caption, color = c.inkDim, maxLines = 1)
        }
    }
}

@Composable
private fun AchievementRows(items: List<AchievementDef>, visible: Boolean, motion: Float, modifier: Modifier) {
    val c = LocalOdoColors.current
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(OdoDurations.ROWS_MS.scaledBy(motion), easing = OdoEasing),
        label = "rows",
    )
    Column(modifier.graphicsLayer { this.alpha = alpha }.verticalScroll(rememberScrollState())) {
        items.forEachIndexed { i, def ->
            if (i > 0) Hairline()
            val desc = stringResource(R.string.medal_desc_open, def.title)
            Row(Modifier.fillMaxWidth().heightIn(min = ROW_H).padding(vertical = OdoSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                Medal(unlocked = true, tier = medalTierOf(def), contentDescription = desc)
                Spacer(Modifier.width(OdoSpace.m))
                Column(Modifier.weight(1f)) {
                    Text(def.title, style = OdoType.body, color = c.ink96, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(def.description, style = OdoType.caption, color = c.inkDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private val PANEL_W = 592.dp
private val PANEL_H = 484.dp
private val LEFT_W = 272.dp
private val RIGHT_W = 288.dp
private val ROW_H = 64.dp
private val UNLOCK_GLYPH = 28.dp
private const val BEAT_CARD_MS = 600f
private const val BEAT_ROWS_MS = 1_500f
private const val CARD_SCALE_FROM = 0.96f
