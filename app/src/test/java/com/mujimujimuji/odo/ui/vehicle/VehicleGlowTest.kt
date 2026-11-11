package com.mujimujimuji.odo.ui.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 밤 글로 세기([glowAlpha]): 지도 위 뷰(Navi·Top)는 줄이고 차고(Hero)는 그대로. 글로가 아닌 층은 1. */
class VehicleGlowTest {
    @Test
    fun `글랜스(Navi) 두 글로는 줄이고 전조등이 꼬리등보다 약하다`() {
        val head = glowAlpha(VehicleView.Navi, LayerKind.GlowHead)
        val tail = glowAlpha(VehicleView.Navi, LayerKind.GlowTail)
        assertTrue("Navi 전조등 $head", head > 0f && head < 1f)
        assertTrue("Navi 꼬리등 $tail", tail > 0f && tail < 1f)
        assertTrue("Navi 전조등 $head ≤ 꼬리등 $tail", head <= tail)
    }

    @Test
    fun `홈·요약(Top) 전조등은 줄인다`() {
        val head = glowAlpha(VehicleView.Top, LayerKind.GlowHead)
        assertTrue("Top 전조등 $head", head > 0f && head < 1f)
        val tail = glowAlpha(VehicleView.Top, LayerKind.GlowTail)
        assertTrue("Top 꼬리등 $tail", tail > 0f && tail <= 1f)
    }

    @Test
    fun `차고(Hero)는 그대로`() {
        assertEquals(1f, glowAlpha(VehicleView.Hero, LayerKind.GlowHead), 0f)
        assertEquals(1f, glowAlpha(VehicleView.Hero, LayerKind.GlowTail), 0f)
    }

    @Test
    fun `글로가 아닌 층은 1`() {
        for (view in VehicleView.entries) {
            for (kind in LayerKind.entries) {
                if (kind == LayerKind.GlowHead || kind == LayerKind.GlowTail) continue
                assertEquals("$view $kind", 1f, glowAlpha(view, kind), 0f)
            }
        }
    }
}
