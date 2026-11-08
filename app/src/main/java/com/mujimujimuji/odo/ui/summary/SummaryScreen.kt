package com.mujimujimuji.odo.ui.summary

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mujimujimuji.odo.engine.AchievementDef
import com.mujimujimuji.odo.engine.LevelTable
import com.mujimujimuji.odo.engine.RiskType
import com.mujimujimuji.odo.engine.Thresholds
import com.mujimujimuji.odo.engine.TripSummary
import com.mujimujimuji.odo.ui.theme.Road

/** 정차 직후 트립 요약. 위험 이벤트는 여기서만, 담담한 말로 보여 준다(절대 규칙 3). */
@Composable
fun SummaryScreen(summary: TripSummary, later: List<AchievementDef>, onClose: () -> Unit) {
    Row(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 28.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("트립을 마쳤어요", color = Road.Muted, fontSize = 24.sp)
                Spacer(Modifier.height(8.dp))
                Row {
                    Text("${summary.safeKm}", color = Road.Lane, fontSize = 96.sp, lineHeight = 96.sp, fontWeight = FontWeight.Bold)
                    Text("km", color = Road.Lane, fontSize = 36.sp, modifier = Modifier.padding(start = 10.dp, top = 52.dp))
                }
                Text("안전 주행", color = Road.Muted, fontSize = 22.sp)
                Spacer(Modifier.height(24.dp))
                Text("+${summary.xpEarned} XP", color = Road.CenterLine, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
                if (summary.capReached) {
                    Text("오늘 받을 수 있는 ${Thresholds.DAILY_XP_CAP} XP를 모두 받았어요", color = Road.Muted, fontSize = 18.sp)
                }
            }
            Column {
                excludedLine(summary)?.let { Text(it, color = Road.Muted, fontSize = 18.sp) }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onClose,
                    modifier = Modifier.height(56.dp).width(200.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Road.CenterLine, contentColor = Road.OnCenterLine),
                ) { Text("홈으로", fontSize = 20.sp) }
            }
        }
        Spacer(Modifier.width(28.dp))
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (summary.levelAfter > summary.levelBefore) LevelUpCard(summary.levelAfter)
            if (summary.achievements.isNotEmpty()) {
                Text("이번 트립에서 연 업적", color = Road.Muted, fontSize = 20.sp)
                summary.achievements.forEach { AchievementRow(it.title, it.description) }
            }
            if (later.isNotEmpty()) {
                Text("정차해 있는 동안 연 업적", color = Road.Muted, fontSize = 20.sp)
                later.forEach { AchievementRow(it.title, it.description) }
            }
        }
    }
}

/** 레벨업 연출. 정차 화면에서 한 번만 커지며 나타난다. */
@Composable
private fun LevelUpCard(level: Int) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(level) { t.animateTo(1f, tween(durationMillis = 700)) }
    Column(
        Modifier
            .fillMaxWidth()
            .scale(0.85f + 0.15f * t.value)
            .alpha(t.value)
            .background(Road.CenterLine, RoundedCornerShape(16.dp))
            .padding(24.dp),
    ) {
        Text("레벨 $level", color = Road.OnCenterLine, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(LevelTable.titleForLevel(level), color = Road.OnCenterLine, fontSize = 26.sp)
        LevelTable.anchorAt(level)?.let {
            Spacer(Modifier.height(10.dp))
            Text("${it.unlock}${subjectParticle(it.unlock)} 열렸어요", color = Road.OnCenterLine, fontSize = 20.sp)
        }
    }
}

@Composable
fun AchievementRow(title: String, description: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Road.Shoulder, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(title, color = Road.Lane, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(description, color = Road.Muted, fontSize = 18.sp)
    }
}

private val riskNames = mapOf(
    RiskType.HARSH_ACCEL to "급가속",
    RiskType.HARSH_DECEL to "급감속",
    RiskType.SPEEDING to "과속",
    RiskType.NO_SEATBELT to "안전벨트 미착용",
)

/** "1 km 구간 1개는 급감속 1회가 있어 XP에서 뺐어요." 이벤트가 없으면 null. */
private fun excludedLine(s: TripSummary): String? {
    if (s.excludedSegments == 0) return null
    val reasons = s.eventCounts.entries.joinToString(", ") { (type, n) -> "${riskNames.getValue(type)} ${n}회" }
    return "1 km 구간 ${s.excludedSegments}개는 $reasons 때문에 XP에서 뺐어요"
}

/** 받침이 있으면 "이", 없으면 "가". */
private fun subjectParticle(word: String): String {
    val c = word.trimEnd(')', ' ').lastOrNull() ?: return "이"
    if (c !in '가'..'힣') return "이"
    return if ((c - '가') % 28 != 0) "이" else "가"
}
