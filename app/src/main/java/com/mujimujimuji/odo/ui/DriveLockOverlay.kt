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
 * 주행 중 터치 0(절대 규칙 1). 화면 맨 위에 덮어 모든 터치·뒤로 가기를 삼킨다.
 * 끄는 설정은 없다. 잠금 여부는 DriveController가 기어·속도·시스템 운전 제한으로 정한다.
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
