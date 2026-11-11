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
 * 1. 경로에서 [GLANCE_ROUTE_SNAP_M] 안이고 주행 heading이 없거나 경로 방위와 [GLANCE_ROUTE_AGREE_DEG] 안이면 경로 방위.
 * 2. 아니면 주행 heading.
 * 3. 그것도 없으면 홈과 같은 경로 방위([routeBearingDeg] 기본 거리 300 m 안), 마지막으로 0(북).
 * heading은 샘플마다 저역통과(α 0.3)를 거쳐 굽은 길에서 2~3샘플 늦게 따라온다. 데모 출발 램프 굽이에서는 최대 22° 늦어,
 * 틸트 화면에서 차가 길을 비스듬히 가로질러 보였다. 경로 위에서는 경로 방위가 차 밑 길과 맞고, 샘플당 회전도 heading보다 작다.
 * 트립을 시작해 heading이 빈 동안에도 첫 프레임부터 경로 진행 방향이 위다(북쪽 위로 뜬 뒤 한 번에 도는 프레임이 없다).
 * 경로를 벗어나거나(우회) heading이 경로와 크게 다르면(역주행 등) heading을 따른다.
 */
internal fun glanceHeadingDeg(headingDeg: Float?, data: CorridorData?, at: LatLng?): Float {
    val route = routeBearingDeg(data, at, GLANCE_ROUTE_SNAP_M)
    if (route != null && (headingDeg == null || angleDiffDeg(headingDeg, route) <= GLANCE_ROUTE_AGREE_DEG)) return route
    return headingDeg ?: routeBearingDeg(data, at) ?: 0f
}

/** 두 방위의 차(0~180°). 음수나 360°를 넘는 입력도 같은 방위로 본다. */
internal fun angleDiffDeg(a: Float, b: Float): Float {
    val d = (a - b) % 360f
    return abs((d + 540f) % 360f - 180f)
}

/** 글랜스에서 경로 방위를 쓰는 경로와의 거리 상한(m). 데모 주행 샘플은 모두 이 안이다. */
private const val GLANCE_ROUTE_SNAP_M = 30.0
/** 글랜스에서 경로 방위를 쓰는 heading과의 차 상한(°). 데모 주행 중 heading과 경로 방위의 차는 최대 22°다. */
private const val GLANCE_ROUTE_AGREE_DEG = 45f

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
