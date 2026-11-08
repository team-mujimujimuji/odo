package com.mujimujimuji.odo.engine

/**
 * 세이프티 스코어(레벨과 별개, 기획서 안전 XP 표). 최근 30일 100 km당 위험 이벤트 수를 0~100점으로 환산한다.
 * 100 − 10 × (100 km당 이벤트 수). 주행 중에는 표시하지 않는다.
 */
object SafetyScore {
    /** 최근 30일 주행 거리가 없으면 null. */
    fun score(eventCount: Int, distanceKm: Double): Int? {
        if (distanceKm <= 0.0) return null
        val per100Km = eventCount / distanceKm * 100.0
        val raw = 100.0 - Thresholds.SAFETY_SCORE_POINTS_PER_EVENT_PER_100KM * per100Km
        return Math.round(raw).toInt().coerceIn(0, 100)
    }
}

/**
 * TTS 횟수 제한(README '안전·프라이버시 원칙'): 주행 중에는 10분 안에 최대 2회. 정차 중에는 제한하지 않는다.
 * 시각은 신호 샘플 시각을 쓴다(시나리오 재생 배속에서도 같은 규칙).
 */
class VoicePolicy {
    private val drivingUtterances = ArrayDeque<Long>()

    fun tryAcquire(timeMs: Long, driving: Boolean): Boolean {
        if (!driving) return true
        while (drivingUtterances.isNotEmpty() &&
            timeMs - drivingUtterances.first() >= Thresholds.TTS_DRIVING_WINDOW_MS
        ) {
            drivingUtterances.removeFirst()
        }
        if (drivingUtterances.size >= Thresholds.TTS_DRIVING_MAX_IN_WINDOW) return false
        drivingUtterances.addLast(timeMs)
        return true
    }
}
