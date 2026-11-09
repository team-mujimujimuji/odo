package com.mujimujimuji.odo.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class NightModeTest {
    @Test
    fun `18시 30분부터 06시 30분 전까지 야간`() {
        assertTrue(isNightAt(LocalTime.of(18, 30)))
        assertTrue(isNightAt(LocalTime.of(23, 59)))
        assertTrue(isNightAt(LocalTime.of(0, 0)))
        assertTrue(isNightAt(LocalTime.of(6, 29)))
    }

    @Test
    fun `06시 30분부터 18시 30분 전까지 주간`() {
        assertFalse(isNightAt(LocalTime.of(6, 30)))
        assertFalse(isNightAt(LocalTime.of(12, 0)))
        assertFalse(isNightAt(LocalTime.of(18, 29)))
    }
}
