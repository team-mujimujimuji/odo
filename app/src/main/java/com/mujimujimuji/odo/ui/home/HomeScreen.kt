package com.mujimujimuji.odo.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mujimujimuji.odo.drive.DriveUi
import com.mujimujimuji.odo.engine.LevelTable
import com.mujimujimuji.odo.ui.Minicar
import com.mujimujimuji.odo.ui.SimBadge
import com.mujimujimuji.odo.ui.theme.Road

data class HomeStats(
    val weeklySafeKm: Int,
    val unlockedCount: Int,
    val achievementTotal: Int,
    val streakDays: Int,
    val carColor: Color,
)

@Composable
fun HomeScreen(
    ui: DriveUi,
    stats: HomeStats,
    onAchievements: () -> Unit,
    onCustomize: () -> Unit,
    onStartDemo: () -> Unit,
    onResetDemo: () -> Unit,
) {
    val xp = ui.profile.totalXp
    val level = LevelTable.levelForXp(xp)
    // Pleos 런처는 앱을 약 656×548 dp 창에 띄운다. 그 크기를 기준으로 짠다.
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Lv $level", color = Road.Lane, fontSize = 52.sp, fontWeight = FontWeight.Bold, lineHeight = 52.sp)
            Text(
                LevelTable.titleForLevel(level),
                color = Road.CenterLine,
                fontSize = 26.sp,
                modifier = Modifier.padding(start = 14.dp, bottom = 6.dp).weight(1f),
            )
            if (ui.simulated) SimBadge(Modifier.padding(bottom = 10.dp))
        }
        Spacer(Modifier.height(12.dp))
        val range = LevelTable.progress(xp)
        if (range != null) {
            val (from, to) = range
            XpBar((xp - from).toFloat() / (to - from), Modifier.fillMaxWidth().height(10.dp))
            Spacer(Modifier.height(6.dp))
            Text("다음 레벨까지 ${to - xp} XP", color = Road.Muted, fontSize = 18.sp)
        } else {
            Text("최고 레벨이에요", color = Road.Muted, fontSize = 18.sp)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                RoadStrip(Modifier.fillMaxWidth().height(48.dp).align(Alignment.BottomCenter))
                Minicar(stats.carColor, Modifier.padding(bottom = 18.dp).width(300.dp).height(150.dp))
            }
            Spacer(Modifier.width(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Stat("${stats.weeklySafeKm} km", "이번 주 안전 주행")
                Stat("${stats.unlockedCount}/${stats.achievementTotal}", "업적")
                Stat("${stats.streakDays}일", "연속 기록")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.simulated) {
                Button(
                    onClick = onStartDemo,
                    enabled = !ui.demoPlaying,
                    modifier = Modifier.height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Road.CenterLine, contentColor = Road.OnCenterLine),
                ) {
                    Text(if (ui.demoPlaying) "재생 중" else "데모 주행 시작", fontSize = 19.sp, maxLines = 1)
                }
            }
            OutlinedButton(onClick = onAchievements, modifier = Modifier.height(56.dp)) {
                Text("업적", fontSize = 19.sp, color = Road.Lane, maxLines = 1)
            }
            OutlinedButton(onClick = onCustomize, modifier = Modifier.height(56.dp)) {
                Text("꾸미기", fontSize = 19.sp, color = Road.Lane, maxLines = 1)
            }
            if (ui.simulated) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onResetDemo) { Text("초기화", fontSize = 17.sp, color = Road.Muted, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, color = Road.Lane, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
        Text(label, color = Road.Muted, fontSize = 16.sp, maxLines = 1)
    }
}

@Composable
fun XpBar(progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Road.Seam, cornerRadius = r)
        drawRoundRect(Road.CenterLine, size = Size(size.width * progress.coerceIn(0f, 1f), size.height), cornerRadius = r)
    }
}

/** 미니카 아래 도로: 아스팔트 띠와 노란 중앙선 점선. */
@Composable
private fun RoadStrip(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Road.Shoulder)
        drawRect(Road.Seam, Offset(0f, 0f), Size(size.width, 3f))
        val dash = 44f
        var x = 12f
        val y = size.height * 0.55f
        while (x < size.width) {
            drawRoundRect(Road.CenterLine, Offset(x, y), Size(dash, 7f), CornerRadius(3f))
            x += dash * 2
        }
    }
}
