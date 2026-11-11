package com.mujimujimuji.odo.engine

import kotlin.math.abs

/**
 * 위험 이벤트 판정(기획서 안전 XP 표). 같은 행동이 몇 초 이어져도 한 번으로 센다:
 * 조건이 풀렸다가 다시 걸려야 다음 이벤트가 된다.
 * 급회전은 1차 범위가 아니다.
 *
 * 급가속·급감속 기준은 "초당 km/h"라서 지금 속도와 1초 전 속도의 차로 본다.
 * 실제 차속 신호는 초당 10번쯤 들어오고 값이 계단처럼 바뀌기도 한다. 이웃한 두 샘플의 차를
 * 그 간격으로 나누면 완만한 가감속도 급가속·급감속으로 읽힌다.
 *
 * 속도 기록은 차량이 차속을 만든 시각([VehicleSnapshot.speedTimeMs])에 둔다. 실제 신호 경로는 1초 틱과 기어 변경 때
 * 마지막 차속을 새 스냅샷 시각에 다시 싣는데, 그 묵은 값을 스냅샷 시각에 두면 1초 창이 묵은 만큼 짧아져 약한 가감속도 걸린다.
 * 새 차속이 늦게 와도(차량 서비스·스레드 사정) 받은 시각이 아니라 만든 시각에 둔다. 같은 행동인지도 이 시각으로 가린다.
 */
class EventDetector {
    private class SpeedPoint(val timeMs: Long, var speedKmh: Double)

    /** 1초 창의 속도 변화(초당 km/h). [rise]로 급가속, [drop]으로 급감속을 본다. 계단이 아니면 둘은 부호만 다르다. */
    private class WindowChange(val rise: Double, val drop: Double)

    /**
     * 최근 속도 기록. 1초 창 시작보다 [Thresholds.MAX_SPEED_SAMPLE_AGE_MS] 이른 시각을 사이에 둔 두 샘플부터 지금 샘플까지 남긴다
     * (늦게 온 차속의 1초 창도 잴 수 있게).
     */
    private val history = ArrayDeque<SpeedPoint>()

    /** 바로 앞 스냅샷 시각. 벽시계가 거꾸로 갔는지는 차속 시각이 아니라 이것으로 본다. */
    private var lastSnapshotMs: Long? = null

    /**
     * 바로 앞 스냅샷에 실린 차속 시각. 다음 스냅샷의 차속 시각이 이와 같으면 틱이나 기어 변경이 예전 차속을 다시 실은 것이다.
     * 입력 흐름의 성질이라 속도 기록을 지울 때도 남긴다(지우면 끊김 뒤 첫 틱이 예전 차속 시각에 놓여 정속 확인이 한 번 빠진다).
     */
    private var lastSpeedTimeMs: Long? = null
    private var inAccel = false
    private var inDecel = false
    private var lastAccelMs: Long? = null
    private var lastDecelMs: Long? = null
    private var inSpeeding = false
    private var inNoBelt = false

    /**
     * 과속·벨트 미착용이 지금 이어지고 있는지. 이벤트는 시작할 때 한 번만 세지만,
     * 그 상태로 달린 1 km 구간은 모두 안전 구간이 아니다.
     */
    val ongoing: Boolean get() = inSpeeding || inNoBelt

    /**
     * 트립이 확정될 때 부른다. 디바운스 2초 안에 이미 시작된 위반도 그 트립의 이벤트로 한 번 세도록
     * "이미 셌다"는 표시만 지운다. 속도 기록은 남긴다(트립 첫 샘플의 급가속·급감속 판정에 필요하다).
     */
    fun onTripStart() {
        inAccel = false
        inDecel = false
        lastAccelMs = null
        lastDecelMs = null
        inSpeeding = false
        inNoBelt = false
    }

