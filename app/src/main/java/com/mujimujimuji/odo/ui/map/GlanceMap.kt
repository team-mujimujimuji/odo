package com.mujimujimuji.odo.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mujimujimuji.odo.cosmetic.CosmeticCatalog
import com.mujimujimuji.odo.cosmetic.Loadout
import com.mujimujimuji.odo.drive.DriveUi
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoSize
import com.mujimujimuji.odo.ui.theme.OdoType
import com.mujimujimuji.odo.ui.vehicle.OdoVehicle
import com.mujimujimuji.odo.ui.vehicle.VehicleMarkerScale
import com.mujimujimuji.odo.ui.vehicle.VehicleView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * 글랜스 지도 장면(설계서 §4.3 글랜스 행): heading-up 6 m/dp 틸트 58°, 미니카 navi(뒤에서 내려다본 추적 뷰)가 지도 영역 가로 중앙·세로 65 %에 항상 위를 향한다.
 * [anchorX]: 미니카의 가로 위치(폭 비율). 왼쪽 패널이 덮는 만큼 오른쪽으로 옮긴다(656dp에서 x 460).
 * [FrameBudget]이 2 s 창 중앙값 >50 ms를 보면 그 글랜스 동안 틸트 0으로 폴백한다(돌아오지 않음).
 * 위치가 없거나 코리도 밖이면 계기 모드(격자 + 미니카). 글자 0.
 */
@Composable
fun GlanceMap(ui: DriveUi, loadout: Loadout, modifier: Modifier = Modifier, anchorX: Float = 0.5f) {
    val data = rememberCorridorData()
    val budget = rememberFrameBudget()
    val tilt = if (budget.tiltAllowed) MapCamera.GLANCE_TILT_DEG else 0f
    val camera = remember(ui.position, ui.headingDeg, data, tilt, anchorX) { glanceCamera(ui.position, ui.headingDeg, data, tilt, anchorX) }
    val trailColor = trailColorOf(loadout)
    Box(modifier) {
        CorridorMap(
            data = data,
            camera = camera,
            speedKmh = ui.speedKmh,
            passed = ui.tripPath,
            trail = loadout.trailId != CosmeticCatalog.NONE,
            modifier = Modifier.fillMaxSize(),
            trailColor = trailColor,
        )
        NaviMinicar(loadout, camera, Modifier.fillMaxSize())
    }
}

/** 글랜스 카메라. 방위는 [glanceHeadingDeg]로 정한다. */
internal fun glanceCamera(position: LatLng?, headingDeg: Float?, data: CorridorData?, tiltDeg: Float, anchorX: Float): MapCamera =
    MapCamera.glance(position, glanceHeadingDeg(headingDeg, data, position), tiltDeg, anchorX)

/**
 * 글랜스 지도 방위(북 0°, 시계 방향). 순서:
 * 1. 경로에서 [GLANCE_ROUTE_SNAP_M] 안이고 주행 heading이 없거나 차 밑 경로 방위(가장 가까운 경로 지점의 앞뒤 [GLANCE_LOCAL_SPAN_M] m,
 *    [routeBearingDeg]와 같은 값)와 [GLANCE_ROUTE_AGREE_DEG] 안이면 경로 위 글랜스 방위([glanceRouteDeg]).
 *    판정은 지도 방위가 아니라 차 밑 경로 방위와의 차로 한다.
 * 2. 아니면 주행 heading.
 * 3. 그것도 없으면 홈과 같은 경로 방위([routeBearingDeg] 기본 거리 300 m 안), 마지막으로 0(북).
 * heading은 저역통과(α 0.3)로 굽은 길에서 늦으므로 경로 위에서는 쓰지 않는다. 트립을 시작해 heading이 빈 동안에도
 * 첫 프레임부터 경로 진행 방향이 위다(북쪽 위로 뜬 뒤 한 번에 도는 프레임이 없다).
 * 경로를 벗어나거나(우회) heading이 경로와 크게 다르면(역주행, 후진 등) heading을 따른다. 정차 중에는 위치가 그대로라 방위도 그대로다.
 * 가장 가까운 경로 지점은 한 번만 찾는다.
 */
