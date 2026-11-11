package com.mujimujimuji.odo.drive

import com.mujimujimuji.odo.engine.DriveStateMachine
import com.mujimujimuji.odo.engine.Gear

/**
 * 주행 터치 잠금 판정(README '안전·프라이버시 원칙'). 잠금은 디바운스 없이 마지막 샘플로 바로 정한다.
 * - 기어 D/R 또는 속도 > 0: 잠금([DriveStateMachine.isMoving]).
 * - 시스템 주행 제한(AAOS UX Restrictions): 잠금.
 * - 트립 중 기어 N 정차(신호 대기 등): 잠금. 엔진은 N 정차에서 트립을 끝내지 않으므로 입력도 주행 중처럼 막는다.
 * - 기어 P: 엔진이 디바운스 동안 아직 트립 중이어도 바로 푼다.
 * - 기어 신호 없음(UNKNOWN) 정차: 묶지 않는다. 기어 신호가 죽은 차에서 잠금이 영영 안 풀리는 일을 막는다.
 *
 * 순수 Kotlin(JVM 테스트 대상).
 */
object DriveLock {
    /**
     * [gear]·[speedKmh]는 마지막 샘플 값, [tripActive]는 엔진이 트립 중(DRIVING)인지, [systemRestricted]는 시스템 주행 제한.
     */
    fun isLocked(gear: Gear, speedKmh: Double, tripActive: Boolean, systemRestricted: Boolean): Boolean =
        DriveStateMachine.isMoving(gear, speedKmh) ||
            systemRestricted ||
            (tripActive && gear == Gear.NEUTRAL)
}
