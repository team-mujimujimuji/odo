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

    private companion object {
        const val MS = 1_000_000L
    }
}