internal fun glanceHeadingDeg(headingDeg: Float?, data: CorridorData?, at: LatLng?): Float {
    if (data != null && at != null) {
        val x = data.localX(at)
        val y = data.localY(at)
        val s = data.routeProgress(x, y)
        val p = DoubleArray(2)
        data.routePoint(s, p)
        val local = if (hypot(p[0] - x, p[1] - y) <= GLANCE_ROUTE_SNAP_M) {
            routeChordDeg(data, s - GLANCE_LOCAL_SPAN_M, s + GLANCE_LOCAL_SPAN_M)
        } else {
            null
        }
        if (local != null && (headingDeg == null || angleDiffDeg(headingDeg, local) <= GLANCE_ROUTE_AGREE_DEG)) {
            return glanceRouteDeg(data, s) ?: local
        }
    }
    return headingDeg ?: routeBearingDeg(data, at) ?: 0f
}

/**
 * 경로 위 글랜스 방위(북 0°, 시계 방향): 경로 거리 [s]에서 앞보기 방위(앞 [GLANCE_LOOK_AHEAD_M] m 구간, [routeWindowDeg])를
 * 둘레 방위(앞뒤 [GLANCE_AROUND_HALF_M] m씩 1.2 km 구간)의 ±[GLANCE_AROUND_CLAMP_DEG] 안으로 묶은 값.
 * 6 m/dp 틸트 58°에서 차 지붕 위로 보이는 길은 차 앞 약 200~630 m, 차 아래로 보이는 길은 차 뒤 약 650 m까지다.
 * 차 밑 앞뒤 30 m 방위를 쓰면 출발 램프처럼 120 m 안에서 S자로 꺾이는 곳(미니카 그림 밑의 굽이)에서 지도가 샘플마다 10° 넘게 돌고,
 * 지붕 위 길이 45°까지 기운다. 앞보기 방위만 쓰면 지붕 위 길은 서지만, 굽은 길에서 지도가 차보다 먼저 돌아 차 뒤 길이 많이 기운다
 * (데모 경로에서 지도 방위와 차 뒤 100~600 m 방향의 차가 최대 73°).
 * 둘레 방위는 1.2 km를 한 번에 이어 짧은 S자를 지우고, 묶음은 지도가 차 둘레 길 방향보다 8° 넘게 앞서 돌지 않게 한다
 * (같은 차가 최대 46°, 출발 S자 밖에서 차 밑 길과의 차는 31° 안).
 * 출발 직후(경로 처음 600 m 안)에는 둘레 구간이 경로 처음 1.2 km에 붙고, 앞보기 구간은 차와 함께 움직이지만 700 m 현이
 * 120 m 안에서 꺾이는 S자보다 훨씬 길다. 그래서 S자를 지나는 동안 지붕 위 길이 거의 세로로 서고, 샘플당 회전은 2° 안이다.
 * 경로 끝에서는 두 구간이 경로 끝에 붙어 방위가 멈춘다(끝에 다가가도 짧은 구간 방위로 흔들리지 않는다).
 * 한 구간의 두 점이 같으면 다른 구간 방위, 둘 다 같으면 null.
 */
internal fun glanceRouteDeg(data: CorridorData, s: Double): Float? {
    val ahead = routeWindowDeg(data, s, 0.0, GLANCE_LOOK_AHEAD_M)
    val around = routeWindowDeg(data, s, GLANCE_AROUND_HALF_M, GLANCE_AROUND_HALF_M)
    if (ahead == null || around == null) return ahead ?: around
    val lead = signedAngleDeg(ahead, around).coerceIn(-GLANCE_AROUND_CLAMP_DEG, GLANCE_AROUND_CLAMP_DEG)
    return (around + lead + 360f) % 360f
}

/**
 * 경로 구간 방위(북 0°, 시계 방향): 경로를 따라 [s] 뒤 [backM] m에서 앞 [aheadM] m까지 이은 방향.
 * 구간이 경로 처음이나 끝을 넘으면 길이를 지킨 채 경로 안으로 민다. 경로가 구간보다 짧으면 경로 처음과 끝을 잇는다. 두 점이 같으면 null.
 */
internal fun routeWindowDeg(data: CorridorData, s: Double, backM: Double, aheadM: Double): Float? {
    val from = (s - backM).coerceIn(0.0, max(0.0, data.routeLengthM - backM - aheadM))
    return routeChordDeg(data, from, from + backM + aheadM)
}

