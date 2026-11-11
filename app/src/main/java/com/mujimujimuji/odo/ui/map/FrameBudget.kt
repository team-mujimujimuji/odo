package com.mujimujimuji.odo.ui.map

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import java.util.Arrays

/**
 * 프레임 예산 감시(설계서 §4.3). 실제로 그린 프레임마다 걸린 시간(창의 FrameMetrics `TOTAL_DURATION`:
 * 입력·애니메이션·측정·그리기에 RenderThread 동기화·명령 전송·버퍼 교환까지)을 2 s 창으로 보고, 중앙값이
 * 50 ms(<20 fps)를 넘으면 [tiltAllowed]를 끈다. 그 글랜스(트립) 동안 틸트 0으로 폴백하고 되돌리지 않는다.
 *
 * - 판단은 0.5 s마다 한 번, 창에 프레임이 8개 이상 있을 때만 한다. 다만 한 프레임이 250 ms를 넘을 만큼 느리면
 *   2 s에 8개가 안 차므로, 그린 시간 합이 창의 절반(1 s)을 넘으면 개수가 모자라도 판단한다. 이때는 하위 중앙값을 써서
 *   느린 한 프레임만으로는(창에 프레임이 2개뿐일 때 큰 쪽) 폴백하지 않는다.
 * - 앞 프레임이 끝나고 1 s 넘게 그린 프레임이 없으면(백그라운드·일시정지·지도가 멈춤) 창을 비우고 다시 2 s를 본다.
 *   1 s 넘게 걸린 프레임 자체는 멈춤이 아니라 느린 프레임으로 센다.
 */
@Stable
class FrameBudget(
    private val windowNanos: Long = 2_000_000_000L,
    private val slowMedianMs: Float = 50f,
) {
    var tiltAllowed: Boolean by mutableStateOf(true)
        private set

    private val times = LongArray(CAP)
    private val durs = LongArray(CAP)
    private val scratch = LongArray(CAP)
    private var head = 0
    private var count = 0
    private var lastEval = Long.MIN_VALUE
    private var lastEnd = Long.MIN_VALUE

    /** 그린 프레임 하나를 기록한다. [frameTimeNanos]: 프레임 시작(의도한 vsync), [durationNanos]: 그 프레임에 걸린 시간. */
    fun record(frameTimeNanos: Long, durationNanos: Long) {
        if (count > 0 && frameTimeNanos - lastEnd > PAUSE_NANOS) {
            count = 0
            head = 0
        }
        lastEnd = frameTimeNanos + durationNanos
        times[head] = frameTimeNanos
        durs[head] = durationNanos
        head = (head + 1) % CAP
        if (count < CAP) count++
        if (lastEval == Long.MIN_VALUE || frameTimeNanos - lastEval >= EVAL_EVERY_NANOS) {
            lastEval = frameTimeNanos
            evaluate(frameTimeNanos)
        }
    }

    private fun evaluate(now: Long) {
        if (count == 0) return
        val oldestIdx = (head - count + CAP) % CAP
        if (now - times[oldestIdx] < windowNanos) return // 아직 2 s를 못 봤다
        var n = 0
        var busy = 0L
        for (k in 0 until count) {
            val idx = (oldestIdx + k) % CAP
            if (now - times[idx] <= windowNanos) {
                scratch[n++] = durs[idx]
                busy += durs[idx]
            }
        }
        if (n < MIN_FRAMES && busy < windowNanos / 2) return
        Arrays.sort(scratch, 0, n)
        // 개수가 모자란 창은 하위 중앙값(짝수 개면 작은 쪽). 프레임이 드물 때 1 s 넘는 단발 프레임이 중앙값으로 뽑히지 않게 한다.
        val mid = if (n < MIN_FRAMES) (n - 1) / 2 else n / 2
        val medianMs = scratch[mid] / 1_000_000f
        if (medianMs > slowMedianMs) tiltAllowed = false
    }

    private companion object {
        const val CAP = 256
        const val MIN_FRAMES = 8
        const val PAUSE_NANOS = 1_000_000_000L
        const val EVAL_EVERY_NANOS = 500_000_000L
    }
}

/**
 * 컴포지션 동안 이 창의 FrameMetrics를 받는 [FrameBudget]. 글랜스가 떠 있는 동안만 살아 있다(트립마다 새로).
 * 창을 못 찾거나 리스너를 못 붙이면 측정 없이 틸트를 유지한다(앱은 계속 돈다).
 */
@Composable
fun rememberFrameBudget(): FrameBudget {
    val budget = remember { FrameBudget() }
    val view = LocalView.current
    DisposableEffect(budget, view) {
        val feed = view.context.findActivity()?.window?.let { FrameMetricsFeed(budget, it) }
        feed?.start()
        onDispose { feed?.stop() }
    }
    return budget
}

/**
 * 창의 FrameMetrics를 [FrameBudget]에 넣는다. FrameMetrics는 실제로 그린 프레임에만 오므로 화면이 그대로인 동안은
 * 아무것도 세지 않는다. 창이 새로 배치된 뒤 첫 프레임(`FIRST_DRAW_FRAME`)은 뺀다. 메인 스레드에서 받는다(콜백당 상수 시간,
 * 0.5 s마다 256개 이하 정렬 1회).
 */
private class FrameMetricsFeed(
    private val budget: FrameBudget,
    private val window: Window,
) : Window.OnFrameMetricsAvailableListener {
    private var added = false

    fun start() {
        added = try {
            window.addOnFrameMetricsAvailableListener(this, Handler(Looper.getMainLooper()))
            true
        } catch (e: IllegalStateException) {
            false // 창에 붙은 뷰가 없다
        }
    }

    fun stop() {
        if (!added) return
        added = false
        try {
            window.removeOnFrameMetricsAvailableListener(this)
        } catch (e: IllegalArgumentException) {
            // 창이 이미 리스너를 놓았다
        }
    }

    override fun onFrameMetricsAvailable(window: Window, frameMetrics: FrameMetrics, dropCountSinceLastInvocation: Int) {
        if (frameMetrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return
        budget.record(
            frameMetrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
            frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
