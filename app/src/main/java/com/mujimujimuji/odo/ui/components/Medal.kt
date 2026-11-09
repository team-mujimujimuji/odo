package com.mujimujimuji.odo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mujimujimuji.odo.engine.AchievementDef
import com.mujimujimuji.odo.engine.AchievementRule
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoSize

/**
 * 업적 메달(설계서 §5.0) 36dp: 위에 V자 리본 두 가닥, 그 아래 원판(지름 22dp). 리본이 있어 입력 컨트롤(라디오 버튼)과 달리
 * 모은 물건으로 읽힌다. 열림 = `amber` 원판 + `onAmber` 1dp 안쪽 선 + `inkDim` 4dp 리본,
 * 잠김 = `surface` 원판(리본 끝을 가림)에 1dp `inkDim` 선, 리본도 1dp. [tier] 1·2·3이면 밑에 4dp 점 1–3개(거리 업적 10/100/1000 km).
 * 색만으로 상태를 전하지 않도록 채움/선 모양이 다르다. 좌표는 36 단위 격자.
 */
@Composable
fun Medal(
    unlocked: Boolean,
    modifier: Modifier = Modifier,
    tier: Int = 0,
    contentDescription: String? = null,
) {
    val c = LocalOdoColors.current
    val semantics = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Canvas(modifier.size(OdoSize.medal).then(semantics)) {
        val u = size.minDimension / 36f
        val hair = 1.dp.toPx()
        val center = Offset(18f * u, 20f * u)
        val r = 11f * u
        val ribbon = if (unlocked) 4.dp.toPx() else hair
        for ((top, bottom) in RIBBON) {
            drawLine(c.inkDim, Offset(top * u, 0f + ribbon / 2f), Offset(bottom * u, 11f * u), strokeWidth = ribbon, cap = StrokeCap.Butt)
        }
        if (unlocked) {
            drawCircle(c.amber, radius = r, center = center)
            drawCircle(c.onAmber, radius = r - 3.dp.toPx(), center = center, style = Stroke(hair))
        } else {
            drawCircle(c.surface, radius = r, center = center)
            drawCircle(c.inkDim, radius = r - hair / 2f, center = center, style = Stroke(hair))
        }
        if (tier > 0) {
            val dots = tier.coerceAtMost(3)
            val step = 8.dp.toPx()
            val y = size.height - 2.dp.toPx()
            val x0 = size.width / 2f - (dots - 1) * step / 2f
            for (i in 0 until dots) {
                drawCircle(if (unlocked) c.amber else c.inkDim, radius = 2.dp.toPx(), center = Offset(x0 + i * step, y))
            }
        }
    }
}

// 리본 두 가닥: (위 x, 아래 x), 36 단위. 원판 위쪽(y 9) 뒤에서 만난다.
private val RIBBON = listOf(12f to 17f, 24f to 19f)

/** 거리 업적의 tier: 10 km → 1, 100·300 km → 2, 1000 km 이상 → 3. 그 밖의 업적은 0. */
fun medalTierOf(def: AchievementDef): Int = when (val rule = def.rule) {
    is AchievementRule.SafeDistance -> distanceTier(rule.km)
    is AchievementRule.NightSafeDistance -> distanceTier(rule.km)
    is AchievementRule.RainSafeDistance -> distanceTier(rule.km)
    else -> 0
}

private fun distanceTier(km: Int): Int = when {
    km >= 1000 -> 3
    km >= 100 -> 2
    else -> 1
}
