package com.mujimujimuji.odo.ui.radar

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.mujimujimuji.odo.ui.theme.Road
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

private const val RADIUS_M = 2_000.0
private const val REFRESH_MS = 3_000L

/**
 * 지도 타일 없는 레이더 링(기획서 로드 레이더 표). 1차는 모의 미니카 2대.
 * 주행 중에는 3초에 한 번 위치만 바꾸고(움직이는 연출 없음) 상대는 점으로만 그린다.
 */
@Composable
fun RadarRing(modifier: Modifier = Modifier) {
    val tick by produceState(System.currentTimeMillis() / REFRESH_MS) {
        while (true) {
            delay(REFRESH_MS)
            value = System.currentTimeMillis() / REFRESH_MS
        }
    }
    val cars = mockCars(tick)
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = center
        drawCircle(Road.Shoulder, r, c)
        drawCircle(Road.Seam, r, c, style = Stroke(width = 2f))
        drawCircle(Road.Seam, r / 2f, c, style = Stroke(width = 2f))
        for ((dx, dy) in cars) {
            val p = Offset(c.x + (dx / RADIUS_M * r).toFloat(), c.y - (dy / RADIUS_M * r).toFloat())
            drawCircle(Road.Lane, r * 0.045f, p)
        }
        // 내 차: 진행 방향(위)을 가리키는 노란 화살촉
        val s = r * 0.09f
        val me = Path().apply {
            moveTo(c.x, c.y - s)
            lineTo(c.x + s * 0.75f, c.y + s * 0.8f)
            lineTo(c.x, c.y + s * 0.4f)
            lineTo(c.x - s * 0.75f, c.y + s * 0.8f)
            close()
        }
        drawPath(me, Road.CenterLine)
    }
}

/** 모의 미니카 상대 위치(m, 동·북). 실제 presence 서버는 2차. */
private fun mockCars(tick: Long): List<Pair<Double, Double>> {
    val t = tick.toDouble()
    val a1 = Math.toRadians(35.0 + t * 4)
    val r1 = 650.0 + 180 * sin(t * 0.3)
    val a2 = Math.toRadians(210.0 - t * 3)
    val r2 = 1_450.0
    return listOf(r1 * sin(a1) to r1 * cos(a1), r2 * sin(a2) to r2 * cos(a2))
}