/** 경로 거리 [from] 지점에서 [to] 지점을 향한 방위(각 거리는 경로 안으로 자른다). 두 점이 같으면 null. */
private fun routeChordDeg(data: CorridorData, from: Double, to: Double): Float? {
    val a = DoubleArray(2)
    val b = DoubleArray(2)
    data.routePoint(from, a)
    data.routePoint(to, b)
    val dx = b[0] - a[0] // 동(+)
    val dy = b[1] - a[1] // 북(+)
    if (dx == 0.0 && dy == 0.0) return null
    return ((Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0).toFloat()
}

/** 두 방위의 차(0~180°). 음수나 360°를 넘는 입력도 같은 방위로 본다. */
internal fun angleDiffDeg(a: Float, b: Float): Float {
    val d = (a - b) % 360f
    return abs((d + 540f) % 360f - 180f)
}

/** 방위 [b]에서 [a]까지 돈 각(-180° 이상 180° 미만, 시계 방향 +). */
internal fun signedAngleDeg(a: Float, b: Float): Float = ((a - b) % 360f + 540f) % 360f - 180f

/** 글랜스에서 경로 방위를 쓰는 경로와의 거리 상한(m). 데모 주행 샘플은 모두 이 안이다. */
private const val GLANCE_ROUTE_SNAP_M = 30.0
/** 글랜스에서 경로 방위를 쓰는 heading과의 차 상한(°). 데모 주행 중 heading과 차 밑 경로 방위의 차는 최대 22°다. */
private const val GLANCE_ROUTE_AGREE_DEG = 45f
/** 차 밑 경로 방위를 재는 구간: 가장 가까운 경로 지점의 앞뒤 30 m(홈 경로 방위와 같다). */
private const val GLANCE_LOCAL_SPAN_M = 30.0
/** 글랜스 앞보기 거리(m). 차 지붕 위로 보이는 길(차 앞 약 200~630 m)을 덮는다. */
internal const val GLANCE_LOOK_AHEAD_M = 700.0
/** 글랜스 둘레 방위의 반 길이(m). 차 앞뒤로 보이는 길(각 약 650 m)과 비슷하게 잡는다. */
internal const val GLANCE_AROUND_HALF_M = 600.0
/** 앞보기 방위가 둘레 방위에서 벗어날 수 있는 한도(°). */
internal const val GLANCE_AROUND_CLAMP_DEG = 8f

/**
 * 지도 위 미니카(설계서 §4.3). 그림의 기준점(상자 중심)을 카메라 기준점에 둔다.
 * north-up 카메라(홈·트립 요약)면 Top 뷰를 `camera.headingDeg`로 돌리고, heading-up 카메라(글랜스)면 Navi 뷰를 돌리지 않는다(차 앞이 항상 위).
 * 그림 크기는 상자(96dp)와 무관하다: 원래 크기 × [markerScale] × 차종 배율. 정지 그림이고 애니메이션은 없다.
 * [at]과 [data]를 주면 그 지점(예: 요약 화면 트립 끝점)에 둔다(틸트 0 기준).
 * [description]을 주면 미니카에 접근성 설명을 단다(홈).
 */
@Composable
fun NaviMinicar(
    loadout: Loadout,
    camera: MapCamera,
    modifier: Modifier = Modifier,
    data: CorridorData? = null,
    at: LatLng? = null,
    markerScale: Float = VehicleMarkerScale.GLANCE,
    description: String? = null,
) {
    BoxWithConstraints(modifier) {
        val wDp = maxWidth.value
        val hDp = maxHeight.value
        val pos = if (at != null && data != null) camera.toScreenDp(data, at, wDp, hDp) else camera.anchor(wDp, hDp)
        val half = OdoSize.navi.value / 2f
        val box = Modifier.offset((pos.x - half).dp, (pos.y - half).dp).size(OdoSize.navi)
        OdoVehicle(
            loadout = loadout,
            view = if (camera.northUp) VehicleView.Top else VehicleView.Navi,
            modifier = if (description != null) box.semantics { contentDescription = description } else box,
            headingDeg = if (camera.northUp) camera.headingDeg else 0f,
            markerScale = markerScale,
        )
    }
}

/** 지도 출처 캡션(ODbL). 정차 화면(홈·업적·차고·폰·트립 요약)에서만 쓴다(주행 중 지도 위 글자 0 규칙). */
@Composable
fun MapAttribution(modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(
        CorridorData.ATTRIBUTION,
        modifier,
        color = LocalOdoColors.current.inkDim,
        style = OdoType.caption,
        maxLines = maxLines,
    )
}
