package com.mujimujimuji.odo.ui.components

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/** 이 앱의 유일한 이징(설계서 §8). 바운스 없음. */
val OdoEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** 정차 화면 전환 200 ms · 차고 150 ms · 레벨업 카드 450 ms · 업적 행 150 ms. NoticeLine은 연출 없이 바로 뜬다. */
object OdoDurations {
    const val SCREEN_MS = 200
    const val GARAGE_MS = 150
    const val LEVEL_UP_MS = 450
    const val ROWS_MS = 150
}

/**
 * 모션 배율. `Settings.Global.ANIMATOR_DURATION_SCALE == 0`이면 모든 연출을 0 ms로 만든다(§8·접근성).
 * 액티비티 수명 동안 한 번 읽는다.
 */
@Composable
fun rememberMotionScale(): Float {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f).coerceIn(0f, 1f)
    }
}

/** 배율을 적용한 지속 시간(ms). */
fun Int.scaledBy(scale: Float): Int = (this * scale).roundToInt()
