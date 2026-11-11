package com.mujimujimuji.odo.ui.summary

import com.mujimujimuji.odo.engine.RiskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SummaryLinesTest {
    private val names = mapOf(
        RiskType.HARSH_ACCEL to "급가속",
        RiskType.HARSH_DECEL to "급감속",
        RiskType.SPEEDING to "과속",
        RiskType.NO_SEATBELT to "안전벨트 미착용",
    )

    @Test
    fun `위험 이벤트 줄은 RiskType 순서로 잇고 0회는 뺀다`() {
        val line = riskLine(mapOf(RiskType.NO_SEATBELT to 1, RiskType.SPEEDING to 0, RiskType.HARSH_DECEL to 2), names)
        assertEquals("급감속 2 · 안전벨트 미착용 1", line.replace(NBSP, ' '))
    }

    @Test
    fun `위험 이벤트 줄은 구분자 뒤에서만 꺾인다`() {
        val line = riskLine(mapOf(RiskType.HARSH_ACCEL to 1, RiskType.HARSH_DECEL to 1, RiskType.NO_SEATBELT to 1), names)
        // 보통 공백(줄을 바꿀 수 있는 자리)은 "·" 바로 뒤에만 있다. 항목 안("안전벨트 미착용 1")과 "·" 앞은 NBSP로 묶는다.
        assertEquals(listOf("급가속\u00A01\u00A0·", "급감속\u00A01\u00A0·", "안전벨트\u00A0미착용\u00A01"), line.split(' '))
    }

    @Test
    fun `위험 이벤트가 없으면 빈 줄이다`() {
        assertEquals("", riskLine(emptyMap(), names))
        assertEquals("", riskLine(mapOf(RiskType.HARSH_DECEL to 0), names))
    }

    @Test
    fun `급감속 한 번은 시연 화면 그대로 한 항목이다`() {
        assertEquals("급감속\u00A01", riskLine(mapOf(RiskType.HARSH_DECEL to 1), names))
    }

    @Test
    fun `상한과 연속 기록 보너스는 캡션 한 자리를 같이 쓴다`() {
        assertNull(xpNoteOf(capReached = false, streakBonusXp = 0))
        assertEquals(XpNote.Cap, xpNoteOf(capReached = true, streakBonusXp = 0))
        assertEquals(XpNote.Bonus(50), xpNoteOf(capReached = false, streakBonusXp = 50))
        assertEquals(XpNote.CapAndBonus(300), xpNoteOf(capReached = true, streakBonusXp = 300))
    }

    @Test
    fun `아래 캡션이 있으면 위험 이벤트 줄은 한 줄까지만`() {
        // 글꼴 1.3배에서 검증된 캡션 줄 수(제외·이벤트·상한 3줄)를 넘지 않게 한다.
        assertEquals(2, riskLineMaxLines(null))
        assertEquals(1, riskLineMaxLines(XpNote.Cap))
        assertEquals(1, riskLineMaxLines(XpNote.Bonus(50)))
        assertEquals(1, riskLineMaxLines(XpNote.CapAndBonus(50)))
    }

    private companion object {
        const val NBSP = '\u00A0'
    }
}
