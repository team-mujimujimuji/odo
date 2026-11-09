package com.mujimujimuji.odo.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mujimujimuji.odo.R
import com.mujimujimuji.odo.engine.AchievementDef
import com.mujimujimuji.odo.engine.AchievementRule
import com.mujimujimuji.odo.engine.AchievementStats
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.ui.components.DISABLED_ALPHA
import com.mujimujimuji.odo.ui.components.Hairline
import com.mujimujimuji.odo.ui.components.Medal
import com.mujimujimuji.odo.ui.components.OdoPanel
import com.mujimujimuji.odo.ui.components.PanelHeader
import com.mujimujimuji.odo.ui.components.VerticalHairline
import com.mujimujimuji.odo.ui.components.medalTierOf
import com.mujimujimuji.odo.ui.map.CorridorData
import com.mujimujimuji.odo.ui.map.ScrimmedScene
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoSpace
import com.mujimujimuji.odo.ui.theme.OdoType

/**
 * 업적 진열장(설계서 §5.4): 2열 × 6행, 카탈로그 순서(왼쪽 열 1–6, 오른쪽 열 7–12), 12개 모두 보인다("?" 숨김 없음).
 * 행 = 메달 36 + 제목(body, 잠김은 ink 56 %) + 한 줄(caption). 딴 업적은 한 일(과거형 "300 km를 쌓았어요"), 잠긴 업적은
 * 조건과 진행도("와이퍼 켜고 3 / 10 km", "107 / 300 km")를 쓴다. 문구는 셀 폭(글꼴 1.0에서 약 220dp)에 1줄로 들어가게 짧게 썼다.
 * 애니메이션 없음. 전역 순위·신원 없음.
 */
@Composable
fun AchievementsScreen(
    catalog: List<AchievementDef>,
    unlocked: Set<String>,
    stats: AchievementStats?,
    data: CorridorData?,
    center: LatLng?,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalOdoColors.current
    Box(modifier.fillMaxSize().background(c.ground)) {
        ScrimmedScene(data, center, Modifier.fillMaxSize())
        OdoPanel(Modifier.align(Alignment.Center).size(PANEL_W, PANEL_H)) {
            Column(Modifier.fillMaxSize()) {
                PanelHeader(
                    title = stringResource(R.string.achievements_title),
                    caption = stringResource(R.string.count_of_total, unlocked.size, catalog.size),
                    onHome = onHome,
                )
                Hairline()
                val rows = (catalog.size + 1) / 2
                Column(Modifier.fillMaxWidth().fillMaxHeight()) {
                    for (r in 0 until rows) {
                        if (r > 0) Hairline()
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            AchievementCell(catalog[r], catalog[r].id in unlocked, stats, Modifier.weight(1f).fillMaxHeight())
                            VerticalHairline()
                            val right = catalog.getOrNull(r + rows)
                            if (right != null) {
                                AchievementCell(right, right.id in unlocked, stats, Modifier.weight(1f).fillMaxHeight())
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AchievementCell(def: AchievementDef, open: Boolean, stats: AchievementStats?, modifier: Modifier) {
    val c = LocalOdoColors.current
    val medalDesc = stringResource(if (open) R.string.medal_desc_open else R.string.medal_desc_locked, def.title)
    val line = if (open) def.description else lockedLine(def, stats)
    Row(
        modifier.padding(horizontal = OdoSpace.m).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Medal(unlocked = open, tier = medalTierOf(def), contentDescription = medalDesc)
        Spacer(Modifier.width(OdoSpace.m))
        Column(Modifier.weight(1f)) {
            Text(
                def.title,
                style = OdoType.body,
                color = if (open) c.ink96 else c.ink.copy(alpha = DISABLED_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(line, style = OdoType.caption, color = c.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 잠긴 업적의 한 줄: 조건 문구 + 누적 진행도(거리·연속일·레벨·횟수 규칙만). 둘 다 없으면 설명을 쓴다. */
@Composable
private fun lockedLine(def: AchievementDef, stats: AchievementStats?): String {
    val progress = stats?.let { s ->
        when (val rule = def.rule) {
            is AchievementRule.SafeDistance -> stringResource(R.string.achievement_progress_km, s.totalSafeKm.coerceAtMost(rule.km), rule.km)
            is AchievementRule.NightSafeDistance -> stringResource(R.string.achievement_progress_km, s.nightSafeKm.coerceAtMost(rule.km), rule.km)
            is AchievementRule.RainSafeDistance -> stringResource(R.string.achievement_progress_km, s.rainSafeKm.coerceAtMost(rule.km), rule.km)
            is AchievementRule.StreakDays -> stringResource(R.string.achievement_progress_days, s.streakDays.coerceAtMost(rule.days), rule.days)
            is AchievementRule.Level -> stringResource(R.string.achievement_progress_level, s.level.coerceAtMost(rule.level), rule.level)
            is AchievementRule.TripCount ->
                if (rule.count > 1) stringResource(R.string.achievement_progress_count, s.tripCount.coerceAtMost(rule.count), rule.count) else null
            else -> null
        }
    }
    return listOfNotNull(def.condition.ifEmpty { null }, progress).joinToString(" ").ifEmpty { def.description }
}

private val PANEL_W = 592.dp
private val PANEL_H = 484.dp
