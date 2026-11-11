package com.mujimujimuji.odo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * 주행 중 터치 0(README '안전·프라이버시 원칙'). 화면 맨 위에 덮어 모든 터치·뒤로 가기를 삼킨다.
 * 끄는 설정은 없다. 잠금 여부는 [com.mujimujimuji.odo.drive.DriveLock]이 정한다: 기어 D/R 또는 속도 > 0,
 * 트립 중 기어 N 정차(기어 P에서만 풀린다), 시스템 운전 제한. DriveController가 샘플마다 다시 판정한다.
 */
@Composable
fun DriveLockOverlay() {
    BackHandler(enabled = true) {}
    Box(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    )
}