    fun onSample(s: VehicleSnapshot): List<RiskEvent> {
        val events = mutableListOf<RiskEvent>()

        val t = speedTime(s)
        val change = speedChangeOverWindow(s, t)
        val accel = change != null && change.rise >= Thresholds.HARSH_ACCEL_KMH_PER_S
        val decel = change != null && change.drop >= Thresholds.HARSH_DECEL_KMH_PER_S
        if (accel) {
            if (!inAccel && startsNewEvent(lastAccelMs, t)) events += RiskEvent(RiskType.HARSH_ACCEL, s.timeMs)
            lastAccelMs = t
        }
        if (decel) {
            if (!inDecel && startsNewEvent(lastDecelMs, t)) events += RiskEvent(RiskType.HARSH_DECEL, s.timeMs)
            lastDecelMs = t
        }
        inAccel = accel
        inDecel = decel

        val limit = s.speedLimitKmh
        val speeding = limit != null && limit > 0 && s.speedKmh > limit + Thresholds.SPEEDING_MARGIN_KMH
        if (speeding && !inSpeeding) events += RiskEvent(RiskType.SPEEDING, s.timeMs)
        inSpeeding = speeding

        val noBelt = s.driverBelt == false && s.speedKmh > Thresholds.SEATBELT_MIN_SPEED_KMH
        if (noBelt && !inNoBelt) events += RiskEvent(RiskType.NO_SEATBELT, s.timeMs)
        inNoBelt = noBelt

        return events
    }

    /**
     * 지금 속도와 [Thresholds.ACCEL_WINDOW_MS] 전 속도의 차를 초당 km/h로 돌려준다. 기록이 아직 그만큼 쌓이지 않았으면 null.
     * "지금"은 스냅샷 시각이 아니라 이 차속을 기록에 둘 시각 [t]([speedTime])이다.
     *
     * 1초 전 속도는 앞뒤 두 샘플 사이를 직선으로 이어 구한다. 그래서 샘플이 1초보다 드물게 오면 두 샘플의 차를 간격으로 나눈 값과 같다.
     * 다만 1초 전 시각이 1초보다 짧은 가파른 샘플 사이(초당 [Thresholds.SPEED_STEP_KMH_PER_S] 이상)에 떨어지면 값이 그 사이
     * 어디서 바뀌었는지 모른다. 차속이 계단처럼 바뀌는 신호에서 직선으로 이으면 1초 넘게 떨어진 두 계단이 한 창에 합쳐져
     * 완만한 가감속이 걸린다. 그래서 그때는 가속과 감속 각각 가장 작게 나오는 끝값을 쓴다(한 번에 기준을 넘게 바뀐 계단은
     * 1초 전 시각이 그 사이를 지나기 전에 이미 걸린다).
     */
    private fun speedChangeOverWindow(s: VehicleSnapshot, t: Long): WindowChange? {
        val prevSnapshotMs = lastSnapshotMs
        lastSnapshotMs = s.timeMs
        // 벽시계가 거꾸로 갔다. 앞 기록과 이벤트 시각을 믿을 수 없어 버리고 다시 시작한다.
        if (prevSnapshotMs != null && s.timeMs < prevSnapshotMs) clearSpeedHistory()

        // 차속 시각이 앞 기록보다 이르다. 둘 중 하나다: 틱이 예전 차속을 정속 확인 점(스냅샷 시각)에 먼저 두었는데
        // 그 사이에 차량이 만든 차속이 늦게 왔거나, 늦게 온 차속들 가운데 1초 넘게 묵어 받은 시각에 둔 것 뒤로 덜 묵은 것이 왔다.
        // 어느 쪽이든 지금 차속이 그 시각의 더 정확한 값이라 이를 남기고 그보다 늦은 점을 지운다.
        while (history.isNotEmpty() && history.last().timeMs > t) history.removeLast()
        val last = history.lastOrNull()
        if (last != null) {
            val dtMs = t - last.timeMs
            when {
                // 신호가 끊겼다. 끊긴 뒤 첫 값으로는 판정하지 않는다.
                dtMs > Thresholds.MAX_ACCEL_SAMPLE_GAP_MS -> clearSpeedHistory()
                // 같은 시각에 얻은 차속은 나중 값만 남긴다.
                dtMs == 0L -> history.removeLast()
            }
        }
        history.addLast(SpeedPoint(t, s.speedKmh))

        val fromMs = t - Thresholds.ACCEL_WINDOW_MS
        // 다음에 올 새 차속은 지금보다 최대 [Thresholds.MAX_SPEED_SAMPLE_AGE_MS] 이른 시각에 놓일 수 있다(늦게 온 차속).
        // 그 차속의 1초 창도 잴 수 있게 그만큼 더 남긴다. 정속 확인 점 하나 때문에 감속 중 기록이 잘려 판정이 끊기면
        // 같은 감속이 두 번 걸리거나 짧은 제동을 놓친다.
        val keepFromMs = fromMs - Thresholds.MAX_SPEED_SAMPLE_AGE_MS
        while (history.size > 2 && history[1].timeMs <= keepFromMs) history.removeFirst()
        val i = history.indexOfLast { it.timeMs <= fromMs }
        if (i < 0) return null
        // 마지막 점은 지금 차속(t > fromMs)이라 i 뒤에는 늘 점이 있다.
        val before = history[i]
        val after = history[i + 1]
        val windowS = Thresholds.ACCEL_WINDOW_MS / 1000.0
        val spanMs = after.timeMs - before.timeMs
        val dv = after.speedKmh - before.speedKmh
        val insideStep = before.timeMs < fromMs && spanMs < Thresholds.ACCEL_WINDOW_MS &&
            abs(dv) * 1000.0 >= Thresholds.SPEED_STEP_KMH_PER_S * spanMs
        if (insideStep) {
            return WindowChange(
                rise = (s.speedKmh - maxOf(before.speedKmh, after.speedKmh)) / windowS,
                drop = (minOf(before.speedKmh, after.speedKmh) - s.speedKmh) / windowS,
            )
        }
        val speedAtFrom = before.speedKmh + dv * (fromMs - before.timeMs) / spanMs
        val rate = (s.speedKmh - speedAtFrom) / windowS
        return WindowChange(rise = rate, drop = -rate)
    }

