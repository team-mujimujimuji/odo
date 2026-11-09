package com.mujimujimuji.odo.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** KeyStrip 셀 폭 규칙([keyCellWidths]): 평소엔 등분, 큰 글꼴에서 한 라벨이 등분 폭을 넘을 때만 내용 폭을 먼저 준다. */
class KeyCellWidthsTest {
    @Test
    fun equal_share_when_every_cell_fits() {
        // 7칸, 줄 폭 600 → 등분 85(+나머지 5px는 앞 칸부터 1씩)
        val widths = keyCellWidths(listOf(40, 60, 70, 85, 30, 50, 66), 600)
        assertEquals(listOf(86, 86, 86, 86, 86, 85, 85), widths)
        assertEquals(600, widths.sum())
    }

    @Test
    fun wide_label_gets_its_width_and_spare_is_shared() {
        // "탑케이스" @1.3처럼 한 칸만 등분(85)을 넘고 합은 줄 폭 안 → 넘는 칸은 내용 폭 이상, 합은 정확히 줄 폭
        val needs = listOf(60, 60, 60, 104, 60, 60, 60)
        val widths = keyCellWidths(needs, 600)
        assertEquals(600, widths.sum())
        needs.indices.forEach { assertEquals(true, widths[it] >= needs[it]) }
        assertEquals(104 + (600 - needs.sum()) / 7, widths[3])
    }

    @Test
    fun falls_back_to_equal_share_when_content_cannot_fit() {
        val widths = keyCellWidths(listOf(200, 200, 200, 200), 600)
        assertEquals(listOf(150, 150, 150, 150), widths)
    }

    @Test
    fun empty_strip_has_no_cells() {
        assertEquals(emptyList<Int>(), keyCellWidths(emptyList(), 600))
    }
}
