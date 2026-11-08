package com.mujimujimuji.odo.engine

import java.time.Instant
import java.time.ZoneId

/** 끝난 1 km 구간 하나. 보너스 플래그는 신호가 있을 때만 true가 된다. */
data class Segment(
    val index: Int,
    val endTimeMs: Long,
    val safe: Boolean,
    val night: Boolean,
    val wiper: Boolean,
    val allBelts: Boolean,
)

/**
 * 주행 거리를 1 km 구간으로 나눈다(기획서 안전 XP 표).
 * 주행거리 신호가 있으면 그 차이를, 없으면 속도를 사다리꼴로 적분한다.
 * - 이벤트가 난 샘플은 진행 중인 구간을 위험 구간으로 만든다.
 * - 야간: 구간이 끝난 시각 기준 22~06시
 * - 와이퍼: 구간 중 한 번이라도 켜졌으면
 * - 전 좌석 벨트: 구간의 모든 샘플에서 true였을 때만
 */
class SegmentAccumulator(private val zone: ZoneId) {
    private var meters = 0.0
    private var lastTimeMs: Long? = null
    private var lastSpeedKmh = 0.0
    private var lastOdometerKm: Double? = null
    // 주행거리 값이 한 번이라도 늘어난 뒤에만 믿는다. 에뮬레이터처럼 0에 멈춘 신호는 속도 적분으로 센다.
    private var odometerLive = false
    private var index = 0

    private var hadEvent = false
    private var wiperSeen = false
    private var beltsAllSamples = true

    /** 이번 트립에서 지금까지 달린 거리(m). 마지막 미완성 구간 포함. */
    var totalMeters = 0.0
        private set

    fun reset() {
        meters = 0.0
        lastTimeMs = null
        lastSpeedKmh = 0.0
        lastOdometerKm = null
        odometerLive = false
        index = 0
        totalMeters = 0.0
        resetFlags()
    }

    fun onSample(s: VehicleSnapshot, hasEvent: Boolean): List<Segment> {
        val delta = deltaMeters(s)
        lastTimeMs = s.timeMs
        lastSpeedKmh = s.speedKmh
        if (s.odometerKm != null) lastOdometerKm = s.odometerKm

        if (hasEvent) hadEvent = true
        if (s.wiperOn == true) wiperSeen = true
        if (s.allBelts != true) beltsAllSamples = false

        meters += delta
        totalMeters += delta

        val done = mutableListOf<Segment>()
        while (meters >= Thresholds.SEGMENT_METERS) {
            meters -= Thresholds.SEGMENT_METERS
            done += Segment(
                index = index++,
                endTimeMs = s.timeMs,
                safe = !hadEvent,
                night = isNight(s.timeMs),
                wiper = wiperSeen,
                allBelts = beltsAllSamples,
            )
            resetFlags()
            // 다음 구간도 지금 샘플의 상태에서 시작한다.
            if (s.wiperOn == true) wiperSeen = true
            if (s.allBelts != true) beltsAllSamples = false
        }
        return done
    }

    private fun deltaMeters(s: VehicleSnapshot): Double {
        val prevTime = lastTimeMs ?: return 0.0
        val prevOdo = lastOdometerKm
        if (s.odometerKm != null && prevOdo != null) {
            val d = (s.odometerKm - prevOdo) * 1000.0
            if (d > 0.0) odometerLive = true
            if (odometerLive && d >= 0.0) return d
        }
        val dtMs = (s.timeMs - prevTime).coerceIn(0L, Thresholds.MAX_DISTANCE_SAMPLE_GAP_MS)
        val avgKmh = (lastSpeedKmh + s.speedKmh) / 2.0
        return avgKmh / 3.6 * (dtMs / 1000.0)
    }

    private fun isNight(timeMs: Long): Boolean {
        val hour = Instant.ofEpochMilli(timeMs).atZone(zone).hour
        return hour >= Thresholds.NIGHT_START_HOUR || hour < Thresholds.NIGHT_END_HOUR
    }

    private fun resetFlags() {
        hadEvent = false
        wiperSeen = false
        beltsAllSamples = true
    }
}
