package com.mujimujimuji.odo.engine

import java.time.LocalDate

/**
 * 안전 XP 계산(기획서 안전 XP 표). XP는 깎지 않는다: 위험 구간은 0 XP일 뿐 감점이 없다.
 */
object XpCalculator {

    /** 보너스 배수의 곱. 방향지시등 사용률은 신호가 없으면 null(1차는 항상 null). */
    fun multiplier(segment: Segment, turnSignalRate: Double? = null): Double {
        var m = 1.0
        if (segment.night) m *= Thresholds.NIGHT_MULTIPLIER
        if (segment.wiper) m *= Thresholds.WIPER_MULTIPLIER
        if (segment.allBelts) m *= Thresholds.ALL_BELTS_MULTIPLIER
        if (turnSignalRate != null && turnSignalRate >= Thresholds.TURN_SIGNAL_RATE_MIN) {
            m *= Thresholds.TURN_SIGNAL_MULTIPLIER
        }
        return m
    }

    /** 상한 적용 전 구간 XP. */
    fun segmentXp(segment: Segment, turnSignalRate: Double? = null): Int {
        if (!segment.safe) return 0
        return Math.round(Thresholds.SAFE_SEGMENT_XP * multiplier(segment, turnSignalRate)).toInt()
    }

    /** 오늘 이미 [earnedToday]만큼 받았을 때 [xp] 중 실제로 줄 수 있는 양. */
    fun applyDailyCap(earnedToday: Int, xp: Int): Int =
        xp.coerceAtMost((Thresholds.DAILY_XP_CAP - earnedToday).coerceAtLeast(0))
}

data class Streak(val days: Int = 0, val lastDate: LocalDate? = null)

/**
 * 연속 기록: 하루 1 km 이상 안전 주행한 날이 이어지면 유지한다.
 * 7일째마다 +50, 30일째마다 +300. 연속 보너스는 일일 상한 밖이다.
 */
object StreakRules {
    data class Result(val streak: Streak, val bonusXp: Int)

    /** [today]가 연속 기록을 채운 날이 됐을 때 부른다. 같은 날 두 번 불러도 한 번만 센다. */
    fun onQualifiedDay(streak: Streak, today: LocalDate): Result {
        if (streak.lastDate == today) return Result(streak, 0)
        val days = if (streak.lastDate == today.minusDays(1)) streak.days + 1 else 1
        var bonus = 0
        if (days % Thresholds.STREAK_7_DAYS == 0) bonus += Thresholds.STREAK_7_BONUS_XP
        if (days % Thresholds.STREAK_30_DAYS == 0) bonus += Thresholds.STREAK_30_BONUS_XP
        return Result(Streak(days, today), bonus)
    }

    /** 화면에 보일 현재 연속 일수. 어제나 오늘 기록이 없으면 0. */
    fun currentDays(streak: Streak, today: LocalDate): Int {
        val last = streak.lastDate ?: return 0
        return if (last == today || last == today.minusDays(1)) streak.days else 0
    }
}
