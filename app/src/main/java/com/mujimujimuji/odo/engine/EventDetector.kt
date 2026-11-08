package com.mujimujimuji.odo.engine

/**
 * 위험 이벤트 판정(기획서 안전 XP 표). 같은 행동이 몇 초 이어져도 한 번으로 센다:
 * 조건이 풀렸다가 다시 걸려야 다음 이벤트가 된다.
 * 급회전은 1차 범위가 아니다.
 */
class EventDetector {
    private var prevTimeMs: Long? = null
    private var prevSpeedKmh = 0.0
    private var inAccel = false
    private var inDecel = false
    private var inSpeeding = false
    private var inNoBelt = false

    fun reset() {
        prevTimeMs = null
        prevSpeedKmh = 0.0
        inAccel = false
        inDecel = false
        inSpeeding = false
        inNoBelt = false
    }

    fun onSample(s: VehicleSnapshot): List<RiskEvent> {
        val events = mutableListOf<RiskEvent>()

        val prev = prevTimeMs
        if (prev != null) {
            val dtMs = s.timeMs - prev
            if (dtMs in 1..Thresholds.MAX_ACCEL_SAMPLE_GAP_MS) {
                val rate = (s.speedKmh - prevSpeedKmh) / (dtMs / 1000.0)
                val accel = rate >= Thresholds.HARSH_ACCEL_KMH_PER_S
                val decel = -rate >= Thresholds.HARSH_DECEL_KMH_PER_S
                if (accel && !inAccel) events += RiskEvent(RiskType.HARSH_ACCEL, s.timeMs)
                if (decel && !inDecel) events += RiskEvent(RiskType.HARSH_DECEL, s.timeMs)
                inAccel = accel
                inDecel = decel
            } else {
                inAccel = false
                inDecel = false
            }
        }
        prevTimeMs = s.timeMs
        prevSpeedKmh = s.speedKmh

        val limit = s.speedLimitKmh
        val speeding = limit != null && limit > 0 && s.speedKmh > limit + Thresholds.SPEEDING_MARGIN_KMH
        if (speeding && !inSpeeding) events += RiskEvent(RiskType.SPEEDING, s.timeMs)
        inSpeeding = speeding

        val noBelt = s.driverBelt == false && s.speedKmh > Thresholds.SEATBELT_MIN_SPEED_KMH
        if (noBelt && !inNoBelt) events += RiskEvent(RiskType.NO_SEATBELT, s.timeMs)
        inNoBelt = noBelt

        return events
    }
}
