package com.mujimujimuji.odo.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 엔진이 이어서 계산하는 프로필 상태. 앱은 이 값을 그대로 저장했다가 다음 실행 때 넘긴다. */
data class ProfileState(
    val totalXp: Int = 0,
    val totalSafeKm: Int = 0,
    val nightSafeKm: Int = 0,
    val rainSafeKm: Int = 0,
    val tripCount: Int = 0,
    val streak: Streak = Streak(),
    /** 아래 day* 값이 가리키는 날짜. */
    val dayDate: LocalDate? = null,
    val dayXp: Int = 0,
    val daySafeKm: Int = 0,
)

data class TripSummary(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val distanceKm: Double,
    val safeKm: Int,
    /** 위험 이벤트가 있어 XP에서 제외된 1 km 구간 수. */
    val excludedSegments: Int,
    val eventCounts: Map<RiskType, Int>,
    val xpEarned: Int,
    val capReached: Boolean,
    val levelBefore: Int,
    val levelAfter: Int,
    val achievements: List<AchievementDef>,
) {
    val eventCount: Int get() = eventCounts.values.sum()
}

sealed interface EngineEvent {
    val timeMs: Long

    data class TripStarted(override val timeMs: Long) : EngineEvent
    data class SegmentCompleted(override val timeMs: Long, val segment: Segment, val xpGranted: Int) : EngineEvent
    data class DailyCapReached(override val timeMs: Long, val todaySafeKm: Int) : EngineEvent
    data class LevelUp(override val timeMs: Long, val level: Int) : EngineEvent
    data class StreakBonus(override val timeMs: Long, val days: Int, val xp: Int) : EngineEvent
    data class AchievementUnlocked(
        override val timeMs: Long,
        val achievement: AchievementDef,
        val duringTrip: Boolean,
    ) : EngineEvent
    data class TripEnded(override val timeMs: Long, val summary: TripSummary) : EngineEvent
}

/**
 * 신호 샘플을 받아 트립·XP·레벨·업적을 한 곳에서 계산한다. 화면과 저장은 [EngineEvent]와 [profile]만 본다.
 */
