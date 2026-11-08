package com.mujimujimuji.odo.engine

enum class DriveState { PARKED, DRIVING }

/**
 * 주행 상태 머신(기획서 주행 상태 표).
 * - DRIVING: 기어 D 또는 R, 또는 속도 > 0
 * - PARKED: 기어 P이고 속도 0
 * - 그 밖(N·알 수 없음이면서 정지)은 현재 상태를 유지한다. 신호 대기 중 N으로 트립이 끊기지 않게.
 * 상태 전환은 같은 판정이 [debounceMs] 동안 이어질 때만 확정한다.
 *
 * 트립 판정만 디바운스한다. 화면 잠금은 [isMoving]으로 즉시 건다(절대 규칙 1).
 */
class DriveStateMachine(private val debounceMs: Long = Thresholds.STATE_DEBOUNCE_MS) {
    var state: DriveState = DriveState.PARKED
        private set

    private var pending: DriveState? = null
    private var pendingSinceMs = 0L

    /** 상태가 바뀌면 새 상태를, 아니면 null을 돌려준다. */
    fun onSample(timeMs: Long, gear: Gear, speedKmh: Double): DriveState? {
        val target = rawState(gear, speedKmh) ?: run {
            pending = null
            return null
        }
        if (target == state) {
            pending = null
            return null
        }
        if (pending != target) {
            pending = target
            pendingSinceMs = timeMs
        }
        if (timeMs - pendingSinceMs >= debounceMs) {
            state = target
            pending = null
            return target
        }
        return null
    }

    companion object {
        fun isMoving(gear: Gear, speedKmh: Double): Boolean =
            gear == Gear.DRIVE || gear == Gear.REVERSE || speedKmh > 0.0

        fun rawState(gear: Gear, speedKmh: Double): DriveState? = when {
            isMoving(gear, speedKmh) -> DriveState.DRIVING
            gear == Gear.PARK -> DriveState.PARKED
            else -> null
        }
    }
}
