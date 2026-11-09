package com.mujimujimuji.odo.drive

import com.mujimujimuji.odo.data.AchievementCatalog
import com.mujimujimuji.odo.engine.AchievementDef
import com.mujimujimuji.odo.engine.AchievementRule
import com.mujimujimuji.odo.engine.DriveState
import com.mujimujimuji.odo.engine.DriveStateMachine
import com.mujimujimuji.odo.engine.EngineEvent
import com.mujimujimuji.odo.engine.Goal
import com.mujimujimuji.odo.engine.TripSession
import com.mujimujimuji.odo.sim.DemoSeed
import com.mujimujimuji.odo.sim.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** 설계서 §6 "축적만"·P3: 주행 중 글랜스 게이지는 뒤로 가지 않고, 보이던 목표가 열리면 정차 전까지 꽉 찬 채로 둔다. */
class GlanceGaugeLatchTest {
    private val safe = AchievementDef("safe_300", "안전 300 km", "", AchievementRule.SafeDistance(300))
    private val road = AchievementDef("road_x", "도로", "", AchievementRule.Road(emptyList()))

    @Test
    fun `정차 중에는 엔진 값 그대로`() {
        val latch = GlanceGaugeLatch()
        assertEquals(GlanceGauge(0.35f, medalOpen = false), latch.update(false, Goal(safe, 0.35f)))
        assertEquals(GlanceGauge(1f, medalOpen = true), latch.update(false, null))
    }

    @Test
    fun `주행 중 목표가 바뀌어 진행률이 낮아져도 게이지는 그대로`() {
        val latch = GlanceGaugeLatch()
        assertEquals(0.14f, latch.update(true, Goal(safe, 0.14f)).progress)
        // 도로 업적 시작 게이트 통과 → 엔진 목표가 도로 업적(0.03)으로 바뀐다.
        assertEquals(0.14f, latch.update(true, Goal(road, 0.03f)).progress)
        // 굽은 길에서 직선거리 진행률이 잠깐 줄어도 그대로.
        assertEquals(0.5f, latch.update(true, Goal(road, 0.5f)).progress)
        assertEquals(0.5f, latch.update(true, Goal(road, 0.48f)).progress)
    }

    @Test
    fun `보이던 목표가 주행 중 열리면 정차 전까지 꽉 찬 게이지, 메달은 정차 후에`() {
        val latch = GlanceGaugeLatch()
        latch.update(true, Goal(road, 0.98f))
        latch.onUnlocked(road.id)
        val after = latch.update(true, Goal(safe, 0.35f))
        assertEquals(1f, after.progress)
        assertFalse(after.medalOpen)
        assertEquals(1f, latch.update(true, Goal(safe, 0.36f)).progress)
        // 정차하면 풀린다: 다음 목표의 실제 진행률.
        assertEquals(GlanceGauge(0.36f, medalOpen = false), latch.update(false, Goal(safe, 0.36f)))
        assertEquals(0.36f, latch.update(true, Goal(safe, 0.36f)).progress)
    }

    @Test
    fun `보이지 않던 업적이 열리면 래치하지 않는다`() {
        val latch = GlanceGaugeLatch()
        latch.update(true, Goal(safe, 0.2f))
        latch.onUnlocked("place_somewhere")
        assertEquals(0.21f, latch.update(true, Goal(safe, 0.21f)).progress)
    }

    /** 실제 데모 시나리오: 엔진 값은 65 km에서 ≈1.0 → 0.35로 떨어지지만 화면 값은 주행 내내 줄지 않는다. */
    @Test
    fun `강릉 데모 주행 내내 게이지가 줄지 않는다`() {
        val zone = ZoneId.of("Asia/Seoul")
        val today = LocalDate.of(2026, 11, 8)
        val start = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        fun asset(path: String) = File("src/main/assets/$path").readText()
        val scenario = Scenario.parse(asset("scenarios/gangneung_trip.json"))
        val catalog = AchievementCatalog.parse(asset("achievements.json"))
        val session = TripSession(catalog, DemoSeed.unlocked, DemoSeed.profile(today), zone)
        val latch = GlanceGaugeLatch()

        var shown = -1f
        var rawDrop = 0f
        var lastRaw = -1f
        var drivingSamples = 0
        var lastDriving: GlanceGauge? = null
        for (s in scenario.timeline(start)) {
            val events = session.onSample(s)
            events.filterIsInstance<EngineEvent.AchievementUnlocked>().forEach { latch.onUnlocked(it.achievement.id) }
            val driving = DriveStateMachine.isMoving(s.gear, s.speedKmh) || session.state == DriveState.DRIVING
            val goal = session.nextGoal()
            val g = latch.update(driving, goal)
            if (driving) {
                drivingSamples++
                val raw = goal?.progress ?: 1f
                if (lastRaw >= 0f) rawDrop = maxOf(rawDrop, lastRaw - raw)
                lastRaw = raw
                assertTrue("게이지가 ${s.timeMs}에서 $shown → ${g.progress}로 줄었다", g.progress >= shown)
                shown = g.progress
                lastDriving = g
            } else {
                assertEquals(GlanceGauge.of(goal), g)
            }
        }
        assertTrue(drivingSamples > 1_000)
        // 고친 문제가 실제로 있었는지: 엔진 값은 주행 중 크게(≥0.5) 떨어진 적이 있다.
        assertTrue("엔진 값 최대 하락 $rawDrop", rawDrop >= 0.5f)
        // 도로 업적을 연 뒤 정차 직전까지 꽉 찬 게이지, 메달은 외곽선.
        assertEquals(GlanceGauge(1f, medalOpen = false), lastDriving)
    }
}