class TripSession(
    catalog: List<AchievementDef>,
    unlocked: Set<String>,
    initial: ProfileState,
    private val zone: ZoneId,
    homeZones: List<LatLng> = emptyList(),
) {
    private val stateMachine = DriveStateMachine()
    private val detector = EventDetector()
    private val segments = SegmentAccumulator(zone)
    private val achievements = AchievementEngine(catalog, unlocked, homeZones)

    var profile: ProfileState = initial
        private set

    val state: DriveState get() = stateMachine.state
    val unlockedIds: Set<String> get() = achievements.unlockedIds

    /** 진행 중인 트립의 달린 거리(km). 트립이 없으면 0. */
    val tripDistanceKm: Double get() = if (trip != null) segments.totalMeters / 1000.0 else 0.0

    private var trip: TripProgress? = null

    private class TripProgress(val startTimeMs: Long, val levelBefore: Int) {
        var safeKm = 0
        var excluded = 0
        var xp = 0
        var capReached = false
        val eventCounts = mutableMapOf<RiskType, Int>()
        val achievements = mutableListOf<AchievementDef>()
    }

    fun level(): Int = LevelTable.levelForXp(profile.totalXp)

    fun nextGoal(): Goal? = achievements.nextGoal(stats(today()))

    fun onSample(s: VehicleSnapshot): List<EngineEvent> {
        val out = mutableListOf<EngineEvent>()
        val date = dateOf(s.timeMs)
        if (profile.dayDate != date) profile = profile.copy(dayDate = date, dayXp = 0, daySafeKm = 0)

        when (stateMachine.onSample(s.timeMs, s.gear, s.speedKmh)) {
            DriveState.DRIVING -> startTrip(s.timeMs, out)
            DriveState.PARKED -> endTrip(s.timeMs, out)
            null -> Unit
        }

        val risks = detector.onSample(s)
        val current = trip
        if (current != null) {
            risks.forEach { current.eventCounts.merge(it.type, 1, Int::plus) }
            segments.onSample(s, risks.isNotEmpty()).forEach { onSegment(it, current, out) }
        }

        achievements.onSample(s, inTrip = current != null).forEach {
            current?.achievements?.add(it)
            out += EngineEvent.AchievementUnlocked(s.timeMs, it, duringTrip = current != null)
        }
        return out
    }

    private fun startTrip(timeMs: Long, out: MutableList<EngineEvent>) {
        trip = TripProgress(timeMs, level())
        segments.reset()
        achievements.onTripStart()
        out += EngineEvent.TripStarted(timeMs)
    }

    private fun endTrip(timeMs: Long, out: MutableList<EngineEvent>) {
        val t = trip ?: return
        trip = null
        profile = profile.copy(tripCount = profile.tripCount + 1)
        val unlockedNow = achievements.onTripEnd(t.safeKm, t.eventCounts.values.sum()) +
            achievements.onStats(stats(dateOf(timeMs)))
        t.achievements += unlockedNow
        unlockedNow.forEach { out += EngineEvent.AchievementUnlocked(timeMs, it, duringTrip = true) }
        out += EngineEvent.TripEnded(
            timeMs,
            TripSummary(
                startTimeMs = t.startTimeMs,
                endTimeMs = timeMs,
                distanceKm = segments.totalMeters / 1000.0,
                safeKm = t.safeKm,
                excludedSegments = t.excluded,
                eventCounts = t.eventCounts.toMap(),
                xpEarned = t.xp,
                capReached = t.capReached,
                levelBefore = t.levelBefore,
                levelAfter = level(),
                achievements = t.achievements.toList(),
            ),
        )
    }

    private fun onSegment(seg: Segment, t: TripProgress, out: MutableList<EngineEvent>) {
        val levelBefore = level()
        val date = dateOf(seg.endTimeMs)
        var p = profile
        var granted = 0
        if (seg.safe) {
            t.safeKm++
            val capWasOpen = p.dayXp < Thresholds.DAILY_XP_CAP
            granted = XpCalculator.applyDailyCap(p.dayXp, XpCalculator.segmentXp(seg))
            p = p.copy(
                totalXp = p.totalXp + granted,
                dayXp = p.dayXp + granted,
                daySafeKm = p.daySafeKm + 1,
                totalSafeKm = p.totalSafeKm + 1,
                nightSafeKm = p.nightSafeKm + if (seg.night) 1 else 0,
                rainSafeKm = p.rainSafeKm + if (seg.wiper) 1 else 0,
            )
            if (capWasOpen && p.dayXp >= Thresholds.DAILY_XP_CAP) {
                t.capReached = true
                out += EngineEvent.DailyCapReached(seg.endTimeMs, p.daySafeKm)
            }
            if (p.daySafeKm >= Thresholds.STREAK_MIN_SAFE_KM && p.streak.lastDate != date) {
                val r = StreakRules.onQualifiedDay(p.streak, date)
                p = p.copy(streak = r.streak, totalXp = p.totalXp + r.bonusXp)
                granted += r.bonusXp
                if (r.bonusXp > 0) out += EngineEvent.StreakBonus(seg.endTimeMs, r.streak.days, r.bonusXp)
            }
        } else {
            t.excluded++
        }
        profile = p
        t.xp += granted
        out += EngineEvent.SegmentCompleted(seg.endTimeMs, seg, granted)

        val levelAfter = level()
        if (levelAfter > levelBefore) out += EngineEvent.LevelUp(seg.endTimeMs, levelAfter)

        achievements.onStats(stats(date)).forEach {
            t.achievements += it
            out += EngineEvent.AchievementUnlocked(seg.endTimeMs, it, duringTrip = true)
        }
    }

    private fun stats(date: LocalDate) = AchievementStats(
        totalSafeKm = profile.totalSafeKm,
        nightSafeKm = profile.nightSafeKm,
        rainSafeKm = profile.rainSafeKm,
        tripCount = profile.tripCount,
        streakDays = StreakRules.currentDays(profile.streak, date),
        level = level(),
    )

    private fun today(): LocalDate = profile.dayDate ?: LocalDate.now(zone)

    private fun dateOf(timeMs: Long): LocalDate = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()
}
