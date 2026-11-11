package com.mujimujimuji.odo.ui.map

import androidx.compose.ui.geometry.Offset

/**
 * 차가 서 있는 휴게소로 보는 거리(로컬 m). 정차 미니카에서 이 거리 안의 가장 가까운 POI 하나는
 * 차 둘레 비움 원([CAR_CLEAR]) 때문에 빠지지 않고 차 옆에 옮겨 그린다([pictoBesideCar]). 그래야 휴게소에 선 장면에서도 휴게소 표시가 남는다.
 * 데모 시나리오의 정차 두 곳에서 가장 가까운 POI는 131 m(출발, OSM way 344480537)와 198 m(트립 끝, OSM node 414680790)이고,
 * 그다음 POI는 1.5 km 넘게 떨어져 있다.
 */
internal const val STAND_POI_M = 300.0

/**
 * 로컬 m 좌표 ([x], [y])에서 가장 가까운 POI의 인덱스. 가장 가까운 POI가 [maxM]보다 멀면(또는 POI가 없으면) -1.
 * 거리가 같으면 앞의 것. 할당 없이 POI를 한 번 훑는다.
 */
internal fun standingPoiIndex(pois: List<MapPoi>, x: Double, y: Double, maxM: Double = STAND_POI_M): Int {
    var best = -1
    var bestD2 = Double.MAX_VALUE
    for (i in pois.indices) {
        val dx = pois[i].x - x
        val dy = pois[i].y - y
        val d2 = dx * dx + dy * dy
        if (d2 < bestD2) {
            bestD2 = d2
            best = i
        }
    }
    return if (best >= 0 && bestD2 <= maxM * maxM) best else -1
}

/**
 * 차가 선 휴게소 픽토(중심 [sx]·[sy], 반지름 [half])를 그릴 자리(캔버스 px).
 * 차 둘레 원(중심 [carX]·[carY], 반경 [clearPx])에 걸치지 않으면([pictoHitsCar] false) 그대로 둔다.
 * 걸치면 차와 같은 높이에서 화면 가로로, 차 중심에서 `clearPx + half + 1` px 떨어진 곳(비움 원 바로 바깥)으로 옮긴다.
 * 픽토가 차 왼쪽이면 왼쪽, 오른쪽이거나 바로 위·아래면 오른쪽이다.
 * 위·아래로 옮기면 홈 지도 위 출처 캡션이나 아래 알림 줄에 붙을 수 있어서 가로로만 옮긴다.
 */
internal fun pictoBesideCar(sx: Float, sy: Float, half: Float, carX: Float, carY: Float, clearPx: Float): Offset {
    if (!pictoHitsCar(sx, sy, half, carX, carY, clearPx)) return Offset(sx, sy)
    val r = clearPx + half + 1f
    return Offset(if (sx < carX) carX - r else carX + r, carY)
}
