package com.mujimujimuji.odo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.floor

class TripSessionTest {

    /** 정차 → 가속 → 100 km/h 순항(중간 급감속 1회) → 감속 → 정차. 1초 간격, [base]부터. */
    private fun highwayDrive(cruiseSec: Int, harshDecelAtSec: Int, base: Long = T0): List<VehicleSnapshot> {
        val out = mutableListOf<VehicleSnapshot>()
        var t = 0.0
        var v = 0.0
        fun add(gear: Gear) {
            out += snap(t, v, gear = gear, base = base)
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
            // 이 트립의 31번째 안전 km에서 100 km가 된다. 상한은 30번째 안전 km에서 찬다.
            initial = ProfileState(totalXp = 820, totalSafeKm = 69),
            zone = SEOUL,
        )
        val events = highwayDrive(cruiseSec = 1260, harshDecelAtSec = 1100).flatMap { session.onSample(it) }
        val unlocked = events.filterIsInstance<EngineEvent.AchievementUnlocked>().single()
        assertEquals("km100", unlocked.achievement.id)
        assertTrue(unlocked.duringTrip)
        assertTrue(unlocked.timeMs > events.filterIsInstance<EngineEvent.DailyCapReached>().single().timeMs)
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

    // ── 연속 기록과 같은 날 여러 트립 ──

    @Test
    fun `연속 기록은 그날 안전 1 km를 채워야 하루로 센다`() {
        // 0.91 km: 끝난 구간이 없어 연속 기록이 시작되지 않는다.
        val short = TripSession(emptyList(), emptySet(), ProfileState(), SEOUL)
        assertEquals(0, summaryOf(highwayDrive(cruiseSec = 0, harshDecelAtSec = -1), short).safeKm)
        assertEquals(0, short.profile.streak.days)

        // 1.19 km: 안전 구간 정확히 1개로 하루가 된다.
        val oneKm = TripSession(emptyList(), emptySet(), ProfileState(), SEOUL)
        assertEquals(1, summaryOf(highwayDrive(cruiseSec = 10, harshDecelAtSec = -1), oneKm).safeKm)
        assertEquals(Streak(days = 1, lastDate = LocalDate.of(2026, 11, 8)), oneKm.profile.streak)
    }

    @Test
    fun `연속 7일째 트립은 보너스 50 XP를 일일 상한 밖으로 더한다`() {
        val session = TripSession(
            emptyList(), emptySet(),
            ProfileState(totalXp = 820, streak = Streak(days = 6, lastDate = LocalDate.of(2026, 11, 7))),
            SEOUL,
        )
        val events = highwayDrive(cruiseSec = 1260, harshDecelAtSec = 1100).flatMap { session.onSample(it) }
        val bonus = events.filterIsInstance<EngineEvent.StreakBonus>().single()
        assertEquals(7, bonus.days)
        assertEquals(50, bonus.xp)
        assertEquals(30, events.filterIsInstance<EngineEvent.DailyCapReached>().single().todaySafeKm)

        val summary = events.filterIsInstance<EngineEvent.TripEnded>().single().summary
        assertEquals(350, summary.xpEarned)
        assertEquals(50, summary.streakBonusXp)
        assertTrue(summary.capReached)
        assertEquals(1_170, session.profile.totalXp)
        assertEquals(300, session.profile.dayXp)
        assertEquals(Streak(days = 7, lastDate = LocalDate.of(2026, 11, 8)), session.profile.streak)
    }

    @Test
    fun `요약은 연속 보너스를 따로 알려 주고 보너스는 일일 상한에 들지 않는다`() {
        val session = TripSession(
            emptyList(), emptySet(),
            ProfileState(streak = Streak(days = 6, lastDate = LocalDate.of(2026, 11, 7))),
            SEOUL,
        )
        // 6.47 km 무사고: 구간 XP 6 × 10 = 60, 7일째 보너스 50.
        val summary = summaryOf(highwayDrive(cruiseSec = 200, harshDecelAtSec = -1), session)
        assertEquals(6, summary.safeKm)
        assertEquals(110, summary.xpEarned)
        assertEquals(50, summary.streakBonusXp)
        assertEquals(false, summary.capReached)
        assertEquals(60, session.profile.dayXp)
        assertEquals(110, session.profile.totalXp)

        // 보너스가 없는 날은 0.
        val plain = TripSession(emptyList(), emptySet(), ProfileState(), SEOUL)
        assertEquals(0, summaryOf(highwayDrive(cruiseSec = 200, harshDecelAtSec = -1), plain).streakBonusXp)
    }

    @Test
    fun `같은 날 두 번째 트립은 남은 상한만 받고 연속 일수는 한 번만 는다`() {
        val session = TripSession(emptyList(), emptySet(), ProfileState(), SEOUL)
        val first = highwayDrive(cruiseSec = 600, harshDecelAtSec = -1).flatMap { session.onSample(it) }
        val s1 = first.filterIsInstance<EngineEvent.TripEnded>().single().summary
        assertEquals(170, s1.xpEarned)
        assertEquals(false, s1.capReached)
        assertEquals(0, first.count { it is EngineEvent.DailyCapReached })

        val second = highwayDrive(cruiseSec = 600, harshDecelAtSec = -1, base = T0 + 2 * 3_600_000L)
            .flatMap { session.onSample(it) }
        val s2 = second.filterIsInstance<EngineEvent.TripEnded>().single().summary
        assertEquals(130, s2.xpEarned)
        assertTrue(s2.capReached)
        assertEquals(30, second.filterIsInstance<EngineEvent.DailyCapReached>().single().todaySafeKm)

        assertEquals(300, session.profile.totalXp)
        assertEquals(300, session.profile.dayXp)
        assertEquals(34, session.profile.daySafeKm)
        assertEquals(2, session.profile.tripCount)
        assertEquals(1, session.profile.streak.days)
    }

    // ── 이미 상한이 찬 날의 트립 ──

    /** 오늘(T0의 날짜) 이미 300 XP를 채운 프로필. */
    private fun cappedToday() = ProfileState(dayDate = LocalDate.of(2026, 11, 8), dayXp = 300, daySafeKm = 30)

    @Test
    fun `이미 상한이 찬 날의 무사고 트립은 상한 도달로 표시하고 상한 알림은 다시 내지 않는다`() {
        // 6.47 km, 위험 이벤트 없음. 안전 km는 6 그대로 쌓이고 XP는 0이다.
        val session = TripSession(emptyList(), emptySet(), cappedToday(), SEOUL)
        val events = highwayDrive(cruiseSec = 200, harshDecelAtSec = -1).flatMap { session.onSample(it) }
        val summary = events.filterIsInstance<EngineEvent.TripEnded>().single().summary
        assertEquals(6, summary.safeKm)
        assertEquals(0, summary.xpEarned)
        assertTrue(summary.capReached)
        assertEquals(0, events.count { it is EngineEvent.DailyCapReached })
        assertEquals(300, session.profile.dayXp)
        assertEquals(36, session.profile.daySafeKm)
    }

    @Test
    fun `이미 상한이 찬 날이라도 안전 구간이 하나도 없으면 상한 도달로 표시하지 않는다`() {
        // 0.91 km: 끝난 1 km 구간이 없다.
        val session = TripSession(emptyList(), emptySet(), cappedToday(), SEOUL)
        val summary = summaryOf(highwayDrive(cruiseSec = 0, harshDecelAtSec = -1), session)
        assertEquals(0, summary.safeKm)
        assertEquals(0, summary.xpEarned)
        assertEquals(false, summary.capReached)
    }

    @Test
    fun `상한이 찬 밤에 출발해 자정을 넘기면 자정 전 구간은 0 XP, 자정 뒤는 새 날의 야간 XP`() {
        // 23:50 출발, 약 35 km. 자정 전 16 km는 그날 상한이 차 있어 0 XP, 자정 뒤 19 km는 야간 15 XP씩.
        val session = TripSession(
            emptyList(), emptySet(),
            ProfileState(
                totalXp = 2_000,
                streak = Streak(days = 3, lastDate = LocalDate.of(2026, 11, 8)),
                dayDate = LocalDate.of(2026, 11, 8), dayXp = 300, daySafeKm = 30,
            ),
            SEOUL,
        )
        val events = highwayDrive(1260, -1, base = epochMs(2026, 11, 8, 23, 50)).flatMap { session.onSample(it) }
        val midnight = epochMs(2026, 11, 9, 0)
        val segs = events.filterIsInstance<EngineEvent.SegmentCompleted>()
        val (before, after) = segs.partition { it.timeMs < midnight }
        assertEquals(List(16) { 0 }, before.map { it.xpGranted })
        assertEquals(List(19) { 15 }, after.map { it.xpGranted })

        val summary = events.filterIsInstance<EngineEvent.TripEnded>().single().summary
        assertEquals(35, summary.safeKm)
        assertEquals(285, summary.xpEarned)
        assertTrue(summary.capReached) // 자정 전 안전 구간이 상한에 막혔다
        assertEquals(0, events.count { it is EngineEvent.DailyCapReached })

        assertEquals(LocalDate.of(2026, 11, 9), session.profile.dayDate)
        assertEquals(285, session.profile.dayXp)
        assertEquals(19, session.profile.daySafeKm)
        assertEquals(2_285, session.profile.totalXp)
        assertEquals(Streak(days = 4, lastDate = LocalDate.of(2026, 11, 9)), session.profile.streak)
    }

    /** 정차 → 곧바로 [speed] km/h 정속으로 [seconds]초 → 정차. 1초 간격. 급가속 판정을 피하려고 천천히 올리고 내린다. */
    private fun steadyDrive(
        speed: Double,
        seconds: Int,
        speedLimit: Int? = null,
        beltAt: (Int) -> Boolean? = { true },
    ): List<VehicleSnapshot> {
        val out = mutableListOf<VehicleSnapshot>()
        var t = 0
        var v = 0.0
        fun add(gear: Gear) {
            out += snap(t.toDouble(), v, gear = gear, speedLimit = speedLimit, driverBelt = beltAt(t))
            t++
        }
        repeat(5) { add(Gear.PARK) }
        while (v < speed) { v = minOf(speed, v + 5.0); add(Gear.DRIVE) }
        repeat(seconds) { add(Gear.DRIVE) }
        while (v > 0.0) { v = maxOf(0.0, v - 5.0); add(Gear.DRIVE) }
        repeat(5) { add(Gear.PARK) }
        return out
    }

    @Test
    fun `과속이 이어지는 동안 지나간 구간은 모두 0 XP다`() {
        // 제한 100에서 125 km/h로 150초(약 5.2 km). 과속 이벤트는 한 번이지만 그 상태로 달린 구간은 안전 구간이 아니다.
        val session = TripSession(emptyList(), emptySet(), ProfileState(), SEOUL)
        val events = steadyDrive(125.0, 150, speedLimit = 100).flatMap { session.onSample(it) }
        val summary = events.filterIsInstance<EngineEvent.TripEnded>().single().summary
        assertEquals(mapOf(RiskType.SPEEDING to 1), summary.eventCounts)
        assertTrue(summary.distanceKm >= 5.0)
        assertEquals(0, summary.safeKm)
        assertEquals(floor(summary.distanceKm).toInt(), summary.excludedSegments)
        assertEquals(0, summary.xpEarned)
        assertEquals(0, session.profile.totalXp)
    }

    @Test
    fun `벨트를 매지 않고 달린 구간은 0 XP, 맨 뒤부터는 다시 쌓인다`() {
        // 60 km/h(1 km에 60초)로 300초. 처음 130초는 벨트 미체결.
        val session = TripSession(emptyList(), emptySet(), ProfileState(), SEOUL)
        val events = steadyDrive(60.0, 300, beltAt = { t -> t >= 135 }).flatMap { session.onSample(it) }
        val summary = events.filterIsInstance<EngineEvent.TripEnded>().single().summary
        assertEquals(mapOf(RiskType.NO_SEATBELT to 1), summary.eventCounts)
        // 0~1 km, 1~2 km, 벨트를 맨 순간이 든 2~3 km까지 세 구간이 빠지고 나머지는 안전 구간이다.
        assertEquals(3, summary.excludedSegments)
        assertEquals(floor(summary.distanceKm).toInt() - 3, summary.safeKm)
        assertTrue(summary.safeKm >= 2)
        assertEquals(summary.safeKm * 10, summary.xpEarned)
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

    // ── 트립이 확정되기 전(디바운스 2초 안)에 시작된 위반 ──

    /**
     * 정차 5초 → 기어 D로 [startSpeeds]를 1초에 하나씩 → 마지막 속도로 [cruiseSec]초 → 초당 5 km/h로 줄여 정차.
     * 기어 D 첫 샘플이 t=5라서 트립은 세 번째 값(t=7)에서 확정된다.
     */
    private fun quickStart(
        startSpeeds: List<Double>,
        cruiseSec: Int,
        driverBelt: Boolean? = true,
    ): List<VehicleSnapshot> {
        val out = mutableListOf<VehicleSnapshot>()
        var t = 0
        fun add(v: Double, gear: Gear) {
            out += snap(t.toDouble(), v, gear = gear, driverBelt = driverBelt)
            t++
        }
        repeat(5) { add(0.0, Gear.PARK) }
        startSpeeds.forEach { add(it, Gear.DRIVE) }
        var v = startSpeeds.last()
        repeat(cruiseSec) { add(v, Gear.DRIVE) }
        while (v > 0.0) { v = maxOf(0.0, v - 5.0); add(v, Gear.DRIVE) }
        repeat(5) { add(0.0, Gear.PARK) }
        return out
    }

    private fun summaryOf(samples: List<VehicleSnapshot>, session: TripSession): TripSummary =
        samples.flatMap { session.onSample(it) }.filterIsInstance<EngineEvent.TripEnded>().single().summary

    @Test
    fun `트립 확정 전에 시작된 벨트 미착용도 그 트립의 이벤트로 한 번 센다`() {
        // t=6에 20 km/h(벨트 미체결)로 위반이 시작되고 트립은 t=7에 확정된다. 초당 10 km/h라 급가속은 아니다.
        val session = TripSession(emptyList(), emptySet(), ProfileState(), SEOUL)
        val summary = summaryOf(
            quickStart(listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0), cruiseSec = 130, driverBelt = false),
            session,
        )
        assertEquals(mapOf(RiskType.NO_SEATBELT to 1), summary.eventCounts)
        assertTrue(summary.distanceKm >= 2.0)
        assertEquals(0, summary.safeKm)
        assertEquals(floor(summary.distanceKm).toInt(), summary.excludedSegments)
        assertEquals(0, summary.xpEarned)
    }

    @Test
    fun `급출발이 트립 확정 때까지 이어지면 급가속 한 번으로 센다`() {
        // 초당 12 km/h로 t=5부터 t=9까지 가속. 급가속은 t=5에 시작됐고 트립이 확정되는 t=7에도 이어진다.
        val session = TripSession(emptyList(), emptySet(), ProfileState(), SEOUL)
        val summary = summaryOf(quickStart(listOf(12.0, 24.0, 36.0, 48.0, 60.0), cruiseSec = 130), session)
        assertEquals(mapOf(RiskType.HARSH_ACCEL to 1), summary.eventCounts)
        // 급가속이 든 첫 1 km만 빠진다.
        assertEquals(1, summary.excludedSegments)
        assertEquals(1, summary.safeKm)
    }

    @Test
    fun `초당 10번 샘플의 급출발도 급가속 한 번으로 센다`() {
        // 2초 정차 → 0.1초에 1.2 km/h씩(초당 12) 60 km/h까지 → 130초 정속 → 초당 5 km/h로 감속 → 정차 3초.
        val samples = mutableListOf<VehicleSnapshot>()
        fun add(v: Double, gear: Gear) {
            samples += snap(0.0, v, gear = gear).copy(timeMs = T0 + samples.size * 100L)
        }
        repeat(20) { add(0.0, Gear.PARK) }
        for (i in 1..50) add(minOf(60.0, 1.2 * i), Gear.DRIVE)
        repeat(1_300) { add(60.0, Gear.DRIVE) }
        for (i in 1..120) add(maxOf(0.0, 60.0 - 0.5 * i), Gear.DRIVE)
        repeat(30) { add(0.0, Gear.PARK) }

        val session = TripSession(emptyList(), emptySet(), ProfileState(), SEOUL)
        val summary = summaryOf(samples, session)
        assertEquals(mapOf(RiskType.HARSH_ACCEL to 1), summary.eventCounts)
        assertEquals(1, summary.excludedSegments)
    }

    @Test
    fun `트립 확정 전에 끝난 급가속은 세지 않는다`() {
        // t=5에만 초당 12 km/h, 그 뒤로는 초당 2 km/h. 트립이 확정되는 t=7에는 이미 끝난 일이다.
        val session = TripSession(emptyList(), emptySet(), ProfileState(), SEOUL)
        val summary = summaryOf(quickStart(List(25) { 12.0 + 2.0 * it }, cruiseSec = 130), session)
        assertEquals(emptyMap<RiskType, Int>(), summary.eventCounts)
        assertEquals(0, summary.excludedSegments)
        assertEquals(floor(summary.distanceKm).toInt(), summary.safeKm)
        assertTrue(summary.safeKm >= 2)
    }

    @Test
    fun `트립이 확정되는 샘플에서 난 급가속은 센다`() {
        // t=6의 10 km/h에서 t=7의 22 km/h로(초당 12). 판정에는 트립 확정 전 샘플의 속도가 필요하다.
        val session = TripSession(emptyList(), emptySet(), ProfileState(), SEOUL)
        val start = listOf(5.0, 10.0, 22.0) + List(7) { 27.0 + 5.0 * it } + 60.0
        val summary = summaryOf(quickStart(start, cruiseSec = 130), session)
        assertEquals(mapOf(RiskType.HARSH_ACCEL to 1), summary.eventCounts)
    }

    /** 앱이 달리는 중에 켜진 경우: 첫 샘플부터 기어 D·100 km/h로 1,260초 → 초당 5 km/h로 감속 → 정차 5초. */
    private fun midDrive(driverBelt: (Int) -> Boolean, speedLimit: (Int) -> Int?): List<VehicleSnapshot> {
        val out = mutableListOf<VehicleSnapshot>()
        var t = 0
        var v = 100.0
        fun add(gear: Gear) {
            out += snap(t.toDouble(), v, gear = gear, driverBelt = driverBelt(t), speedLimit = speedLimit(t))
            t++
        }
        repeat(1_260) { add(Gear.DRIVE) }
        while (v > 0.0) { v = maxOf(0.0, v - 5.0); add(Gear.DRIVE) }
        repeat(5) { add(Gear.PARK) }
        return out
    }

    @Test
    fun `첫 샘플부터 이어지던 위반도 그 트립에서 한 번 세고 무사고 트립을 열지 않는다`() {
        // 위반은 t=0~59(약 1.6 km)에 이어지고 트립은 t=2에 확정된다. 그 뒤 33 km 넘게 무사고로 달린다.
        val clean = AchievementDef("clean", "무사고", "", AchievementRule.CleanTrip(30))
        val cases = listOf(
            Triple("벨트", midDrive(driverBelt = { it >= 60 }, speedLimit = { null }), RiskType.NO_SEATBELT),
            Triple("과속", midDrive(driverBelt = { true }, speedLimit = { if (it < 60) 70 else 110 }), RiskType.SPEEDING),
        )
        for ((name, samples, type) in cases) {
            val session = TripSession(listOf(clean), emptySet(), ProfileState(), SEOUL)
            val events = samples.flatMap { session.onSample(it) }
            val summary = events.filterIsInstance<EngineEvent.TripEnded>().single().summary
            assertEquals(name, mapOf(type to 1), summary.eventCounts)
            // 위반이 풀린 순간이 든 둘째 구간까지 빠진다.
            assertEquals(name, 2, summary.excludedSegments)
            assertEquals(name, floor(summary.distanceKm).toInt() - 2, summary.safeKm)
            assertTrue(name, summary.safeKm >= 30)
            assertEquals(name, 0, events.count { it is EngineEvent.AchievementUnlocked })
        }
    }

    // ── 샘플 간격 ──

    /** 1초 간격 샘플을 0.1초 간격으로 늘린다. 속도는 앞뒤 샘플 사이를 직선으로 잇고, 기어·위치 등은 앞 샘플 것을 쓴다. */
    private fun tenHzOf(samples: List<VehicleSnapshot>): List<VehicleSnapshot> =
        samples.zipWithNext().flatMap { (a, b) ->
            (0 until 10).map { k ->
                a.copy(timeMs = a.timeMs + k * 100L, speedKmh = a.speedKmh + (b.speedKmh - a.speedKmh) * k / 10.0)
            }
        } + samples.last()

    @Test
    fun `같은 주행을 1초 간격과 초당 10번으로 넣어도 이벤트·제외 구간·XP가 같다`() {
        val oneHz = highwayDrive(cruiseSec = 1260, harshDecelAtSec = 1100)
        fun runOf(samples: List<VehicleSnapshot>): Pair<TripSummary, List<Int>> {
            val session = TripSession(emptyList(), emptySet(), ProfileState(totalXp = 820), SEOUL)
            val events = samples.flatMap { session.onSample(it) }
            val summary = events.filterIsInstance<EngineEvent.TripEnded>().single().summary
            return summary to events.filterIsInstance<EngineEvent.SegmentCompleted>().map { it.xpGranted }
        }
        val (a, aXp) = runOf(oneHz)
        val (b, bXp) = runOf(tenHzOf(oneHz))
        assertEquals(mapOf(RiskType.HARSH_DECEL to 1), a.eventCounts)
        assertEquals(a.eventCounts, b.eventCounts)
        assertEquals(a.excludedSegments, b.excludedSegments)
        assertEquals(a.safeKm, b.safeKm)
        assertEquals(a.xpEarned, b.xpEarned)
        assertEquals(a.capReached, b.capReached)
        assertEquals(a.levelAfter, b.levelAfter)
        assertEquals(aXp, bXp)
        // 초당 10번에서는 t=4.1부터 속도가 0보다 커 트립이 0.9초 일찍 확정되고, 그동안의 2 m쯤을 더 센다.
        assertEquals(a.distanceKm, b.distanceKm, 0.01)
    }

    // ── 도로 업적 진행 ──

    @Test
    fun `트립이 끝나면 다음 업적 게이지가 도로 업적에서 누적 거리 업적으로 돌아온다`() {
        val gateA = LatLng(37.2400, 127.5800)
        val gateB = LatLng(37.4500, 128.1200)
        val mid = LatLng((gateA.lat + gateB.lat) / 2, (gateA.lng + gateB.lng) / 2)
        val road = AchievementDef(
            "road", "도로", "",
            AchievementRule.Road(listOf(Geofence(gateA, 300.0), Geofence(gateB, 300.0))),
        )
        val km100 = AchievementDef("km100", "100 km", "", AchievementRule.SafeDistance(100))
        val session = TripSession(listOf(road, km100), emptySet(), ProfileState(totalSafeKm = 50), SEOUL)

        // 시작 게이트에서 출발해 중간까지만 가서 선다(1 km 미만이라 누적 안전 km는 그대로 50).
        val (driving, stopping) = steadyDrive(60.0, 30)
            .map { it.copy(location = if (it.timeMs < T0 + 20_000) gateA else mid) }
            .partition { it.timeMs <= T0 + 30_000 }
        driving.forEach { session.onSample(it) }
        assertEquals("road", session.nextGoal()!!.achievement.id)

        val events = stopping.flatMap { session.onSample(it) }
        assertEquals(1, events.count { it is EngineEvent.TripEnded })
        assertEquals(Goal(km100, 0.5f), session.nextGoal())
    }
}
