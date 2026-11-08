package com.mujimujimuji.odo.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mujimujimuji.odo.engine.AchievementDef
import com.mujimujimuji.odo.ui.theme.Road

/** 업적 목록. 연 것은 밝게, 아직인 것은 여는 방법만 흐리게. */
@Composable
fun AchievementsScreen(catalog: List<AchievementDef>, unlocked: Set<String>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("업적", color = Road.Lane, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(
                "${catalog.count { it.id in unlocked }}/${catalog.size}",
                color = Road.CenterLine,
                fontSize = 26.sp,
                modifier = Modifier.padding(start = 16.dp).weight(1f),
            )
            TextButton(onClick = onBack) { Text("홈으로", fontSize = 20.sp, color = Road.Lane) }
        }
        Spacer(Modifier.height(16.dp))
        val sorted = catalog.sortedBy { it.id !in unlocked }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 260.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(sorted, key = { it.id }) { a ->
                val open = a.id in unlocked
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .then(
                            if (open) Modifier.background(Road.Shoulder, RoundedCornerShape(12.dp))
                            else Modifier.border(1.dp, Road.Seam, RoundedCornerShape(12.dp)),
                        )
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Text(a.title, color = if (open) Road.Lane else Road.Muted, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    Text(a.description, color = Road.Muted, fontSize = 16.sp, maxLines = 2)
                }
            }
        }
    }
}
