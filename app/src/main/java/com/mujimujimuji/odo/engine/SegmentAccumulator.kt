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
 * 거리는 늘 속도를 사다리꼴로 적분해 센다. 주행거리 신호가 눈금 단위로 오르면, 두 눈금 사이 거리가
 * 눈금 차이와 같아지도록 눈금이 오른 샘플에서 맞춘다(마지막 눈금 뒤 남은 거리도 속도로 센다).
 * 주행거리 값이 없어지거나, 줄거나, 한 샘플에 [Thresholds.MAX_ODOMETER_STEP_KM]보다 많이 늘거나,
 * 위상이 맞은 뒤 한 샘플에 "직전 눈금 뒤 속도로 센 거리 + [Thresholds.ODOMETER_STEP_SLACK_M]"보다 많이 늘거나(튐),
 * 샘플이 [Thresholds.MAX_DISTANCE_SAMPLE_GAP_MS]보다 길게 끊기면 그 샘플은 속도로 세고 기준을 다시 잡는다.
 * "직전 눈금 뒤 속도 거리 + 슬랙" 안쪽의 튐은 걸러지지 않는다(1 km 눈금 계기처럼 그 거리가 1 km 가까이 쌓이면
 * 2 km 가까운 튐도 통과한다). 튄 값이 다음 눈금 전에 돌아오면 최대 1 km, 다음 눈금까지 남으면(보정 등)
 * 최대 [Thresholds.MAX_ODOMETER_STEP_KM](2 km)가 더해지고 되돌리지 않아 가짜 구간이 최대 2개 닫힐 수 있다.
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
    // 기준을 잡은 뒤 눈금이 한 번 올라 위상이 맞았으면 true. 그 전의 첫 눈금은 속도로 세고 위상만 맞춘다
    // (앞서 속도로 센 거리를 모르니 눈금 차이를 더하면 겹친다). 에뮬레이터처럼 0에 멈춘 신호는 끝까지 false다.
    private var odometerLive = false
    // 직전 눈금(또는 기준) 뒤로 속도로 센 거리(m).
    private var sinceTickMeters = 0.0
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
        sinceTickMeters = 0.0
        index = 0
        totalMeters = 0.0
        resetFlags()
    }

    fun onSample(s: VehicleSnapshot, hasEvent: Boolean): List<Segment> {
        val delta = deltaMeters(s)
        lastTimeMs = s.timeMs
        lastSpeedKmh = s.speedKmh
        lastOdometerKm = s.odometerKm

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
        val gapMs = s.timeMs - prevTime
        val dtMs = gapMs.coerceIn(0L, Thresholds.MAX_DISTANCE_SAMPLE_GAP_MS)
        val avgKmh = (lastSpeedKmh + s.speedKmh) / 2.0
        val bySpeed = avgKmh / 3.6 * (dtMs / 1000.0)

        val odo = s.odometerKm
        val prevOdo = lastOdometerKm
        val stepKm = if (odo != null && prevOdo != null) odo - prevOdo else null
        if (stepKm == null || stepKm < 0.0 || stepKm > Thresholds.MAX_ODOMETER_STEP_KM ||
            (odometerLive && stepKm * 1000.0 > sinceTickMeters + Thresholds.ODOMETER_STEP_SLACK_M) ||
            gapMs > Thresholds.MAX_DISTANCE_SAMPLE_GAP_MS
        ) {
            // 값이 없거나, 줄었거나, 튀었거나(직전 눈금 뒤 속도 거리 + 슬랙보다 많이 늘었다), 샘플이 끊겼다:
            // 속도로 세고 이 값을 새 기준으로 삼는다.
            odometerLive = false
            sinceTickMeters = 0.0
            return bySpeed
        }
        if (stepKm == 0.0) {
            sinceTickMeters += bySpeed
            return bySpeed
        }
        // 눈금이 올랐다. 위상이 맞아 있으면 두 눈금 사이가 눈금 차이가 되도록 이번 샘플 거리를 맞춘다.
        val d = if (odometerLive) maxOf(0.0, stepKm * 1000.0 - sinceTickMeters) else bySpeed
        odometerLive = true
        sinceTickMeters = 0.0
        return d
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
