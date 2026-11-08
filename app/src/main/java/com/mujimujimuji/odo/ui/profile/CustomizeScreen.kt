package com.mujimujimuji.odo.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mujimujimuji.odo.engine.LevelTable
import com.mujimujimuji.odo.ui.Minicar
import com.mujimujimuji.odo.ui.theme.Road

/** 미니카 색 바꾸기. 레벨 5 전에는 5색이 잠겨 있다. */
@Composable
fun CustomizeScreen(level: Int, selectedId: String?, onSelect: (String) -> Unit, onBack: () -> Unit) {
    val selected = MinicarPalette.byId(selectedId)
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("미니카 색", color = Road.Lane, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(selected.name, color = Road.CenterLine, fontSize = 24.sp, modifier = Modifier.padding(start = 14.dp).weight(1f))
            TextButton(onClick = onBack) { Text("홈으로", fontSize = 20.sp, color = Road.Lane) }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Minicar(selected.color, Modifier.width(280.dp).height(140.dp))
            }
            Spacer(Modifier.width(20.dp))
            Column {
                MinicarPalette.all.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                        row.forEach { c ->
                            val open = level >= c.unlockLevel
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
                                Box(
                                    Modifier
                                        .size(72.dp)
                                        .border(4.dp, if (c.id == selected.id) Road.CenterLine else Road.Asphalt, CircleShape)
                                        .padding(7.dp)
                                        .alpha(if (open) 1f else 0.3f)
                                        .background(c.color, CircleShape)
                                        .clickable(enabled = open, role = Role.RadioButton) { onSelect(c.id) },
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (open) c.name else "Lv ${c.unlockLevel}에 열려요",
                                    color = if (open) Road.Lane else Road.Muted,
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (level < LevelTable.colorUnlockLevel) {
            Text("안전 주행으로 레벨 ${LevelTable.colorUnlockLevel}이 되면 5색이 열려요", color = Road.Muted, fontSize = 18.sp)
        }
    }
}
