package com.mujimujimuji.odo.drive

import com.mujimujimuji.odo.engine.DriveState
import com.mujimujimuji.odo.engine.DriveStateMachine
import com.mujimujimuji.odo.engine.Gear
import com.mujimujimuji.odo.engine.Thresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 주행 터치 잠금(README '안전·프라이버시 원칙').
 * 기어 D/R 또는 속도 > 0, 시스템 주행 제한, 그리고 트립 중 기어 N 정차면 잠근다.
 * 기어 P는 디바운스를 기다리지 않고 바로 풀고, 기어 신호가 없으면(UNKNOWN) 정차를 묶지 않는다.
 */
class DriveLockTest {

    private fun locked(gear: Gear, speedKmh: Double, tripActive: Boolean, systemRestricted: Boolean = false) =
        DriveLock.isLocked(gear, speedKmh, tripActive, systemRestricted)

    @Test
    fun `트립 중 기어 N으로 멈추면 잠금을 유지한다`() {
        assertTrue(locked(Gear.NEUTRAL, 0.0, tripActive = true))
    }

    @Test
    fun `트립이 없으면 기어 N 정차는 풀린다`() {
        assertFalse(locked(Gear.NEUTRAL, 0.0, tripActive = false))
    }

    @Test
    fun `기어 P 정차는 트립 중이어도 바로 풀린다`() {
        assertFalse(locked(Gear.PARK, 0.0, tripActive = true))
        assertFalse(locked(Gear.PARK, 0.0, tripActive = false))
    }

    @Test
    fun `기어 신호가 없으면 정차를 묶지 않는다`() {
        assertFalse(locked(Gear.UNKNOWN, 0.0, tripActive = true))
        assertFalse(locked(Gear.UNKNOWN, 0.0, tripActive = false))
    }

    @Test
    fun `기어 D·R 또는 속도가 있으면 잠근다`() {
        for (trip in listOf(true, false)) {
            assertTrue(locked(Gear.DRIVE, 0.0, trip))
            assertTrue(locked(Gear.REVERSE, 0.0, trip))
            assertTrue(locked(Gear.PARK, 0.5, trip))
            assertTrue(locked(Gear.NEUTRAL, 3.0, trip))
            assertTrue(locked(Gear.UNKNOWN, 12.0, trip))
        }
    }

    @Test
    fun `시스템 주행 제한이면 잠근다`() {
        for (gear in Gear.entries) {
            for (trip in listOf(true, false)) {
                assertTrue("$gear trip=$trip", locked(gear, 0.0, trip, systemRestricted = true))
            }
        }
    }

    /** 엔진 상태 머신과 함께: N 정차는 트립을 끝내지 않으니 잠긴 채, P는 디바운스 중(엔진은 아직 DRIVING)에도 바로 풀린다. */
    @Test
    fun `상태 머신과 함께 - N 정차는 잠긴 채, P는 디바운스 중에도 바로 풀린다`() {
        val sm = DriveStateMachine()
        val debounce = Thresholds.STATE_DEBOUNCE_MS
        fun step(t: Long, gear: Gear, speed: Double): Boolean {
            sm.onSample(t, gear, speed)
            return DriveLock.isLocked(gear, speed, tripActive = sm.state == DriveState.DRIVING, systemRestricted = false)
        }
        // 출발: 디바운스가 끝나기 전에도 D면 잠긴다.
        assertTrue(step(0, Gear.DRIVE, 10.0))
        assertTrue(step(debounce, Gear.DRIVE, 30.0))
        assertEquals(DriveState.DRIVING, sm.state)
        // 신호 대기: N, 속도 0. 트립은 이어지고 잠금도 유지된다.
        assertTrue(step(debounce + 1_000, Gear.NEUTRAL, 0.0))
        assertTrue(step(debounce + 60_000, Gear.NEUTRAL, 0.0))
        assertEquals(DriveState.DRIVING, sm.state)
        // 기어 P: 엔진은 디바운스 동안 아직 DRIVING이지만 잠금은 바로 풀린다.
        val parkAt = debounce + 61_000
        assertFalse(step(parkAt, Gear.PARK, 0.0))
        assertEquals(DriveState.DRIVING, sm.state)
        assertFalse(step(parkAt + debounce, Gear.PARK, 0.0))
        assertEquals(DriveState.PARKED, sm.state)
        // 트립이 끝난 뒤의 N 정차는 묶지 않는다.
        assertFalse(step(parkAt + debounce + 1_000, Gear.NEUTRAL, 0.0))
    }
}
