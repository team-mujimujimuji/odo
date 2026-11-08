package com.mujimujimuji.odo.engine

/**
 * 레벨 표(기획서 레벨 표). 기준 레벨 사이는 누적 XP를 선형 보간한다.
 * 중간 레벨의 칭호는 직전 기준 레벨의 칭호를 쓴다.
 */
object LevelTable {
    data class Anchor(val level: Int, val title: String, val xp: Int, val unlock: String)

    val anchors = listOf(
        Anchor(1, "시동", 0, "기본 미니카"),
        Anchor(5, "초보 딱지 뗌", 1_000, "미니카 색상 5종"),
        Anchor(10, "로드메이트", 4_000, "캐릭터 의상 1세트, 트레일 효과"),
        Anchor(20, "투어러", 15_000, "데칼, 루프박스"),
        Anchor(30, "베테랑", 35_000, "한정 페인트"),
        Anchor(50, "마스터 드라이버", 100_000, "황금 번호판(가상)"),
    )

    val maxLevel: Int = anchors.last().level

    /** 레벨에 도달하는 데 필요한 누적 XP. */
    fun xpForLevel(level: Int): Int {
        require(level >= 1) { "level must be >= 1" }
        if (level >= maxLevel) return anchors.last().xp
        val upper = anchors.first { it.level >= level }
        if (upper.level == level) return upper.xp
        val lower = anchors.last { it.level < level }
        val span = upper.level - lower.level
        val step = (upper.xp - lower.xp).toDouble() / span
        return lower.xp + Math.round(step * (level - lower.level)).toInt()
    }

    fun levelForXp(totalXp: Int): Int {
        var level = 1
        while (level < maxLevel && totalXp >= xpForLevel(level + 1)) level++
        return level
    }

    fun titleForLevel(level: Int): String = anchors.last { it.level <= level }.title

    /** 다음 레벨까지 진행 상황(현재 레벨 시작 XP, 다음 레벨 XP). 최고 레벨이면 null. */
    fun progress(totalXp: Int): Pair<Int, Int>? {
        val level = levelForXp(totalXp)
        if (level >= maxLevel) return null
        return xpForLevel(level) to xpForLevel(level + 1)
    }

    /** 다음 해금이 있는 기준 레벨. */
    fun nextUnlock(level: Int): Anchor? = anchors.firstOrNull { it.level > level }
}
