package com.mujimujimuji.odo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.time.LocalTime

/** 18:30~06:30이면 야간(설계서 §1.6). */
fun isNightAt(time: LocalTime): Boolean = time >= NIGHT_START || time < NIGHT_END

private val NIGHT_START: LocalTime = LocalTime.of(18, 30)
private val NIGHT_END: LocalTime = LocalTime.of(6, 30)

/**
 * 주야 판정. 1분마다 기기 시각으로 다시 평가하되 [locked]인 동안은 값을 바꾸지 않는다(주행 중 전환 금지).
 * [forced]는 fake 빌드의 캡처용 강제 전환(홈 데모 칩 길게 누르기). null이면 시각을 따른다.
 */
@Composable
fun rememberNightMode(locked: Boolean, forced: Boolean? = null): Boolean {
    var clockNight by remember { mutableStateOf(isNightAt(LocalTime.now())) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            clockNight = isNightAt(LocalTime.now())
        }
    }
    val live = forced ?: clockNight
    // 주행 중 액티비티가 다시 만들어져도(글꼴 배율·화면 설정 변경) 정차 때 정한 값을 잃지 않게 저장한다.
    val frozen = rememberSaveable { mutableStateOf(live) }
    SideEffect { if (!locked) frozen.value = live }
    return if (locked) frozen.value else live
}
