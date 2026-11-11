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
    fun `주행거리 신호가 1 km 눈금씩 오르면 그 차이를 따른다`() {
        // 36 km/h(1초에 10 m). 첫 눈금(100.0→100.1)은 위상만 맞추고 속도로 센다(10 m),
        // 그다음 눈금부터는 주행거리 차이(1.0 km, 1.0 km)를 따른다. 눈금 한 칸(1 km)은 속도 거리가 0이어도 믿는다.
        val acc = SegmentAccumulator(SEOUL)
        val done = listOf(100.0, 100.1, 101.1, 102.1).withIndex().flatMap { (t, odo) ->
            acc.onSample(snap(t.toDouble(), 36.0).copy(odometerKm = odo), false)
        }
        assertEquals(2, done.size)
        assertEquals(10.0 + 1000.0 + 1000.0, acc.totalMeters, 1e-6)
    }

    @Test
    fun `주행거리 신호가 멈춰 있으면 속도로 센다`() {
        val acc = SegmentAccumulator(SEOUL)
        val done = (0..100).flatMap { acc.onSample(snap(it.toDouble(), 36.0).copy(odometerKm = 0.0), false) }
        assertEquals(1, done.size)
        assertEquals(1000.0, acc.totalMeters, 1e-6)
    }

    // ── 주행거리 신호로 넘어갈 때와 신호가 끊겼을 때 ──

    /** 36 km/h(1초에 10 m), 1초 간격. [odoAt]이 그 초의 주행거리 값(km, 없으면 null)을 준다. */
    private fun feed(acc: SegmentAccumulator, seconds: IntRange, odoAt: (Int) -> Double?): List<Segment> =
        seconds.flatMap { acc.onSample(snap(it.toDouble(), 36.0).copy(odometerKm = odoAt(it)), false) }

    @Test
    fun `멈춰 있던 주행거리가 처음 늘어나는 순간에 거리를 덧붙이지 않는다`() {
        // 1 km 눈금: 60초 동안 12,345에 멈춰 있다가 12,346이 된다. 그 사이 600 m는 이미 속도로 셌다.
        val acc = SegmentAccumulator(SEOUL)
        val done = feed(acc, 0..60) { if (it < 60) 12_345.0 else 12_346.0 }
        assertEquals(600.0, acc.totalMeters, 1e-6)
        assertEquals(0, done.size)
    }

    @Test
    fun `1 km 눈금 사이와 마지막 눈금 뒤는 속도로 세고 눈금에서 맞춘다`() {
        // 1 km 눈금: 60초와 160초에 눈금이 오르고 250초에 트립이 끝난다.
        val acc = SegmentAccumulator(SEOUL)
        feed(acc, 0..60) { if (it < 60) 12_345.0 else 12_346.0 }
        assertEquals(600.0, acc.totalMeters, 1e-6)
        // 눈금이 그대로인 동안에도 속도로 센다(990 m).
        val between = feed(acc, 61..159) { 12_346.0 }
        assertEquals(1590.0, acc.totalMeters, 1e-6)
        assertEquals(1, between.size)
        // 다음 눈금에서 두 눈금 사이가 정확히 1 km가 되도록 맞춘다(10 m만 더한다).
        feed(acc, 160..160) { 12_347.0 }
        assertEquals(1600.0, acc.totalMeters, 1e-6)
        // 마지막 눈금 뒤 트립 끝까지 90초(900 m)도 버리지 않는다.
        val tail = feed(acc, 161..250) { 12_347.0 }
        assertEquals(2500.0, acc.totalMeters, 1e-6)
        assertEquals(1, tail.size)
    }

    @Test
    fun `멈춰 있던 주행거리가 7초 만에 처음 오를 때 앞 6초를 겹쳐 세지 않는다`() {
        // 100 m 눈금이 7초에 처음 오른다. 앞 6초(60 m)는 이미 속도로 셌으니 7초째는 10 m만 더한다.
        val acc = SegmentAccumulator(SEOUL)
        feed(acc, 0..7) { if (it < 7) 100.0 else 100.1 }
        assertEquals(70.0, acc.totalMeters, 1e-6)
    }

    @Test
    fun `주행거리 눈금이 맞는 뒤에는 속도보다 주행거리를 따른다`() {
        // 속도는 1초에 10 m인데 주행거리는 1초에 20 m씩 오른다. 첫 눈금만 속도로 세고 그 뒤는 20 m씩.
        val acc = SegmentAccumulator(SEOUL)
        feed(acc, 0..10) { 100.0 + 0.02 * it }
        assertEquals(10.0 + 9 * 20.0, acc.totalMeters, 1e-6)
    }

    @Test
    fun `살아 있는 주행거리가 50 km 튀어도 구간이 생기지 않는다`() {
        val acc = SegmentAccumulator(SEOUL)
        feed(acc, 0..20) { 100.0 + 0.1 * (it / 10) } // 살아난 상태, 기준 100.2
        val before = acc.totalMeters
        val spike = feed(acc, 21..21) { 150.2 } // 한 샘플만 +50 km
        assertEquals(10.0, acc.totalMeters - before, 1e-6)
        assertEquals(0, spike.size)
        val back = feed(acc, 22..22) { 100.2 } // 원래 값으로 돌아오면 줄어든 것이니 다시 기준을 잡는다
        assertEquals(20.0, acc.totalMeters - before, 1e-6)
        assertEquals(0, back.size)
    }

    @Test
    fun `주행거리 값이 끊겼다 돌아와도 공백 동안의 거리를 겹쳐 세지 않는다`() {
        // 100 m 눈금(10초에 한 눈금). 21~39초에는 값이 없어 속도로 세고, 40초에 값이 돌아온다.
        val acc = SegmentAccumulator(SEOUL)
        feed(acc, 0..50) { if (it in 21..39) null else 100.0 + 0.1 * (it / 10) }
        assertEquals(500.0, acc.totalMeters, 1e-6)
    }

    @Test
    fun `주행거리 값이 줄면 그 샘플은 속도로 세고 기준을 다시 잡는다`() {
        val acc = SegmentAccumulator(SEOUL)
        feed(acc, 0..20) { 100.0 + 0.1 * (it / 10) } // 살아난 상태, 기준 100.2
        val before = acc.totalMeters
        feed(acc, 21..21) { 50.0 } // 값이 줄었다
        assertEquals(10.0, acc.totalMeters - before, 1e-6)
        feed(acc, 22..29) { 50.0 } // 눈금이 그대로인 동안은 속도로 센다
        assertEquals(90.0, acc.totalMeters - before, 1e-6)
        feed(acc, 30..30) { 50.1 } // 새 기준 50.0 뒤 첫 눈금은 위상만 맞추고 속도로 센다(100 m를 더하지 않는다)
        assertEquals(100.0, acc.totalMeters - before, 1e-6)
    }

    @Test
    fun `주행거리가 0에서 12,345 km로 한 번에 뛰어도 구간이 생기지 않는다`() {
        val acc = SegmentAccumulator(SEOUL)
        val done = feed(acc, 0..20) { if (it < 10) 0.0 else 12_345.0 }
        assertEquals(0, done.size)
        assertEquals(200.0, acc.totalMeters, 1e-6)
        // 뛴 값을 새 기준으로 삼아, 다음 눈금부터 다시 주행거리 차이로 센다.
        feed(acc, 21..40) { 12_345.0 + 0.1 * ((it - 20) / 10) }
        assertEquals(400.0, acc.totalMeters, 1e-6)
    }

    @Test
    fun `살아 있는 주행거리가 직전 눈금 뒤 속도 거리보다 1 km 넘게 늘거나 2 km 넘게 늘면 믿지 않고 속도로 센다`() {
        assertEquals(1_000.0, Thresholds.ODOMETER_STEP_SLACK_M, 0.0)
        // 살아난 주행거리(기준 100.5 km)가 [pausedSeconds]초 멈춰 있다가(직전 눈금 뒤 속도 거리 = 10 m × pausedSeconds)
        // 다음 샘플에서 [stepKm]만큼 늘 때 더해진 거리.
        fun addedByStep(stepKm: Double, pausedSeconds: Int = 0): Double {
            val acc = SegmentAccumulator(SEOUL)
            feed(acc, 0..1) { 100.0 + 0.5 * it }
            feed(acc, 2..pausedSeconds + 1) { 100.5 }
            val before = acc.totalMeters
            val t = pausedSeconds + 2
            feed(acc, t..t) { 100.5 + stepKm }
            return acc.totalMeters - before
        }
        // 직전 눈금 뒤 속도 거리 0 m: 눈금 한 칸(1 km)까지는 믿는다.
        assertEquals(1000.0, addedByStep(1.0), 1e-6)
        // 그보다 많이 늘면 튄 값이다: 그 샘플은 속도로 센다(10 m).
        assertEquals(10.0, addedByStep(1.5), 1e-6)
        assertEquals(10.0, addedByStep(2.0), 1e-6)
        assertEquals(10.0, addedByStep(2.25), 1e-6)
        // 150초 멈춰 직전 눈금 뒤 속도 거리가 1,500 m면 슬랙(2,500 m까지)보다 [Thresholds.MAX_ODOMETER_STEP_KM](2 km)가 좁다.
        // 2 km까지는 믿어 두 눈금 사이를 2 km로 맞추고(500 m를 더한다), 2 km 넘게 늘면 속도로 센다(10 m).
        assertEquals(500.0, addedByStep(2.0, pausedSeconds = 150), 1e-6)
        assertEquals(10.0, addedByStep(2.25, pausedSeconds = 150), 1e-6)
    }

    @Test
    fun `살아 있는 주행거리가 한 프레임만 1_5 km 튀고 돌아오면 가짜 구간이 생기지 않는다`() {
        // 실제 경로처럼 0.1초마다 차속(36 km/h, 한 프레임에 1 m)과 0.1 km 눈금 주행거리(10초에 한 칸)가 함께 온다.
        // 26초 한 프레임만 +1.5 km 튀고 다음 프레임에 제자리로 돌아온다.
        val acc = SegmentAccumulator(SEOUL)
        val done = (0..400).flatMap { i ->
            val odo = if (i == 260) 101.7 else 100.0 + 0.1 * (i / 100)
            acc.onSample(snap(0.0, 36.0).copy(timeMs = T0 + i * 100L, odometerKm = odo), false)
        }
        // 튄 프레임은 속도로 세고 기준을 다시 잡는다. 거리는 속도로 센 값(40초 × 10 m)과 같다.
        assertEquals(400.0, acc.totalMeters, 1e-6)
        assertEquals(0, done.size)
    }

    @Test
    fun `1 km 눈금 계기의 눈금 한 칸은 속도로 센 거리가 모자라도 믿는다`() {
        // 1 km 눈금이 60초에 처음 오르고(위상만 맞춘다), 그 뒤 차속은 실제의 절반(18 km/h, 1초에 5 m)으로 들어온다.
        val acc = SegmentAccumulator(SEOUL)
        val done = feed(acc, 0..60) { if (it < 60) 12_345.0 else 12_346.0 }.toMutableList()
        fun at(t: Int, odo: Double) = acc.onSample(snap(t.toDouble(), 18.0).copy(odometerKm = odo), false)
        (61..159).forEach { done += at(it, 12_346.0) }
        // 60→61초 사다리꼴(36→18 km/h) 7.5 m + 98초 × 5 m = 497.5 m
        assertEquals(600.0 + 497.5, acc.totalMeters, 1e-6)
        // 160초 눈금 1 km는 믿고, 두 눈금 사이가 1 km가 되도록 502.5 m를 더한다.
        done += at(160, 12_347.0)
        assertEquals(1600.0, acc.totalMeters, 1e-6)
        assertEquals(1, done.size)
    }

    @Test
    fun `차속이 잠시 0에 멈춘 채 0_1 km 눈금이 오르면 믿는다`() {
        // 0.1 km 눈금(10초에 한 칸)으로 살아난 뒤, 21초부터 차속이 0으로 들어오는데 주행거리는 계속 오른다.
        val acc = SegmentAccumulator(SEOUL)
        feed(acc, 0..20) { 100.0 + 0.1 * (it / 10) } // 살아난 상태, 기준 100.2
        assertEquals(200.0, acc.totalMeters, 1e-6)
        (21..40).forEach { acc.onSample(snap(it.toDouble(), 0.0).copy(odometerKm = 100.0 + 0.1 * (it / 10)), false) }
        // 20→21초 사다리꼴 5 m 뒤로 속도 거리는 늘지 않지만, 30초와 40초 눈금(0.1 km씩)은 믿어 두 눈금 사이를 100 m로 맞춘다.
        assertEquals(400.0, acc.totalMeters, 1e-6)
    }

    @Test
    fun `샘플이 10초 넘게 끊긴 뒤에는 주행거리 차이를 쓰지 않는다`() {
        // 살아난 주행거리(기준 100.2 km)에서 [gapMs] 뒤에 100.7 km가 들어올 때 더해진 거리.
        fun addedAfterGap(gapMs: Long): Double {
            val acc = SegmentAccumulator(SEOUL)
            feed(acc, 0..20) { 100.0 + 0.1 * (it / 10) }
            val before = acc.totalMeters
            acc.onSample(snap(20.0, 36.0).copy(timeMs = T0 + 20_000 + gapMs, odometerKm = 100.7), false)
            return acc.totalMeters - before
        }
        // 10초 간격까지는 주행거리 차이(500 m)를 믿는다.
        assertEquals(500.0, addedAfterGap(10_000), 1e-6)
        // 그보다 길게 끊겼으면 속도로 센다(10초까지만 → 100 m).
        assertEquals(100.0, addedAfterGap(10_001), 1e-6)

        // 끊긴 뒤 들어온 값이 새 기준이 되고, 그 뒤 첫 눈금은 위상만 맞춘다(속도로 10 m).
        val acc = SegmentAccumulator(SEOUL)
        feed(acc, 0..20) { 100.0 + 0.1 * (it / 10) }
        acc.onSample(snap(60.0, 36.0).copy(odometerKm = 100.7), false)
        val before = acc.totalMeters
        acc.onSample(snap(61.0, 36.0).copy(odometerKm = 100.8), false)
        assertEquals(10.0, acc.totalMeters - before, 1e-6)
    }

    @Test
    fun `속도로 거리를 셀 때 샘플 간격은 10초까지만 센다`() {
        assertEquals(10_000L, Thresholds.MAX_DISTANCE_SAMPLE_GAP_MS)
        // 36 km/h로 [gapMs] 떨어진 두 샘플 사이의 거리.
        fun metersOver(gapMs: Long): Double {
            val acc = SegmentAccumulator(SEOUL)
            acc.onSample(snap(0.0, 36.0), false)
            acc.onSample(snap(0.0, 36.0).copy(timeMs = T0 + gapMs), false)
            return acc.totalMeters
        }
        assertEquals(90.0, metersOver(9_000), 1e-6)
        assertEquals(100.0, metersOver(10_000), 1e-6)
        assertEquals(100.0, metersOver(10_001), 1e-6)
        assertEquals(100.0, metersOver(60_000), 1e-6)
    }

    @Test
    fun `이벤트가 난 구간만 위험 구간이 된다`() {
        val acc = SegmentAccumulator(SEOUL)
        val done = (0..300).flatMap { acc.onSample(snap(it.toDouble(), 36.0), it == 150) }
        assertEquals(listOf(true, false, true), done.map { it.safe })
    }

    @Test
    fun `구간 경계를 넘는 샘플에서 난 이벤트는 그 샘플이 닫는 구간에만 붙는다`() {
        // 36 km/h(초당 10 m)면 t=100 샘플이 첫 구간을 닫는다.
        fun safeWithEventAt(t: Int): List<Boolean> {
            val acc = SegmentAccumulator(SEOUL)
            return (0..200).flatMap { acc.onSample(snap(it.toDouble(), 36.0), it == t) }.map { it.safe }
        }
        assertEquals(listOf(false, true), safeWithEventAt(99))
        assertEquals(listOf(false, true), safeWithEventAt(100))
        assertEquals(listOf(true, false), safeWithEventAt(101))
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
    fun `야간은 구간이 끝난 시각으로 22시부터 6시 전까지`() {
        // 36 km/h로 100초: 구간은 시작 100초 뒤 샘플에서 닫힌다.
        fun nightAtEnd(start: Long): Boolean {
            val acc = SegmentAccumulator(SEOUL)
            return (0..100).flatMap { acc.onSample(snap(it.toDouble(), 36.0, base = start), false) }.single().night
        }
        assertFalse(nightAtEnd(epochMs(2026, 11, 8, 21, 58, 19))) // 21:59:59에 끝남
        assertTrue(nightAtEnd(epochMs(2026, 11, 8, 21, 58, 20))) // 22:00:00
        assertTrue(nightAtEnd(epochMs(2026, 11, 8, 5, 58, 19))) // 05:59:59
        assertFalse(nightAtEnd(epochMs(2026, 11, 8, 5, 58, 20))) // 06:00:00
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
