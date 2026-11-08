package com.mujimujimuji.odo.sim

import com.mujimujimuji.odo.data.AchievementCatalog
import com.mujimujimuji.odo.engine.EngineEvent
import com.mujimujimuji.odo.engine.Gear
import com.mujimujimuji.odo.engine.LevelTable
import com.mujimujimuji.odo.engine.RiskType
import com.mujimujimuji.odo.engine.TripSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** 실제 데모 시나리오를 엔진에 넣어 대본의 숫자가 나오는지 확인한다. */
class GangneungTripTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 8)
    private val start = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    private fun asset(path: String) = File("src/main/assets/$path").readText()

    private fun run(): Pair<TripSession, List<EngineEvent>> {
        val scenario = Scenario.parse(asset("scenarios/gangneung_trip.json"))
        val catalog = AchievementCatalog.parse(asset("achievements.json"))
        val session = TripSession(catalog, DemoSeed.unlocked, DemoSeed.profile(today), zone)
        val events = scenario.timeline(start).flatMap { session.onSample(it) }
        return session to events
    }

    @Test
    fun `시나리오 형식`() {
        val scenario = Scenario.parse(asset("scenarios/gangneung_trip.json"))
        assertEquals(1_000L, scenario.tickMs)
        assertEquals(20, scenario.speedup)
        assertTrue(scenario.durationSec / scenario.speedup < 180) // 3분 안에 재생
    }

    @Test
    fun `카탈로그 12개, 시드 해금 3개`() {
        val catalog = AchievementCatalog.parse(asset("achievements.json"))
        assertEquals(12, catalog.size)
        assertEquals(12, catalog.map { it.id }.toSet().size)
        assertTrue(catalog.map { it.id }.containsAll(DemoSeed.unlocked))
        assertEquals(4, LevelTable.levelForXp(DemoSeed.profile(today).totalXp))
    }

    @Test
    fun `대본대로 상한, 레벨업, 급감속 1회, 도로·지역 업적`() {
        val (session, events) = run()

        assertEquals(30, events.filterIsInstance<EngineEvent.DailyCapReached>().single().todaySafeKm)
        assertEquals(5, events.filterIsInstance<EngineEvent.LevelUp>().single().level)

        val summary = events.filterIsInstance<EngineEvent.TripEnded>().single().summary
        assertEquals(300, summary.xpEarned)
        assertEquals(1, summary.excludedSegments)
        assertEquals(mapOf(RiskType.HARSH_DECEL to 1), summary.eventCounts)
        assertEquals(65, summary.safeKm)
        assertEquals(listOf("road_yeongdong_yeoju_hoengseong"), summary.achievements.map { it.id })

        val unlocked = events.filterIsInstance<EngineEvent.AchievementUnlocked>()
        assertEquals(
            listOf("road_yeongdong_yeoju_hoengseong" to true, "place_gangwon_first" to false),
            unlocked.map { it.achievement.id to it.duringTrip },
        )
        // 체류 10분은 휴게소에서 기어 P로 바꾼 순간부터 잰다(트립 종료는 디바운스 2초 뒤).
        val samples = Scenario.parse(asset("scenarios/gangneung_trip.json")).samples
        val parkedAt = start + (samples.last { it.gear != Gear.PARK }.t + 1) * 1000
        val placeAt = unlocked.last().timeMs
        assertTrue(placeAt >= summary.endTimeMs)
        assertTrue(placeAt - parkedAt >= 10 * 60_000L)

        assertEquals(1_120, session.profile.totalXp)
        assertEquals(42 + 65, session.profile.totalSafeKm)
        assertEquals(2, session.profile.streak.days)
        assertEquals(5, session.unlockedIds.size)
    }
}
