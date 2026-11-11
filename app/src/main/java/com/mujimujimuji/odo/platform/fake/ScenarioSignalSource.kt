package com.mujimujimuji.odo.platform.fake

import com.mujimujimuji.odo.engine.VehicleSnapshot
import com.mujimujimuji.odo.platform.SignalSource
import com.mujimujimuji.odo.sim.Scenario
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 시나리오 JSON을 시간에 맞춰 재생한다. 간격은 tickMs / speedup(실제 시간), 스냅샷 시각은 가상 시각.
 * 에뮬레이터에 속도·위치를 연속으로 넣을 방법이 마땅치 않아 시연 영상은 이 경로로 찍는다.
 *
 * 절대 시각표로 재생한다: k번째 스냅샷은 시작 + k × 간격에 낸다. `emit` 뒤에 간격만큼 `delay`하면 수집 쪽 처리 시간과
 * 깨어나는 지연이 매번 더해져 재생이 약 8 % 늦어졌다(66 km 데모가 그만큼 길어짐). 앱이 멈췄다 돌아오는 등 [MAX_LAG_NS]보다
 * 크게 밀리면 몰아서 따라잡지 않고 그 자리에서 시각표를 다시 잡는다. 판정은 스냅샷의 가상 시각만 보므로 내는 간격이 흔들려도
 * 결과는 같다.
 */
class ScenarioSignalSource(
    private val scenario: Scenario,
    private val startTimeMs: Long,
) : SignalSource {
    override val simulated = true

    override val snapshots: Flow<VehicleSnapshot> = flow {
        val periodNs = scenario.tickMs * NS_PER_MS / scenario.speedup
        var anchorNs = System.nanoTime()
        var k = 0L
        for (s in scenario.timeline(startTimeMs)) {
            emit(s)
            k++
            val now = System.nanoTime()
            var waitNs = anchorNs + k * periodNs - now
            if (waitNs < -MAX_LAG_NS) {
                anchorNs = now
                k = 0L
                waitNs = 0L
            }
            if (waitNs > 0L) delay((waitNs + NS_PER_MS / 2) / NS_PER_MS)
        }
    }

    private companion object {
        const val NS_PER_MS = 1_000_000L
        /** 시각표보다 이만큼(실제 1 s) 넘게 밀리면 따라잡지 않고 다시 잡는다. */
        const val MAX_LAG_NS = 1_000L * NS_PER_MS
    }
}
