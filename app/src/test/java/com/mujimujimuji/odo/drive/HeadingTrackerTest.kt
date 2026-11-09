package com.mujimujimuji.odo.drive

import com.mujimujimuji.odo.engine.Gear
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.engine.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** 설계서 §4.4: 위치 샘플 → 저역통과(α 0.3) 진행방향, 3 m 미만 무시, 50 m 간격 지난 길. */
class HeadingTrackerTest {
    private val start = LatLng(37.24, 127.57)

    private fun snap(t: Long, p: LatLng?) = VehicleSnapshot(timeMs = t, gear = Gear.DRIVE, speedKmh = 60.0, location = p)

    /** [from]에서 방위 [bearingDeg]로 [m] m 간 점(평면 근사). */
    private fun move(from: LatLng, bearingDeg: Double, m: Double): LatLng {
        val b = Math.toRadians(bearingDeg)
        val lat = from.lat + m * cos(b) / 111_320.0
        val lng = from.lng + m * sin(b) / (111_320.0 * cos(Math.toRadians(from.lat)))
        return LatLng(lat, lng)
    }

    @Test
    fun `첫 샘플은 위치만, 동쪽 20 m 이동은 90도`() {
        val t = HeadingTracker()
        t.update(snap(0, start))
        assertEquals(start, t.position)
        assertNull(t.headingDeg)
        val p = move(start, 90.0, 20.0)
        t.update(snap(1_000, p))
        assertEquals(p, t.position)
        assertEquals(90f, t.headingDeg!!, 0.5f)
    }

    @Test
    fun `3 m 미만 이동은 방향을 바꾸지 않는다`() {
        val t = HeadingTracker()
        t.update(snap(0, start))
        val p = move(start, 90.0, 20.0)
        t.update(snap(1_000, p))
        val before = t.headingDeg
        t.update(snap(2_000, move(p, 0.0, 1.0)))
        assertEquals(before, t.headingDeg)
    }

    @Test
    fun `북쪽을 가로지르는 저역통과`() {
        val t = HeadingTracker()
        t.update(snap(0, start))
        val p1 = move(start, 350.0, 20.0)
        t.update(snap(1_000, p1))
        assertEquals(350f, t.headingDeg!!, 0.5f)
        t.update(snap(2_000, move(p1, 10.0, 20.0)))
        // 350 → 10 은 +20°, α 0.3 이면 356. (170 쪽으로 돌면 안 된다.)
        assertEquals(356f, t.headingDeg!!, 0.5f)
    }

    @Test
    fun `위치 없는 샘플은 무시`() {
        val t = HeadingTracker()
        t.update(snap(0, null))
        assertNull(t.position)
        assertTrue(t.path.isEmpty())
    }

    @Test
    fun `지난 길은 50 m 간격`() {
        val t = HeadingTracker()
        var p = start
        for (i in 0..20) {
            t.update(snap(i * 1_000L, p))
            p = move(p, 0.0, 12.0)
        }
        // 0, 60, 120, 180, 240 m
        assertEquals(5, t.path.size)
        assertEquals(start, t.path.first())
    }

    @Test
    fun `점이 상한을 넘으면 솎아 낸다`() {
        val t = HeadingTracker(pathStepM = 10.0, maxPathPoints = 4)
        var p = start
        for (i in 0..30) {
            t.update(snap(i * 1_000L, p))
            assertTrue(t.path.size <= 5)
            p = move(p, 0.0, 10.0)
        }
        assertEquals(start, t.path.first())
        assertTrue(t.path.size >= 2)
    }

    @Test
    fun `reset 은 모두 비운다`() {
        val t = HeadingTracker()
        t.update(snap(0, start))
        t.update(snap(1_000, move(start, 90.0, 20.0)))
        t.reset()
        assertNull(t.position)
        assertNull(t.headingDeg)
        assertTrue(t.path.isEmpty())
        t.update(snap(2_000, start))
        assertEquals(1, t.path.size)
        assertNull(t.headingDeg)
    }
}
