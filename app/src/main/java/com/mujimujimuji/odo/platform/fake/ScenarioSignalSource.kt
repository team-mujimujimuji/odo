package com.mujimujimuji.odo.platform.fake

import com.mujimujimuji.odo.engine.VehicleSnapshot
import com.mujimujimuji.odo.platform.SignalSource
import com.mujimujimuji.odo.sim.Scenario
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 시나리오 JSON을 시간에 맞춰 재생한다. 실제 대기는 tickMs / speedup, 스냅샷 시각은 가상 시각.
 * 에뮬레이터에 속도·위치를 연속으로 넣을 방법이 마땅치 않아 시연 영상은 이 경로로 찍는다(README '구조').
 */
class ScenarioSignalSource(
    private val scenario: Scenario,
    private val startTimeMs: Long,
) : SignalSource {
    override val simulated = true

    override val snapshots: Flow<VehicleSnapshot> = flow {
        val frameDelay = scenario.tickMs / scenario.speedup
        for (s in scenario.timeline(startTimeMs)) {
            emit(s)
            delay(frameDelay)
        }
    }
}
