package com.mujimujimuji.odo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

class TripSessionTest {

    /** 정차 → 가속 → 100 km/h 순항(중간 급감속 1회) → 감속 → 정차. 1초 간격. */
    private fun highwayDrive(cruiseSec: Int, harshDecelAtSec: Int): List<VehicleSnapshot> {
        val out = mutableListOf<VehicleSnapshot>()
        var t = 0.0
        var v = 0.0
        fun add(gear: Gear) {
            out += snap(t, v, gear = gear)
            t += 1.0
        }
        repeat(5) { add(Gear.PARK) }
        while (v < 99.0) { v += 3.0; add(Gear.DRIVE) }
        v = 100.0
        for (i in 0 until cruiseSec) {
            if (i == harshDecelAtSec) v = 90.0
            else if (v < 100.0) v = minOf(100.0, v + 2.0)
            add(Gear.DRIVE)
        }
        while (v > 0.0) { v = maxOf(0.0, v - 3.0); add(Gear.DRIVE) }
        repeat(5) { add(Gear.PARK) }
        return out
    }

    @Test
    fun `고속도로 35 km - 30 km에서 상한, 급감속 구간 하나 제외, 레벨 5`() {
        val session = TripSession(
            catalog = emptyList(),
            unlocked = emptySet(),
            initial = ProfileState(totalXp = 820),
            zone = SEOUL,
        )
        val events = highwayDrive(cruiseSec = 1260, harshDecelAtSec = 1100).flatMap { session.onSample(it) }

        assertEquals(1, events.count { it is EngineEvent.TripStarted })
        val cap = events.filterIsInstance<EngineEvent.DailyCapReached>().single()
        assertEquals(30, cap.todaySafeKm)
        assertEquals(5, events.filterIsInstance<EngineEvent.LevelUp>().single().level)

        val summary = events.filterIsInstance<EngineEvent.TripEnded>().single().summary
        assertEquals(300, summary.xpEarned)
        assertTrue(summary.capReached)
        assertEquals(1, summary.excludedSegments)
        assertEquals(mapOf(RiskType.HARSH_DECEL to 1), summary.eventCounts)
        assertEquals(floor(summary.distanceKm).toInt(), summary.safeKm + summary.excludedSegments)
        assertEquals(4, summary.levelBefore)
        assertEquals(5, summary.levelAfter)

        assertEquals(1_120, session.profile.totalXp)
        assertEquals(300, session.profile.dayXp)
        assertEquals(1, session.profile.tripCount)
        assertEquals(1, session.profile.streak.days)
        assertEquals(DriveState.PARKED, session.state)
    }

    @Test
    fun `상한 이후 안전 km는 업적에 반영된다`() {
        val km100 = AchievementDef("km100", "100 km", "", AchievementRule.SafeDistance(100))
        val session = TripSession(
            catalog = listOf(km100),
            unlocked = emptySet(),
            initial = ProfileState(totalXp = 820, totalSafeKm = 82),
            zone = SEOUL,
        )
        val events = highwayDrive(cruiseSec = 1260, harshDecelAtSec = 1100).flatMap { session.onSample(it) }
        val unlocked = events.filterIsInstance<EngineEvent.AchievementUnlocked>().single()
        assertEquals("km100", unlocked.achievement.id)
        assertTrue(unlocked.duringTrip)
        val summary = events.filterIsInstance<EngineEvent.TripEnded>().single().summary
        assertEquals(listOf(km100), summary.achievements)
    }

    @Test
    fun `날짜가 바뀌면 일일 상한이 새로 시작한다`() {
        val session = TripSession(
            catalog = emptyList(),
            unlocked = emptySet(),
            initial = ProfileState(dayDate = java.time.LocalDate.of(2026, 11, 7), dayXp = 300, daySafeKm = 30),
            zone = SEOUL,
        )
        highwayDrive(cruiseSec = 200, harshDecelAtSec = -1).forEach { session.onSample(it) }
        assertTrue(session.profile.dayXp > 0)
        assertEquals(session.profile.dayXp, session.profile.totalXp)
    }

    @Test
    fun `XP는 깎이지 않는다`() {
        val session = TripSession(emptyList(), emptySet(), ProfileState(totalXp = 500), SEOUL)
        var last = 500
        highwayDrive(cruiseSec = 600, harshDecelAtSec = 100).forEach {
            session.onSample(it)
            assertTrue(session.profile.totalXp >= last)
            last = session.profile.totalXp
        }
    }
}
