package com.mujimujimuji.odo.sim

import com.mujimujimuji.odo.engine.LevelTable
import com.mujimujimuji.odo.engine.ProfileState
import com.mujimujimuji.odo.engine.Streak
import java.time.LocalDate
import java.time.ZoneId

/**
 * fake 빌드의 데모 시작값(README '시나리오 재생 방법': 레벨 4, 이번 주 안전 42 km).
 * 어제 42 km 트립 1회, 누적 820 XP, 업적 3개 해금. "데모 초기화"가 이 값으로 되돌린다.
 */
object DemoSeed {
    const val YESTERDAY_TRIP_KM = 42
    const val YESTERDAY_TRIP_XP = 300
    val unlocked: Set<String> = setOf("first_trip", "safe_10", "place_yeoju_rest")

    fun profile(today: LocalDate): ProfileState {
        val yesterday = today.minusDays(1)
        return ProfileState(
            totalXp = 820,
            totalSafeKm = YESTERDAY_TRIP_KM,
            tripCount = 1,
            streak = Streak(days = 1, lastDate = yesterday),
            dayDate = yesterday,
            dayXp = YESTERDAY_TRIP_XP,
            daySafeKm = YESTERDAY_TRIP_KM,
        )
    }

    /** 어제 트립 한 건: (시작 ms, 끝 ms). 오후 3시부터 40분. */
    fun yesterdayTrip(today: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val start = today.minusDays(1).atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
        return start to start + 40 * 60_000L
    }

    val yesterdayLevelBefore: Int get() = LevelTable.levelForXp(820 - YESTERDAY_TRIP_XP)

    /** 시나리오 가상 시계의 시작 시각: 오늘 10:00(야간 보너스가 녹화 시각에 좌우되지 않게). */
    fun scenarioStart(today: LocalDate, zone: ZoneId): Long =
        today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
}
