package com.mujimujimuji.odo.drive

import com.mujimujimuji.odo.engine.Geo
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.engine.VehicleSnapshot
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 위치 샘플에서 현재 위치·진행방향·지난 길을 만든다(설계서 §4.4). 순수 Kotlin, 엔진은 건드리지 않는다.
 * - 진행방향: 마지막 방향 갱신점에서 [minMoveM](3 m) 이상 움직였을 때만 계산(느리게 기어도 누적되면 갱신),
 *   저역통과 α=[alpha](0.3), 각도 랩 처리(350°→10°는 0°를 지나 수렴). 첫 값은 그대로 쓴다.
 * - 지난 길: [pathStepM](50 m) 간격으로 솎고, [maxPathPoints](2,000)를 넘으면 절반으로 솎은 뒤 간격을 2배로.
 *   [path]는 바뀔 때만 새 불변 리스트가 되므로 화면은 참조 비교로 스티치 Path를 다시 만든다.
 */
class HeadingTracker(
    private val alpha: Double = 0.3,
    private val minMoveM: Double = 3.0,
    pathStepM: Double = 50.0,
    private val maxPathPoints: Int = 2_000,
) {
    var position: LatLng? = null
        private set
    var headingDeg: Float? = null
        private set
    var path: List<LatLng> = emptyList()
        private set

    private val buf = ArrayList<LatLng>()
    private var headingAnchor: LatLng? = null
    private val baseStepM = pathStepM
    private var stepM = pathStepM

    fun update(s: VehicleSnapshot) {
        val p = s.location ?: return
        position = p
        val anchor = headingAnchor
        if (anchor == null) {
            headingAnchor = p
        } else {
            val (east, north) = Geo.offsetMeters(anchor, p)
            if (hypot(east, north) >= minMoveM) {
                val raw = (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
                val prev = headingDeg
                headingDeg = if (prev == null) {
                    raw.toFloat()
                } else {
                    val diff = ((raw - prev + 540.0) % 360.0) - 180.0
                    ((prev + alpha * diff + 360.0) % 360.0).toFloat()
                }
                headingAnchor = p
            }
        }
        val last = buf.lastOrNull()
        if (last == null || Geo.distanceMeters(last, p) >= stepM) {
            buf.add(p)
            if (buf.size > maxPathPoints) thin()
            path = ArrayList(buf)
        }
    }

    private fun thin() {
        val kept = ArrayList<LatLng>(buf.size / 2 + 1)
        for (i in buf.indices) if (i % 2 == 0 || i == buf.lastIndex) kept.add(buf[i])
        buf.clear()
        buf.addAll(kept)
        stepM *= 2
    }

    /** 트립 시작·데모 초기화: 위치·방향·지난 길을 모두 비운다. 다음 샘플이 위치를 다시 채운다. */
    fun reset() {
        position = null
        headingDeg = null
        headingAnchor = null
        buf.clear()
        path = emptyList()
        stepM = baseStepM
    }
}
