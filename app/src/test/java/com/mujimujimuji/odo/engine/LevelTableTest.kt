package com.mujimujimuji.odo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `꾸미기 해금 레벨은 모두 기준 레벨`() {
        val anchorLevels = LevelTable.anchors.map { it.level }.toSet()
        assertEquals(16, LevelTable.cosmeticUnlocks.size)
        LevelTable.cosmeticUnlocks.forEach { (key, level) -> assertTrue("$key → Lv $level", level in anchorLevels) }
        // 기본값(sky·steel·white·차종)은 표에 없고 Lv1로 읽힌다.
        assertEquals(1, LevelTable.unlockLevelOf("color:sky"))
        assertEquals(1, LevelTable.unlockLevelOf("vehicle:scooter"))
        // 표의 문구와 맞물림: Lv5 색상 5종만, Lv10 투톤(의상)+트레일+다이아 휠·웜 램프, Lv20 데칼·루프박스, Lv30 한정 페인트 2종, Lv50 황금 번호판.
        assertEquals(
            listOf("color:cherry", "color:lavender", "color:mint", "color:pearl", "color:tangerine"),
            LevelTable.cosmeticUnlocks.filterValues { it == 5 }.keys.sorted(),
        )
        assertEquals(
            listOf("lamp:warm", "roof:twotone", "trail:ribbon", "wheel:dia"),
            LevelTable.cosmeticUnlocks.filterValues { it == 10 }.keys.sorted(),
        )
        assertEquals(10, LevelTable.unlockLevelOf("roof:twotone"))
        assertEquals(10, LevelTable.unlockLevelOf("trail:ribbon"))
        assertEquals(20, LevelTable.unlockLevelOf("decal:stripe"))
        assertEquals(20, LevelTable.unlockLevelOf("roof:box"))
        assertEquals(listOf("color:graphite", "color:sand"), LevelTable.cosmeticUnlocks.filterValues { it == 30 }.keys.sorted())
        assertEquals(listOf("plate:gold"), LevelTable.cosmeticUnlocks.filterValues { it == 50 }.keys.toList())
    }

    @Test
    fun `기준 레벨 표 스냅샷`() {
        val expected = listOf(
            Triple(1, 0, "시동"),
            Triple(5, 1_000, "초보 딱지 뗌"),
            Triple(10, 4_000, "로드메이트"),
            Triple(20, 15_000, "투어러"),
            Triple(30, 35_000, "베테랑"),
            Triple(50, 100_000, "마스터 드라이버"),
        )
        assertEquals(expected, LevelTable.anchors.map { Triple(it.level, it.xp, it.title) })
        assertEquals(
            listOf("기본 미니카", "미니카 색상 5종", "캐릭터 의상 1세트, 트레일 효과", "데칼, 루프박스", "한정 페인트", "황금 번호판(가상)"),
            LevelTable.anchors.map { it.unlock },
        )
        assertEquals(5, LevelTable.colorUnlockLevel)
        assertEquals(50, LevelTable.maxLevel)
    }
}
