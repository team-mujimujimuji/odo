package com.mujimujimuji.odo.ui.theme

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp

/**
 * 글자 역할 7개(설계서 §1.2). 화면 코드는 `fontSize = N.sp`를 직접 쓰지 않고 이 역할만 쓴다.
 * 시스템 글꼴(Roboto + 기기 Noto CJK), 번들 글꼴 0. 숫자는 항상 `tnum`.
 */
object OdoType {
    private val base = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase),
    )

    /** 20/26 Medium — 정차 캡션(조건·힌트·출처). */
    val caption = base.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)

    /**
     * caption의 줄 높이만 24로 조인 변형(글자 크기 20 그대로, 역할을 늘리지 않는다). 좁은 칸에 줄을 쌓는 곳 —
     * KeyStrip 작은 라벨·캡션 줄, 차고 그림 칸 이름·"Lv N" — 이 같은 값을 쓴다. 한글 글리프 상자(≈글자 크기)에 위아래 2dp씩 남는다.
     */
    val captionTight = caption.copy(lineHeight = 24.sp)

    /** 24/32 Medium — 정차 본문·KeyStrip 라벨·칩 라벨. */
    val body = base.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)

    /** 28/36 Bold — 정차 화면 제목. */
    val title = base.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)

    /** 32/40 Medium — 주행 라벨·단위. */
    val label = base.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium)

    /** 36/44 Bold. */
    val headline = base.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)

    /** 48/52 Bold — 정차 큰 숫자(Lv, km, XP). */
    val numeralM = base.copy(fontSize = 48.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)

    /** 88/92 Bold — 주행 숫자 1개. */
    val numeralL = base.copy(fontSize = 88.sp, lineHeight = 92.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp)
}
