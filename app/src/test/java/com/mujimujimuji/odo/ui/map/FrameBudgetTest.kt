package com.mujimujimuji.odo.ui.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 설계서 §4.3 성능 폴백: 그린 프레임 시간의 2 s 창 중앙값이 50 ms를 넘으면 그 트립 동안 틸트 0. */
class FrameBudgetTest {
    private fun FrameBudget.feed(fromMs: Long, toMs: Long, stepMs: Long, durMs: Long) {
        var t = fromMs
        while (t < toMs) {
            record(t * MS, durMs * MS)
            t += stepMs
        }
    }

    @Test
    fun `가벼운 프레임이면 틸트 유지`() {
        val b = FrameBudget()
        b.feed(0, 5_000, 16, 12)
        assertTrue(b.tiltAllowed)
    }

    @Test
    fun `2 s 창 중앙값이 50 ms를 넘으면 틸트를 끄고 되돌리지 않는다`() {
        val b = FrameBudget()
        b.feed(0, 2_600, 60, 55)
        assertFalse(b.tiltAllowed)
        b.feed(2_600, 8_000, 16, 5)
        assertFalse(b.tiltAllowed)
    }

    @Test
    fun `2 s를 다 보기 전에는 판단하지 않는다`() {
        val b = FrameBudget()
        b.feed(0, 1_900, 60, 55)
        assertTrue(b.tiltAllowed)
    }

    @Test
    fun `가끔 튀는 프레임은 중앙값에 묻힌다`() {
        val b = FrameBudget()
        var k = 0L
        while (k * 16 < 5_000) {
            b.record(k * 16 * MS, (if (k % 4 == 0L) 120L else 10L) * MS)
            k++
        }
        assertTrue(b.tiltAllowed)
    }

    @Test
    fun `1 s 넘게 그린 프레임이 없으면 창을 비우고 다시 2 s를 본다`() {
        val b = FrameBudget()
        b.feed(0, 1_500, 60, 55)
        // 3 s 동안 그린 프레임 없음(지도가 멈춤) → 느린 프레임이 합쳐 2 s를 넘어도 이어진 2 s가 아니다.
        b.feed(4_500, 6_000, 60, 55)
        assertTrue(b.tiltAllowed)
        b.feed(6_000, 7_000, 60, 55)
        assertFalse(b.tiltAllowed)
    }

    @Test
    fun `프레임이 250 ms를 넘게 느려 2 s에 8개가 안 차도 폴백한다`() {
        val b = FrameBudget()
        var t = 0L
        repeat(12) {
            b.record(t * MS, 1_200 * MS)
            t += 1_200
        }
        assertFalse(b.tiltAllowed)
    }

    @Test
    fun `글랜스 시작의 무거운 프레임 몇 개로는 폴백하지 않는다`() {
        val b = FrameBudget()
        b.record(0, 300 * MS)
        b.record(300 * MS, 300 * MS)
        b.record(600 * MS, 300 * MS)
        b.feed(900, 6_000, 16, 8)
        assertTrue(b.tiltAllowed)
    }

    @Test
    fun `프레임이 둘뿐인 창에서 1 s 넘는 프레임 하나로는 폴백하지 않는다`() {
        val b = FrameBudget()
        b.record(0, 20 * MS)
        b.record(900 * MS, 1_100 * MS)
        b.record(2_100 * MS, 20 * MS) // 창 안: 1,100 ms + 20 ms(합 1.12 s ≥ 1 s라 개수가 모자라도 판단한다)
        assertTrue(b.tiltAllowed)
    }

    @Test
    fun `프레임이 드물어도 느린 프레임 하나는 중앙값에 묻힌다`() {
        // 위치만 초당 1~10번 바뀌어 프레임이 드문 구간. 같은 단발 프레임이 프레임 빈도에 따라 폴백을 일으키면 안 된다.
        for (stepMs in longArrayOf(100, 500, 1_000)) {
            for (slowMs in longArrayOf(1_100, 1_500, 1_950)) {
                val b = FrameBudget()
                var t = 0L
                while (t < 2_000) {
                    b.record(t * MS, 20 * MS)
                    t += stepMs
                }
                b.record(t * MS, slowMs * MS)
                t += slowMs
                while (t < 8_000) {
                    b.record(t * MS, 20 * MS)
                    t += stepMs
                }
                assertTrue("간격 $stepMs ms, 느린 프레임 $slowMs ms", b.tiltAllowed)
            }
        }
    }

    @Test
    fun `프레임이 드물어도 모두 느리면 폴백한다`() {
        val b = FrameBudget()
        var t = 0L
        repeat(6) {
            b.record(t * MS, 600 * MS) // 1.1 s마다 600 ms: 2 s 창에 프레임 2개, 둘 다 느림
            t += 1_100
        }
        assertFalse(b.tiltAllowed)
    }

    private companion object {
        const val MS = 1_000_000L
    }
}
