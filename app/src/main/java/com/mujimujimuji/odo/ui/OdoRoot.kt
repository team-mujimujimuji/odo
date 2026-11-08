package com.mujimujimuji.odo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mujimujimuji.odo.OdoApp
import com.mujimujimuji.odo.engine.AchievementDef
import com.mujimujimuji.odo.engine.LevelTable
import com.mujimujimuji.odo.engine.StreakRules
import com.mujimujimuji.odo.ui.glance.GlanceScreen
import com.mujimujimuji.odo.ui.home.HomeScreen
import com.mujimujimuji.odo.ui.home.HomeStats
import com.mujimujimuji.odo.ui.profile.AchievementsScreen
import com.mujimujimuji.odo.ui.profile.CustomizeScreen
import com.mujimujimuji.odo.ui.profile.MinicarPalette
import com.mujimujimuji.odo.ui.summary.SummaryScreen
import com.mujimujimuji.odo.ui.theme.Road
import kotlinx.coroutines.launch
import java.time.LocalDate

private enum class Screen { HOME, ACHIEVEMENTS, CUSTOMIZE }

/**
 * 화면 고르기: 잠금(주행) → 글랜스, 트립 요약 있음 → 요약, 그 밖에는 정차 화면들.
 * 잠금 중에는 DriveLockOverlay가 맨 위에서 모든 입력을 막는다.
 */
@Composable
fun OdoRoot(app: OdoApp) {
    val ui by app.controller.ui.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val weekly by remember(today) { app.repo.weeklySafeKm(today) }.collectAsStateWithLifecycle(0)
    val unlocked by app.repo.unlockedFlow.collectAsStateWithLifecycle(emptySet())
    val colorId by remember { app.repo.cosmetic(MinicarPalette.SLOT) }.collectAsStateWithLifecycle(null)
    var screen by remember { mutableStateOf(Screen.HOME) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(Road.Asphalt)) {
        val summary = ui.summary
        when {
            !ui.ready -> Unit
            ui.locked -> GlanceScreen(ui)
            summary != null -> SummaryScreen(summary, later = ui.notices) {
                app.controller.closeSummary()
                app.controller.dismissNotices()
            }
            screen == Screen.ACHIEVEMENTS -> AchievementsScreen(app.catalog, unlocked) { screen = Screen.HOME }
            screen == Screen.CUSTOMIZE -> CustomizeScreen(
                level = LevelTable.levelForXp(ui.profile.totalXp),
                selectedId = colorId,
                onSelect = { id -> scope.launch { app.repo.setCosmetic(MinicarPalette.SLOT, id) } },
                onBack = { screen = Screen.HOME },
            )
            else -> HomeScreen(
                ui = ui,
                stats = HomeStats(
                    weeklySafeKm = weekly,
                    unlockedCount = unlocked.size,
                    achievementTotal = app.catalog.size,
                    streakDays = StreakRules.currentDays(ui.profile.streak, today),
                    carColor = MinicarPalette.byId(colorId).color,
                ),
                onAchievements = { screen = Screen.ACHIEVEMENTS },
                onCustomize = { screen = Screen.CUSTOMIZE },
                onStartDemo = { screen = Screen.HOME; app.controller.startDemo() },
                onResetDemo = app.controller::resetDemo,
            )
        }
        // 정차 중에 연 업적 알림. 주행 중에는 띄우지 않는다(절대 규칙 2). 트립 요약에서는 요약 안에 보인다.
        if (!ui.locked && summary == null && ui.notices.isNotEmpty()) {
            NoticeBanner(ui.notices, app.controller::dismissNotices, Modifier.align(Alignment.TopCenter))
        }
        if (ui.locked) DriveLockOverlay()
    }
}

@Composable
private fun NoticeBanner(items: List<AchievementDef>, onDismiss: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .padding(top = 20.dp)
            .widthIn(min = 420.dp)
            .background(Road.CenterLine, RoundedCornerShape(14.dp))
            .padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = false).padding(end = 16.dp)) {
            Text("업적을 열었어요", color = Road.OnCenterLine, fontSize = 18.sp)
            Text(items.joinToString(", ") { it.title }, color = Road.OnCenterLine, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onDismiss) { Text("확인", color = Road.OnCenterLine, fontSize = 20.sp) }
    }
}
