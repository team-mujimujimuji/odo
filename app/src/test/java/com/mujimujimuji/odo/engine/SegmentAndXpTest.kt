package com.mujimujimuji.odo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SegmentAndXpTest {

    private fun seg(
        safe: Boolean = true,
        night: Boolean = false,
        wiper: Boolean = false,
        allBelts: Boolean = false,
    ) = Segment(0, T0, safe, night, wiper, allBelts)

    @Test
    fun `36 km_h로 100초 달리면 1 km 구간 하나`() {
        val acc = SegmentAccumulator(SEOUL)
        val done = (0..100).flatMap { acc.onSample(snap(it.toDouble(), 36.0), false) }
        assertEquals(1, done.size)
        assertTrue(done[0].safe)
        assertEquals(1000.0, acc.totalMeters, 1e-6)
    }

    @Test
    fun `주행거리 신호가 있으면 그 차이로 센다`() {
        val acc = SegmentAccumulator(SEOUL)
        acc.onSample(snap(0.0, 10.0).copy(odometerKm = 100.0), false)
        val done = acc.onSample(snap(1.0, 10.0).copy(odometerKm = 102.5), false)
        assertEquals(2, done.size)
        assertEquals(2500.0, acc.totalMeters, 1e-6)
    }

    @Test
    fun `주행거리 신호가 멈춰 있으면 속도로 센다`() {
        val acc = SegmentAccumulator(SEOUL)
        val done = (0..100).flatMap { acc.onSample(snap(it.toDouble(), 36.0).copy(odometerKm = 0.0), false) }
        assertEquals(1, done.size)
        assertEquals(1000.0, acc.totalMeters, 1e-6)
    }

    @Test
    fun `이벤트가 난 구간만 위험 구간이 된다`() {
        val acc = SegmentAccumulator(SEOUL)
        val done = (0..300).flatMap { acc.onSample(snap(it.toDouble(), 36.0), it == 150) }
        assertEquals(listOf(true, false, true), done.map { it.safe })
    }

    @Test
    fun `보너스 플래그는 신호가 있을 때만`() {
        val night = epochMs(2026, 11, 8, 23)
        val acc = SegmentAccumulator(SEOUL)
        val done = (0..100).flatMap {
            acc.onSample(snap(it.toDouble(), 36.0, base = night, wiperOn = it == 10, allBelts = true), false)
        }
        assertEquals(1, done.size)
        assertTrue(done[0].night && done[0].wiper && done[0].allBelts)

        val acc2 = SegmentAccumulator(SEOUL)
        val day = (0..100).flatMap {
            acc2.onSample(snap(it.toDouble(), 36.0, allBelts = if (it == 50) null else true), false)
        }
        assertFalse(day[0].night || day[0].wiper || day[0].allBelts)
    }

    @Test
    fun `안전 구간 10 XP, 위험 구간 0 XP`() {
        assertEquals(10, XpCalculator.segmentXp(seg()))
        assertEquals(0, XpCalculator.segmentXp(seg(safe = false, night = true)))
    }

    @Test
    fun `보너스 배수는 곱한다`() {
        assertEquals(15, XpCalculator.segmentXp(seg(night = true)))
        assertEquals(15, XpCalculator.segmentXp(seg(wiper = true)))
        assertEquals(11, XpCalculator.segmentXp(seg(allBelts = true)))
        assertEquals(23, XpCalculator.segmentXp(seg(night = true, wiper = true))) // 22.5 반올림
        assertEquals(11, XpCalculator.segmentXp(seg(), turnSignalRate = 0.9))
        assertEquals(10, XpCalculator.segmentXp(seg(), turnSignalRate = 0.89))
    }

    @Test
    fun `일일 상한 300 XP`() {
        assertEquals(300, Thresholds.DAILY_XP_CAP)
        assertEquals(10, XpCalculator.applyDailyCap(290, 10))
        assertEquals(5, XpCalculator.applyDailyCap(295, 15))
        assertEquals(0, XpCalculator.applyDailyCap(300, 10))
    }

    @Test
    fun `연속 기록 7일 +50, 30일 +300`() {
        var streak = Streak()
        var bonus = 0
        val start = LocalDate.of(2026, 10, 1)
        val bonusByDay = mutableMapOf<Int, Int>()
        for (i in 0 until 30) {
            val r = StreakRules.onQualifiedDay(streak, start.plusDays(i.toLong()))
            streak = r.streak
            bonus += r.bonusXp
            if (r.bonusXp > 0) bonusByDay[streak.days] = r.bonusXp
        }
        assertEquals(30, streak.days)
        assertEquals(mapOf(7 to 50, 14 to 50, 21 to 50, 28 to 50, 30 to 300), bonusByDay)

        val sameDay = StreakRules.onQualifiedDay(streak, streak.lastDate!!)
        assertEquals(0, sameDay.bonusXp)

        val broken = StreakRules.onQualifiedDay(streak, streak.lastDate!!.plusDays(2))
        assertEquals(1, broken.streak.days)
        assertEquals(0, StreakRules.currentDays(streak, streak.lastDate!!.plusDays(2)))
    }

    @Test
    fun `세이프티 스코어`() {
        assertEquals(null, SafetyScore.score(0, 0.0))
        assertEquals(100, SafetyScore.score(0, 50.0))
        assertEquals(90, SafetyScore.score(1, 100.0))
        assertEquals(85, SafetyScore.score(1, 66.0))
        assertEquals(0, SafetyScore.score(20, 100.0))
    }

    @Test
    fun `주행 중 TTS는 10분에 2회`() {
        val v = VoicePolicy()
        val min = 60_000L
        assertTrue(v.tryAcquire(0, true))
        assertTrue(v.tryAcquire(1 * min, true))
        assertFalse(v.tryAcquire(5 * min, true))
        assertTrue(v.tryAcquire(5 * min, false))
        assertTrue(v.tryAcquire(10 * min, true))
        assertFalse(v.tryAcquire(10 * min + 1, true))
        assertTrue(v.tryAcquire(11 * min, true))
    }
}
