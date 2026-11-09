package com.mujimujimuji.odo.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 타일 캐시의 순수 부분(키 포장, 사다리꼴 컬링). 비트맵·스레드는 에뮬레이터 캡처로 확인한다. */
class TileCacheTest {
    @Test
    fun tileKey_distinguishes_scale_detail_and_indices() {
        val base = tileKey(6f, true, 3, 4)
        assertEquals(base, tileKey(6f, true, 3, 4))
        assertNotEquals(base, tileKey(6f, false, 3, 4))
        assertNotEquals(base, tileKey(6f, true, 4, 3))
        assertNotEquals(base, tileKey(20f, true, 3, 4))
        assertNotEquals(tileKey(6f, true, -1, -2), tileKey(6f, true, 0, 0))
        assertNotEquals(tileKey(6f, true, -1, 0), tileKey(6f, true, 0, -1))
    }

    private fun quad(x0: Float, y0: Float, x1: Float, y1: Float) = floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)

    /** 좌우 대칭 사다리꼴(extL = extR) 줄임 — 테스트 전용. 앱은 6인자 판을 쓴다(CorridorMap). */
    private fun quadMayOverlapTrapezoid(q: FloatArray, w: Float, h: Float, ext: Float, margin: Float): Boolean =
        quadMayOverlapTrapezoid(q, w, h, ext, ext, margin)

    @Test
    fun quad_outside_slanted_edge_is_culled_only_where_trapezoid_is_narrow() {
        val w = 600f
        val h = 500f
        val ext = 300f
        // 아랫변 근처(y 400~480)에서 빗변은 x=-60~-12 → x -250~-150 타일은 완전히 밖
        assertFalse(quadMayOverlapTrapezoid(quad(-250f, 400f, -150f, 480f), w, h, ext, 0f))
        // 윗변 근처(y 0~80)에서 빗변은 x=-300~-252 → 같은 x 범위라도 안쪽
        assertTrue(quadMayOverlapTrapezoid(quad(-250f, 0f, -150f, 80f), w, h, ext, 0f))
        // 오른쪽 빗변도 대칭
        assertFalse(quadMayOverlapTrapezoid(quad(w + 150f, 400f, w + 250f, 480f), w, h, ext, 0f))
        assertTrue(quadMayOverlapTrapezoid(quad(w + 150f, 0f, w + 250f, 80f), w, h, ext, 0f))
    }

    @Test
    fun asymmetric_trapezoid_spreads_each_side_by_its_own_amount() {
        val w = 600f
        val h = 500f
        // 기준점이 오른쪽(x 420)이면 왼쪽 빗변이 더 많이 벌어진다: extL = 420·s, extR = 180·s (s = 1)
        val extL = 420f
        val extR = 180f
        // 윗변 근처 x -400~-300: 왼쪽은 -420까지 열려 있어 유지
        assertTrue(quadMayOverlapTrapezoid(quad(-400f, 0f, -300f, 40f), w, h, extL, extR, 0f))
        // 같은 거리의 오른쪽(w+300~w+400)은 오른쪽 빗변(w+180) 밖이라 컬링
        assertFalse(quadMayOverlapTrapezoid(quad(w + 300f, 0f, w + 400f, 40f), w, h, extL, extR, 0f))
        // 대칭 오버로드는 양쪽을 같은 값으로 본다
        assertTrue(quadMayOverlapTrapezoid(quad(w + 300f, 0f, w + 400f, 40f), w, h, 420f, 0f))
    }

    @Test
    fun quad_fully_above_or_below_is_culled_but_straddling_is_kept() {
        val w = 600f
        val h = 500f
        assertFalse(quadMayOverlapTrapezoid(quad(100f, -300f, 200f, -40f), w, h, 300f, 16f))
        assertFalse(quadMayOverlapTrapezoid(quad(100f, h + 40f, 200f, h + 300f), w, h, 300f, 16f))
        assertTrue(quadMayOverlapTrapezoid(quad(100f, h - 40f, 200f, h + 300f), w, h, 300f, 16f))
        // 여백 안이면 유지
        assertTrue(quadMayOverlapTrapezoid(quad(100f, -300f, 200f, -10f), w, h, 300f, 16f))
        // 틸트 0(ext 0): 보통 사각형 교차
        assertTrue(quadMayOverlapTrapezoid(quad(-50f, -50f, 50f, 50f), w, h, 0f, 0f))
        assertFalse(quadMayOverlapTrapezoid(quad(-150f, 100f, -50f, 200f), w, h, 0f, 0f))
    }
}
