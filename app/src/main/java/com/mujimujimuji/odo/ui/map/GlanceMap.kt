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
import androidx.compose.ui.unit.dp
import com.mujimujimuji.odo.cosmetic.CosmeticCatalog
import com.mujimujimuji.odo.cosmetic.Loadout
import com.mujimujimuji.odo.drive.DriveUi
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoSize
import com.mujimujimuji.odo.ui.theme.OdoType
import com.mujimujimuji.odo.ui.vehicle.OdoVehicle
import com.mujimujimuji.odo.ui.vehicle.VehicleView

/**
 * 글랜스 지도 장면(설계서 §4.3 글랜스 행): heading-up 6 m/dp 틸트 58°, 미니카 navi 96dp가 지도 영역 가로 중앙·세로 65 %에 항상 위를 향한다.
 * [anchorX]: 미니카의 가로 위치(폭 비율). 왼쪽 패널이 덮는 만큼 오른쪽으로 옮긴다(656dp에서 x 460).
 * [FrameBudget]이 2 s 창 중앙값 >50 ms를 보면 그 글랜스 동안 틸트 0으로 폴백한다(돌아오지 않음).
 * 위치가 없거나 코리도 밖이면 계기 모드(격자 + 미니카). 글자 0.
 */
@Composable
fun GlanceMap(ui: DriveUi, loadout: Loadout, modifier: Modifier = Modifier, anchorX: Float = 0.5f) {
    val data = rememberCorridorData()
    val budget = rememberFrameBudget()
    val tilt = if (budget.tiltAllowed) MapCamera.GLANCE_TILT_DEG else 0f
    val camera = remember(ui.position, ui.headingDeg, tilt, anchorX) { MapCamera.glance(ui.position, ui.headingDeg, tilt, anchorX) }
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

/**
 * 지도 위 navi 미니카 96dp. 접지점(상자 중심)을 카메라 기준점에 두고, north-up이면 진행방향으로 회전, heading-up이면 항상 위.
 * [at]과 [data]를 주면 그 지점(예: 요약 화면 트립 끝점)에 둔다(틸트 0 기준).
 */
@Composable
fun NaviMinicar(
    loadout: Loadout,
    camera: MapCamera,
    modifier: Modifier = Modifier,
    data: CorridorData? = null,
    at: LatLng? = null,
) {
    BoxWithConstraints(modifier) {
        val wDp = maxWidth.value
        val hDp = maxHeight.value
        val pos = if (at != null && data != null) camera.toScreenDp(data, at, wDp, hDp) else camera.anchor(wDp, hDp)
        val half = OdoSize.navi.value / 2f
        OdoVehicle(
            loadout = loadout,
            view = VehicleView.Navi,
            modifier = Modifier.offset((pos.x - half).dp, (pos.y - half).dp).size(OdoSize.navi),
            headingDeg = if (camera.northUp) camera.headingDeg else 0f,
        )
    }
}

/** 지도 출처 캡션(ODbL). 정차 화면(홈·업적·차고·폰·트립 요약)에서만 쓴다 — 주행 중 지도 위 글자 0 규칙. */
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
