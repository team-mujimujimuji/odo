package com.mujimujimuji.odo.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mujimujimuji.odo.ui.theme.Road

/** 시나리오 재생 신호로 움직이는 중임을 밝힌다(시연 영상의 정직성). */
@Composable
fun SimBadge(modifier: Modifier = Modifier) {
    Text(
        "시나리오 재생",
        color = Road.Muted,
        fontSize = 16.sp,
        modifier = modifier
            .border(1.dp, Road.Seam, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
