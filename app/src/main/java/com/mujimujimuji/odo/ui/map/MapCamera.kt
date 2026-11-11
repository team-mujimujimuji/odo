package com.mujimujimuji.odo.ui.map

import androidx.compose.ui.geometry.Offset
import com.mujimujimuji.odo.engine.LatLng
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 지도 시점(설계서 §4.3). 값이 바뀔 때만 [CorridorMap]이 다시 그린다(data class 동등성).
 * - heading-up(글랜스): 6 m/dp, 틸트 58°, 카메라 중심(=미니카 접지점)이 지도 영역 가로 중앙·세로 65 %.
 *   지도 영역이 캔버스 전체가 아니면(글랜스: 왼쪽 패널 264dp를 뺀 오른쪽) [anchorX]로 가로 위치를 옮긴다. 원근 소실점도 그 위로 간다.
 * - north-up(홈·요약·정적): 틸트 0, 중심이 정중앙. 패널이 지도 가운데를 가리면(넓은 창의 트립 요약) [parkedAnchor]로 보이는 띠 가운데에 둔다.
 */
data class MapCamera(
    val center: LatLng,
    /** 진행방향, 북 기준 시계방향 도. north-up에서는 지도 회전에 쓰지 않고 미니카 회전에만 쓴다. */
    val headingDeg: Float,
    val metersPerDp: Float,
    val tiltDeg: Float,
    val northUp: Boolean,
    /** heading-up에서 카메라 중심의 가로 위치(캔버스 폭 비율). 656dp 글랜스는 (264 + 656) / 2 / 656 ≈ 0.70. */
    val anchorX: Float = 0.5f,
    /** north-up에서 카메라 중심의 위치(폭·높이 비율). heading-up에서는 쓰지 않는다. */
    val parkedAnchor: Offset = Offset(0.5f, 0.5f),
) {
    /** 카메라 중심이 놓이는 지도 영역 안 위치(폭·높이 비율). */
    val anchorFraction: Offset get() = if (northUp) parkedAnchor else Offset(anchorX, 0.65f)

    fun anchor(width: Float, height: Float): Offset = Offset(width * anchorFraction.x, height * anchorFraction.y)

    /** 틸트 0 기준으로 지점 p의 화면 위치(dp). heading-up이면 회전만 반영한다(요약 화면 끝점 미니카 배치용). */
    fun toScreenDp(data: CorridorData, p: LatLng, widthDp: Float, heightDp: Float): Offset {
        val dx = data.localX(p) - data.localX(center)
        val dy = data.localY(p) - data.localY(center)
        val h = if (northUp) 0.0 else Math.toRadians(headingDeg.toDouble())
        val rx = dx * cos(h) - dy * sin(h)
        val ry = dx * sin(h) + dy * cos(h)
        val a = anchor(widthDp, heightDp)
        return Offset(a.x + (rx / metersPerDp).toFloat(), a.y - (ry / metersPerDp).toFloat())
    }

    companion object {
        const val GLANCE_METERS_PER_DP = 6f
        const val PARKED_METERS_PER_DP = 20f
        const val GLANCE_TILT_DEG = 58f

        /** 코리도 밖의 중심. 위치를 모를 때 글랜스가 계기 모드(격자)로 그리게 한다. */
        val NOWHERE = LatLng(0.0, 0.0)

        fun glance(center: LatLng?, headingDeg: Float?, tiltDeg: Float = GLANCE_TILT_DEG, anchorX: Float = 0.5f): MapCamera =
            MapCamera(center ?: NOWHERE, headingDeg ?: 0f, GLANCE_METERS_PER_DP, tiltDeg, northUp = false, anchorX = anchorX)

        fun parked(center: LatLng): MapCamera = MapCamera(center, 0f, PARKED_METERS_PER_DP, 0f, northUp = true)

        /** 점들의 bbox가 padding 안에 들어가는 north-up 카메라(요약 화면). */
        fun fit(
            points: List<LatLng>,
            widthDp: Float,
            heightDp: Float,
            paddingDp: Float = 48f,
            headingDeg: Float = 0f,
            minMetersPerDp: Float = 2f,
        ): MapCamera {
            require(points.isNotEmpty()) { "fit needs ≥1 point" }
            var latMin = points[0].lat
            var latMax = latMin
            var lngMin = points[0].lng
            var lngMax = lngMin
            for (p in points) {
                if (p.lat < latMin) latMin = p.lat
                if (p.lat > latMax) latMax = p.lat
                if (p.lng < lngMin) lngMin = p.lng
                if (p.lng > lngMax) lngMax = p.lng
            }
            val center = LatLng((latMin + latMax) / 2, (lngMin + lngMax) / 2)
            val widthM = (lngMax - lngMin) * 111_320.0 * cos(Math.toRadians(center.lat))
            val heightM = (latMax - latMin) * 111_320.0
            val availW = max(1f, widthDp - 2 * paddingDp)
            val availH = max(1f, heightDp - 2 * paddingDp)
            val mpd = max(minMetersPerDp.toDouble(), max(widthM / availW, heightM / availH)).toFloat()
            return MapCamera(center, headingDeg, mpd, 0f, northUp = true)
        }
    }
}
