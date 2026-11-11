package com.mujimujimuji.odo.ui.summary

import com.mujimujimuji.odo.engine.RiskType

/**
 * 요약 왼쪽 열 아래 캡션 한 자리(오늘 상한 · 연속 기록 보너스). 둘 다 있으면 한 줄로 합친다:
 * 캡션 줄을 늘리면 글꼴 1.3배에서 아래 '주행 n km'가 밀린다.
 */
internal sealed interface XpNote {
    /** 오늘 상한만 채웠다. */
    data object Cap : XpNote

    /** 이번 트립 XP에 연속 기록 보너스 [xp]가 들어 있다(상한 밖). */
    data class Bonus(val xp: Int) : XpNote

    /** 상한을 채웠고 연속 기록 보너스 [xp]도 받았다. 트립 XP가 상한보다 큰 까닭을 같이 적는다. */
    data class CapAndBonus(val xp: Int) : XpNote
}

internal fun xpNoteOf(capReached: Boolean, streakBonusXp: Int): XpNote? = when {
    streakBonusXp > 0 && capReached -> XpNote.CapAndBonus(streakBonusXp)
    streakBonusXp > 0 -> XpNote.Bonus(streakBonusXp)
    capReached -> XpNote.Cap
    else -> null
}

/**
 * 위험 이벤트 줄의 최대 줄 수. 아래 캡션([XpNote])이 있으면 1줄, 없으면 2줄 —
 * 글꼴 1.3배에서 검증된 캡션 3줄(제외 · 이벤트 · 상한)을 넘지 않게 한다.
 */
internal fun riskLineMaxLines(note: XpNote?): Int = if (note != null) 1 else 2

/**
 * 위험 이벤트 줄: 종류별 "이름 n"을 [RiskType] 순서로 " · "로 잇는다(0회 · 이름 없는 종류는 뺀다).
 * 두 줄로 꺾일 때 " · " 뒤에서만 바뀌게 항목 안 공백과 구분자 앞 공백을 NBSP로 묶는다(차고 사양 줄과 같은 방식).
 */
internal fun riskLine(counts: Map<RiskType, Int>, names: Map<RiskType, String>): String =
    RiskType.entries.mapNotNull { t ->
        val n = counts[t]?.takeIf { it > 0 } ?: return@mapNotNull null
        val name = names[t] ?: return@mapNotNull null
        "$name $n".replace(' ', NBSP)
    }.joinToString(RISK_SEPARATOR)

private const val NBSP = '\u00A0'
private const val RISK_SEPARATOR = "\u00A0· "