    /**
     * 스냅샷의 차속을 기록에 둘 시각. 차속 시각을 모르면(데모 재생) 스냅샷 시각이다. 한 스냅샷에 한 번만 부른다.
     *
     * 새로 받은 차속은 늦게 왔어도 차량이 만든 시각에 둔다(받은 시각에 두면 늦은 만큼 1초 창이 어긋나 판정이 흔들린다).
     * 틱이나 기어 변경이 예전 차속을 다시 실은 스냅샷만, 얻은 지 [Thresholds.SPEED_HOLD_CONFIRM_MS] 이상이면
     * "그때부터 지금까지 같은 속도"라는 확인으로 보고 스냅샷 시각에 둔다. 값이 바뀔 때만 오는 차속에서
     * 정속 몇 초 뒤 첫 차속이 신호 끊김(3초)으로 읽혀 바로 뒤 급제동을 놓치지 않게 하려는 것이다.
     * 차속 시각이 스냅샷 시각보다 늦게 나오면 스냅샷 시각으로 자른다(1초 창을 넓히지 않게).
     */
    private fun speedTime(s: VehicleSnapshot): Long {
        val raw = s.speedTimeMs
        val recarried = raw != null && raw == lastSpeedTimeMs
        lastSpeedTimeMs = raw
        val st = (raw ?: return s.timeMs).coerceAtMost(s.timeMs)
        return if (recarried && s.timeMs - st >= Thresholds.SPEED_HOLD_CONFIRM_MS) s.timeMs else st
    }

    /**
     * 1초 창이 서로 겹치는 판정은 같은 행동이다. 조건이 잠깐 풀렸다 다시 걸려도 새 이벤트로 세지 않는다.
     * 시각은 차속 시각([speedTime])으로 잰다. 늦게 몰려온 차속은 스냅샷 시각이 1초 넘게 벌어져도 같은 행동이다.
     */
    private fun startsNewEvent(lastMs: Long?, nowMs: Long): Boolean =
        lastMs == null || nowMs - lastMs >= Thresholds.ACCEL_WINDOW_MS

    private fun clearSpeedHistory() {
        history.clear()
        inAccel = false
        inDecel = false
        lastAccelMs = null
        lastDecelMs = null
    }
}
