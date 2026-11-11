package com.mujimujimuji.odo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveStateMachineTest {

    @Test
    fun `기어 D가 2초 이어져야 주행으로 바뀐다`() {
        val m = DriveStateMachine()
        assertNull(m.onSample(0, Gear.DRIVE, 0.0))
        assertNull(m.onSample(1_999, Gear.DRIVE, 0.0))
        assertEquals(DriveState.DRIVING, m.onSample(2_000, Gear.DRIVE, 0.0))
        assertEquals(DriveState.DRIVING, m.state)
    }

    @Test
    fun `2초 안에 되돌아가면 상태가 바뀌지 않는다`() {
        val m = DriveStateMachine()
        m.onSample(0, Gear.DRIVE, 0.0)
        m.onSample(1_000, Gear.PARK, 0.0)
        assertNull(m.onSample(2_500, Gear.DRIVE, 0.0))
        assertEquals(DriveState.PARKED, m.state)
        assertEquals(DriveState.DRIVING, m.onSample(4_500, Gear.DRIVE, 0.0))
    }

    @Test
    fun `속도가 있으면 기어와 무관하게 주행이다`() {
        val m = DriveStateMachine()
        m.onSample(0, Gear.UNKNOWN, 5.0)
        assertEquals(DriveState.DRIVING, m.onSample(2_000, Gear.UNKNOWN, 5.0))
    }

    @Test
    fun `정차 중 중립은 주행 상태를 유지한다`() {
        val m = DriveStateMachine()
        m.onSample(0, Gear.DRIVE, 30.0)
        m.onSample(2_000, Gear.DRIVE, 30.0)
        assertNull(m.onSample(10_000, Gear.NEUTRAL, 0.0))
        assertNull(m.onSample(20_000, Gear.NEUTRAL, 0.0))
        assertEquals(DriveState.DRIVING, m.state)
        m.onSample(21_000, Gear.PARK, 0.0)
        assertEquals(DriveState.PARKED, m.onSample(23_000, Gear.PARK, 0.0))
    }

    @Test
    fun `주행 중 기어 P가 2초를 못 채우고 D로 돌아오면 주행을 유지한다`() {
        val m = DriveStateMachine()
        m.onSample(0, Gear.DRIVE, 0.0)
        assertEquals(DriveState.DRIVING, m.onSample(2_000, Gear.DRIVE, 0.0))
        assertNull(m.onSample(10_000, Gear.PARK, 0.0))
        assertNull(m.onSample(11_000, Gear.PARK, 0.0))
        assertNull(m.onSample(11_500, Gear.DRIVE, 0.0))
        assertEquals(DriveState.DRIVING, m.state)
        // 다시 P로 가면 2초를 처음부터 잰다.
        assertNull(m.onSample(12_000, Gear.PARK, 0.0))
        assertNull(m.onSample(13_999, Gear.PARK, 0.0))
        assertEquals(DriveState.PARKED, m.onSample(14_000, Gear.PARK, 0.0))
    }

    @Test
    fun `후진 기어가 2초 이어지면 주행으로 바뀐다`() {
        val m = DriveStateMachine()
        assertNull(m.onSample(0, Gear.REVERSE, 0.0))
        assertNull(m.onSample(1_999, Gear.REVERSE, 0.0))
        assertEquals(DriveState.DRIVING, m.onSample(2_000, Gear.REVERSE, 0.0))
    }

    @Test
    fun `화면 잠금 판정은 디바운스 없이 즉시다`() {
        assertTrue(DriveStateMachine.isMoving(Gear.REVERSE, 0.0))
        assertTrue(DriveStateMachine.isMoving(Gear.PARK, 0.1))
        assertFalse(DriveStateMachine.isMoving(Gear.PARK, 0.0))
    }
}
