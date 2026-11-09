package com.mujimujimuji.odo.drive

import com.mujimujimuji.odo.engine.Goal

/** 글랜스 게이지 한 개(설계서 §5.2): [progress] 0..1, [medalOpen]이면 끝의 메달을 채워 그린다. */
data class GlanceGauge(val progress: Float = 1f, val medalOpen: Boolean = true) {
    companion object {
        /** 엔진 목표 그대로: 목표가 없으면(열 수 있는 거리·도로 업적을 다 열었으면) 꽉 찬 게이지 + 채운 메달. */
        fun of(goal: Goal?): GlanceGauge = GlanceGauge(goal?.progress ?: 1f, medalOpen = goal == null)
    }
}

/**
 * 주행 중 글랜스 게이지가 뒤로 가지 않게 잡아 두는 래치(설계서 §6 "축적만", P3 "숫자는 뒤로 안 감").
 *
 * 엔진의 다음 목표([Goal])는 목표가 바뀌면 진행률이 낮은 값으로 넘어간다. 도로 업적의 시작 게이트를 지나면 그 도로 업적으로,
 * 도로 업적을 완주하면 다음 거리 업적으로 바뀐다(데모: 64→65 km에서 ≈1.0 → 0.35). 그래서 주행 중에는 엔진 값을 그대로
 * 보이지 않고 이 래치를 거친다.
 * - 게이지는 이번 주행에서 이미 보인 값보다 내려가지 않는다. 목표가 바뀌어도, 도로 업적의 직선거리 진행률이 굽은 길에서
 *   잠깐 줄어도 그대로다.
 * - 보이던 목표가 주행 중에 열리면 이번 주행이 끝날 때까지 꽉 찬 채로 둔다. 메달은 채우지 않는다. 열렸다는 소식은
 *   정차 후 요약이 전한다(P1 보상은 정차 후, 주행 중 알림 0).
 * - 정차하면(이동 없음 + 트립 끝) 래치를 풀고 엔진 값을 쓴다. 다음 주행은 새 목표의 실제 진행률에서 시작한다.
 *
 * 엔진 판정과 숫자는 건드리지 않고, 화면에 보낼 값만 고른다. 순수 Kotlin(JVM 테스트 대상).
 */
class GlanceGaugeLatch {
    private var active = false
    private var floor = 0f
    private var goalId: String? = null
    private var reached = false
    private var medalOpen = true

    fun reset() {
        active = false
        floor = 0f
        goalId = null
        reached = false
        medalOpen = true
    }

    /** 이번 샘플에서 열린 업적. 주행 중 지금 보이는 목표라면 이번 주행 동안 게이지를 꽉 찬 채로 둔다. */
    fun onUnlocked(achievementId: String) {
        if (active && achievementId == goalId) reached = true
    }

    /** [driving]: 이동 중이거나 트립이 진행 중(정차 디바운스 2초 포함). [goal]: 엔진의 지금 목표. */
    fun update(driving: Boolean, goal: Goal?): GlanceGauge {
        val raw = GlanceGauge.of(goal)
        if (!driving) {
            reset()
            return raw
        }
        if (!active) {
            active = true
            floor = raw.progress
            goalId = goal?.achievement?.id
            medalOpen = raw.medalOpen
            return raw
        }
        if (reached) return GlanceGauge(1f, medalOpen = medalOpen)
        floor = maxOf(floor, raw.progress)
        goalId = goal?.achievement?.id
        medalOpen = raw.medalOpen
        return GlanceGauge(floor, medalOpen)
    }
}
