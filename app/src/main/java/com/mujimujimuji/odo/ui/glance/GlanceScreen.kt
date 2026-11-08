package com.mujimujimuji.odo.ui.glance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mujimujimuji.odo.drive.DriveUi
import com.mujimujimuji.odo.ui.LaneGauge
import com.mujimujimuji.odo.ui.SimBadge
import com.mujimujimuji.odo.ui.radar.RadarRing
import com.mujimujimuji.odo.ui.theme.Road

/**
 * 주행 중 화면(절대 규칙 2): 숫자 1개(오늘 안전 km), 게이지 1개(다음 업적까지), 레이더 링.
 * 애니메이션·팝업·부정 피드백 없음. 터치는 DriveLockOverlay가 막는다.
 */
@Composable
fun GlanceScreen(ui: DriveUi) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 28.dp)) {
        val radar = minOf(maxHeight * 0.62f, maxWidth * 0.36f)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text("오늘 안전 주행", color = Road.Muted, fontSize = 22.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${ui.todaySafeKm}",
                        color = Road.Lane,
                        fontSize = 132.sp,
                        lineHeight = 132.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("km", color = Road.Lane, fontSize = 36.sp, modifier = Modifier.padding(start = 10.dp, bottom = 22.dp))
                }
                Spacer(Modifier.height(24.dp))
                val goal = ui.goal
                LaneGauge(goal?.progress ?: 1f, Modifier.fillMaxWidth().height(20.dp), dashes = 8)
                Spacer(Modifier.height(14.dp))
                Text(
                    if (goal != null) "다음 업적  ${goal.achievement.title}" else "열 수 있는 업적을 모두 열었어요",
                    color = Road.Muted,
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(28.dp))
            RadarRing(Modifier.size(radar))
        }
        if (ui.simulated) SimBadge(Modifier.align(Alignment.TopEnd))
    }
}
