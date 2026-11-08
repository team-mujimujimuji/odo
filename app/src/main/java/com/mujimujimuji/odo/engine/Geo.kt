package com.mujimujimuji.odo.engine

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** 두 지점 사이의 대원 거리(m). */
    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /** a에서 본 b의 동쪽·북쪽 거리(m). 레이더 링처럼 수 km 안에서만 쓴다. */
    fun offsetMeters(a: LatLng, b: LatLng): Pair<Double, Double> {
        val east = Math.toRadians(b.lng - a.lng) * EARTH_RADIUS_M * cos(Math.toRadians(a.lat))
        val north = Math.toRadians(b.lat - a.lat) * EARTH_RADIUS_M
        return east to north
    }

    /** 위치를 격자 중심으로 반올림한다(로드 레이더 공개 위치용). */
    fun snapToGrid(p: LatLng, gridMeters: Double): LatLng {
        val latStep = Math.toDegrees(gridMeters / EARTH_RADIUS_M)
        val lngStep = latStep / cos(Math.toRadians(p.lat))
        return LatLng(
            (Math.floor(p.lat / latStep) + 0.5) * latStep,
            (Math.floor(p.lng / lngStep) + 0.5) * lngStep,
        )
    }
}

data class Geofence(val center: LatLng, val radiusMeters: Double) {
    fun contains(p: LatLng): Boolean = Geo.distanceMeters(center, p) <= radiusMeters
}
