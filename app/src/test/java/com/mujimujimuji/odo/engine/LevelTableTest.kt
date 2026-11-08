package com.mujimujimuji.odo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LevelTableTest {

    @Test
    fun `기준 레벨의 누적 XP는 기획서 표와 같다`() {
        assertEquals(0, LevelTable.xpForLevel(1))
        assertEquals(1_000, LevelTable.xpForLevel(5))
        assertEquals(4_000, LevelTable.xpForLevel(10))
        assertEquals(15_000, LevelTable.xpForLevel(20))
        assertEquals(35_000, LevelTable.xpForLevel(30))
        assertEquals(100_000, LevelTable.xpForLevel(50))
    }

    @Test
    fun `중간 레벨은 선형 보간`() {
        assertEquals(250, LevelTable.xpForLevel(2))
        assertEquals(750, LevelTable.xpForLevel(4))
        assertEquals(1_600, LevelTable.xpForLevel(6))
        assertEquals(67_500, LevelTable.xpForLevel(40))
    }

    @Test
    fun `레벨 경계`() {
        assertEquals(1, LevelTable.levelForXp(0))
        assertEquals(1, LevelTable.levelForXp(249))
        assertEquals(2, LevelTable.levelForXp(250))
        assertEquals(4, LevelTable.levelForXp(820))
        assertEquals(4, LevelTable.levelForXp(999))
        assertEquals(5, LevelTable.levelForXp(1_000))
        assertEquals(5, LevelTable.levelForXp(1_120))
        assertEquals(10, LevelTable.levelForXp(4_000))
        assertEquals(50, LevelTable.levelForXp(100_000))
        assertEquals(50, LevelTable.levelForXp(1_000_000))
    }

    @Test
    fun `칭호와 진행률`() {
        assertEquals("시동", LevelTable.titleForLevel(4))
        assertEquals("초보 딱지 뗌", LevelTable.titleForLevel(5))
        assertEquals("초보 딱지 뗌", LevelTable.titleForLevel(9))
        assertEquals(750 to 1_000, LevelTable.progress(820))
        assertNull(LevelTable.progress(100_000))
        assertEquals(5, LevelTable.nextUnlock(4)?.level)
    }
}
