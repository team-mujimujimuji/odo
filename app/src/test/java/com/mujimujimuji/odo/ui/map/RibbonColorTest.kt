package com.mujimujimuji.odo.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.mujimujimuji.odo.cosmetic.CosmeticCatalog
import com.mujimujimuji.odo.cosmetic.Slot
import com.mujimujimuji.odo.ui.theme.OdoDay
import com.mujimujimuji.odo.ui.theme.OdoNight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 지난 길 리본 색: 내 차 색 45 %, 단 도로 코어보다 어두운 페인트는 도로 위에서 묻히므로 `ink`로 바꾼다. */
class RibbonColorTest {
    private val themes = listOf("야간" to OdoNight, "주간" to OdoDay)
    private val paints = CosmeticCatalog.bySlot(Slot.COLOR).map { it.id to Color(it.argb!!) }

    @Test
    fun `그래파이트 리본은 주야 모두 ink 45 %`() {
        val graphite = Color(CosmeticCatalog.item(Slot.COLOR, "graphite").argb!!)
        for ((name, theme) in themes) {
            assertEquals(name, theme.ink.copy(alpha = 0.45f), ribbonColor(graphite, theme))
        }
    }

    @Test
    fun `그래파이트 말고는 내 차 색 45 % 그대로`() {
        for ((name, theme) in themes) {
            for ((id, paint) in paints.filter { it.first != "graphite" } + ("amber" to theme.amber)) {
                assertEquals("$name/$id", paint.copy(alpha = 0.45f), ribbonColor(paint, theme))
            }
        }
    }

    @Test
    fun `리본 색은 도로 코어보다 어둡지 않다`() {
        for ((name, theme) in themes) {
            for ((id, paint) in paints + ("amber" to theme.amber)) {
                val ribbon = ribbonColor(paint, theme).copy(alpha = 1f)
                assertTrue("$name/$id", ribbon.luminance() >= theme.roadCore.luminance())
            }
        }
    }
}
