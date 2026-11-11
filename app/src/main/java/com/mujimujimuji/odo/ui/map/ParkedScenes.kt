package com.mujimujimuji.odo.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.mujimujimuji.odo.cosmetic.CosmeticCatalog
import com.mujimujimuji.odo.cosmetic.Loadout
import com.mujimujimuji.odo.cosmetic.Slot
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoShape
import com.mujimujimuji.odo.ui.theme.OdoSpace
import com.mujimujimuji.odo.ui.vehicle.VehicleMarkerScale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/** 트레일 색 = 미니카 색(카탈로그 argb). 색 항목에 argb가 없으면 `amber`. */
@Composable
fun trailColorOf(loadout: Loadout): Color =
    CosmeticCatalog.item(Slot.COLOR, loadout.colorId).argb?.let { Color(it) } ?: LocalOdoColors.current.amber

/**
 * 정차 north-up 정지 장면(설계서 §4.3 홈·업적·차고·폰 행): 20 m/dp, 지도 회전 없음.
 * [center]가 null이거나 코리도 밖이면 [CorridorMap]이 계기 모드(격자)로 그린다(§4.4).
 * [scrim]이면 `ground` 64 %를 덮어 패널 뒤 배경으로 가라앉힌다(업적·차고·폰).
 * [cover]는 위에 얹히는 패널 자리(캔버스 px): 그 경계에 걸치는 휴게소 픽토를 그리지 않는다(홈 패널 위 가장자리).
 * ODbL 출처 캡션은 지도가 보이는 모든 정차 화면에 둔다. 아래가 패널로 막힌 홈은 [attributionTop]으로 왼쪽 위에(데모 칩은 오른쪽 위).
 * [anchor]: [center]가 놓이는 화면 위치(폭·높이 비율). 홈은 패널에 가리지 않는 띠의 가운데([bandAnchor])를 준다.
 * [minicar]를 주면 그 자리에 Top 미니카를 [headingDeg](북 0°, 시계 방향)로 돌려 그린다(홈). 지도는 돌지 않는다. [minicarDesc]는 접근성 설명.
 * 미니카 둘레([CAR_CLEAR])에 걸치는 휴게소 픽토는 그리지 않는다(차 밑에 깔리거나 범퍼에 붙어 보이지 않게).
 */
@Composable
fun NorthUpScene(
    data: CorridorData?,
    center: LatLng?,
    modifier: Modifier = Modifier,
    scrim: Boolean = false,
    cover: Rect = Rect.Zero,
    attributionTop: Boolean = false,
    anchor: Offset = Offset(0.5f, 0.5f),
    headingDeg: Float = 0f,
    minicar: Loadout? = null,
    minicarDesc: String? = null,
) {
    val c = LocalOdoColors.current
    val at = center ?: MapCamera.NOWHERE
    val camera = remember(at, anchor, headingDeg) { MapCamera.parked(at).copy(headingDeg = headingDeg, parkedAnchor = anchor) }
    Box(modifier) {
        CorridorMap(
            data = data,
            camera = camera,
            speedKmh = 0f,
            passed = emptyList(),
            trail = false,
            modifier = Modifier.fillMaxSize().then(StillLayer),
            cover = cover,
            car = if (minicar != null) at else null,
            carClear = CAR_CLEAR * VehicleMarkerScale.HOME,
        )
        if (scrim) Box(Modifier.fillMaxSize().background(c.ground.copy(alpha = SCRIM_ALPHA)))
        if (minicar != null) {
            NaviMinicar(minicar, camera, Modifier.fillMaxSize(), markerScale = VehicleMarkerScale.HOME, description = minicarDesc)
        }
        if (data != null) Attribution(attributionTop)
    }
}

@Composable
fun ScrimmedScene(data: CorridorData?, center: LatLng?, modifier: Modifier = Modifier) =
    NorthUpScene(data, center, modifier, scrim = true)

/**
 * 트립 요약 장면(§4.3 요약 행): north-up, 지난 길 스티치, 끝점에 Top 미니카(마지막 진행방향으로 회전, 0.8배), 오른쪽 아래 ODbL 출처 캡션.
 * 끝점 미니카 둘레([CAR_CLEAR] × 0.8)에 걸치는 휴게소 픽토는 그리지 않는다.
 * 가운데 패널([covered], dp)에 가리지 않는 띠([visibleBand])에 경로 bbox를 맞춘다. 여백([FIT_PADDING_DP])이 있어 끝점 미니카가 띠 안에 든다.
 * 띠가 없으면(658×552 창: 패널 592×484 둘레 33dp뿐) 경로·미니카를 빼고 끝점 중심의 정적 지도에 `ground` 64 % 스크림만 깐다.
 * 경로 점이 2개 미만이면 시작점(없으면 시나리오 시작점) 중심의 정차 시점으로 대신한다.
 */
@Composable
fun TripFitScene(
    data: CorridorData?,
    path: List<LatLng>,
    loadout: Loadout,
    modifier: Modifier = Modifier,
    covered: DpSize = DpSize.Zero,
) {
    val trailColor = trailColorOf(loadout)
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier) {
        val wDp = maxWidth.value
        val hDp = maxHeight.value
        val band = remember(wDp, hDp, covered) { visibleBand(wDp, hDp, covered.width.value, covered.height.value) }
        val camera = remember(data, path, band, wDp, hDp) {
            if (band == null) {
                MapCamera.parked(path.lastOrNull() ?: data?.routeStart ?: MapCamera.NOWHERE)
            } else {
                val anchor = Offset(band.center.x / wDp, band.center.y / hDp)
                if (path.size >= 2) {
                    MapCamera.fit(path, band.width, band.height, FIT_PADDING_DP, lastHeadingDeg(path)).copy(parkedAnchor = anchor)
                } else {
                    MapCamera.parked(path.firstOrNull() ?: data?.routeStart ?: MapCamera.NOWHERE).copy(parkedAnchor = anchor)
                }
            }
        }
        val cover = if (covered.width.value > 0f && covered.height.value > 0f) {
            val cw = covered.width.value
            val ch = covered.height.value
            Rect((wDp - cw) / 2f * density, (hDp - ch) / 2f * density, (wDp + cw) / 2f * density, (hDp + ch) / 2f * density)
        } else {
            Rect.Zero
        }
        val end = path.lastOrNull()
        CorridorMap(
            data = data,
            camera = camera,
            speedKmh = 0f,
            passed = if (band != null) path else emptyList(),
            trail = band != null && loadout.trailId != CosmeticCatalog.NONE,
            modifier = Modifier.fillMaxSize().then(StillLayer),
            trailColor = trailColor,
            cover = cover,
            showRoute = band != null,
            car = if (band != null) end else null,
            carClear = CAR_CLEAR * VehicleMarkerScale.SUMMARY,
        )
        if (band == null) {
            Box(Modifier.fillMaxSize().background(LocalOdoColors.current.ground.copy(alpha = SCRIM_ALPHA)))
        } else if (data != null && end != null) {
            NaviMinicar(loadout, camera, Modifier.fillMaxSize(), data, end, markerScale = VehicleMarkerScale.SUMMARY)
        }
        Attribution(top = false)
    }
}

/**
 * 가운데 패널(coverW×coverH dp)을 뺀 지도 띠: 오른쪽 띠와 아래 띠 중 짧은 변이 긴 쪽. 가림이 없으면 캔버스 전체.
 * 그 짧은 변이 [FIT_MIN_DP]보다 짧으면(경로를 담을 자리가 없으면) null.
 */
internal fun visibleBand(w: Float, h: Float, coverW: Float, coverH: Float): Rect? {
    if (coverW <= 0f || coverH <= 0f) return Rect(0f, 0f, w, h)
    val side = (w - coverW) / 2f
    val below = (h - coverH) / 2f
    val right = Rect(w - side, 0f, w, h)
    val bottom = Rect(0f, h - below, w, h)
    val best = if (min(right.width, right.height) >= min(bottom.width, bottom.height)) right else bottom
    return best.takeIf { min(it.width, it.height) >= FIT_MIN_DP }
}

/**
 * 지도 위 ODbL 캡션. 글자는 측정된 어둡기 위에 둔다: ground 60 % 스크림 칩 → 가장 밝은 도로(roadCore) 위에서도
 * inkDim 5.0:1(야간)·5.2:1(주간), tools/design/contrast.py. 요약에서는 경로선이 띠 여백 안쪽이라 이 모서리에 오지 않는다.
 * [top]이면 왼쪽 위(홈), 아니면 오른쪽 아래. 왼쪽 위는 데모 칩과 겹치지 않게 폭을 묶고 넘치면 2줄로 꺾는다(말줄임 없음).
 */
@Composable
private fun BoxScope.Attribution(top: Boolean) {
    val place = if (top) {
        Modifier.align(Alignment.TopStart).padding(top = OdoSpace.xl, start = OdoSpace.margin).widthIn(max = ATTRIBUTION_TOP_MAX_W)
    } else {
        Modifier.align(Alignment.BottomEnd).padding(OdoSpace.xs)
    }
    MapAttribution(
        place
            .background(LocalOdoColors.current.ground.copy(alpha = ATTRIBUTION_SCRIM_ALPHA), OdoShape.chip)
            .padding(horizontal = OdoSpace.s),
        maxLines = if (top) 2 else 1,
    )
}

/** 경로 마지막 두 점의 방위각(북 0°, 시계방향). 같은 점이면 0. */
internal fun lastHeadingDeg(path: List<LatLng>): Float {
    if (path.size < 2) return 0f
    val a = path[path.size - 2]
    val b = path[path.size - 1]
    val dx = (b.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2))
    val dy = b.lat - a.lat
    if (dx == 0.0 && dy == 0.0) return 0f
    return ((Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0).toFloat()
}

/**
 * 위쪽 [topPx]와 아래쪽 [bottomPx]가 가려진 높이 [heightPx] 화면에서, 지도가 보이는 띠의 가운데를 정차 카메라 기준점(폭·높이 비율)으로 준다.
 * 가로는 가운데. 홈: 위 = 캡션·칩 줄, 아래 = 알림 줄 + 패널 → 656×548 창에서 y 172dp(알림 줄이 있으면 150dp).
 * 띠가 없으면(가림이 화면을 다 덮으면) 화면 가운데.
 */
internal fun bandAnchor(heightPx: Float, topPx: Float, bottomPx: Float): Offset {
    if (heightPx <= 0f) return Offset(0.5f, 0.5f)
    val top = topPx.coerceIn(0f, heightPx)
    val bottom = (heightPx - bottomPx).coerceIn(0f, heightPx)
    if (bottom <= top) return Offset(0.5f, 0.5f)
    return Offset(0.5f, (top + bottom) / 2f / heightPx)
}

/**
 * 경로 방위(북 0°, 시계 방향): [at]에서 가장 가까운 경로 지점의 진행 방향(앞뒤 [ROUTE_BEARING_SPAN_M] m를 이은 방향).
 * 지도 데이터나 위치가 없거나, 경로에서 [ROUTE_SNAP_M]보다 멀면(경로와 상관없는 곳) null.
 */
internal fun routeBearingDeg(data: CorridorData?, at: LatLng?): Float? {
    if (data == null || at == null) return null
    val x = data.localX(at)
    val y = data.localY(at)
    val s = data.routeProgress(x, y)
    val p = DoubleArray(2)
    data.routePoint(s, p)
    if (hypot(p[0] - x, p[1] - y) > ROUTE_SNAP_M) return null
    val a = DoubleArray(2)
    val b = DoubleArray(2)
    data.routePoint(s - ROUTE_BEARING_SPAN_M, a)
    data.routePoint(s + ROUTE_BEARING_SPAN_M, b)
    val dx = b[0] - a[0] // 동(+)
    val dy = b[1] - a[1] // 북(+)
    if (dx == 0.0 && dy == 0.0) return null
    return ((Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0).toFloat()
}

/** 홈 미니카 방향(설계서 §4.3): 주행에서 얻은 heading → 없으면 경로 방위 → 그것도 없으면 0(북). */
internal fun homeHeadingDeg(uiHeadingDeg: Float?, data: CorridorData?, at: LatLng?): Float =
    uiHeadingDeg ?: routeBearingDeg(data, at) ?: 0f

/**
 * 정지 장면 지도는 오프스크린 합성층에 한 번 그리고, 지도가 바뀌지 않는 동안 그 텍스처를 다시 쓴다.
 * 위에서 움직이는 것(요약 박자의 카드·업적 행, 크로스페이드, KeyStrip 눌림, 차고 미리보기)이 프레임을 낼 때마다
 * 66 km 벡터 지도를 다시 래스터하지 않게 한다. heading-up 글랜스는 매 프레임 지도가 바뀌어 이득이 없으므로 쓰지 않는다.
 */
private val StillLayer = Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }

/** 요약 fit 여백: 끝점 Top 미니카(0.8배, 소형 차체 반대각 약 43dp)가 띠 안에 든다. */
private const val FIT_PADDING_DP = 56f
/** 경로를 담을 띠의 짧은 변 최소값(여백 2×56 + 경로 96). */
private const val FIT_MIN_DP = 208f
private const val SCRIM_ALPHA = 0.64f
/**
 * 정차 미니카(Top) 둘레에서 휴게소 픽토를 비울 반경(배율 1 기준, 화면 배율을 곱해 쓴다). 픽토 반지름 18dp를 더한 거리 안의 픽토는 그리지 않는다.
 * 차가 그 휴게소에 서 있으면 차가 자리를 알려 주므로 픽토가 차 밑에 깔리거나 범퍼에 붙어 보이지 않게 뺀다.
 * 가장 긴 차체(대형 Top 차체 반길이 62dp)보다 길게 잡아, 픽토가 어느 방향에 있든 범퍼와 닿지 않는다.
 * 데모 경로(정차 축척 20 m/dp)에서 트립 끝 휴게소 바로 앞 픽토(차 중심에서 75dp)는 빠지고,
 * 출발 지점 홈에서 차 밑이 아닌 가장 가까운 픽토(94dp)는 남는다.
 */
internal val CAR_CLEAR = 68.dp
private const val ATTRIBUTION_SCRIM_ALPHA = 0.6f
/** 홈 왼쪽 위 캡션 폭 상한: 32 + 520 = 552 < 데모 칩 왼쪽(글꼴 1.3에서 약 557). */
private val ATTRIBUTION_TOP_MAX_W = 520.dp
/** 경로 방위를 재는 구간: 가장 가까운 경로 지점의 앞뒤 30 m. */
private const val ROUTE_BEARING_SPAN_M = 30.0
/**
 * 이보다 경로에서 멀면 경로 방위를 쓰지 않는다. 경로 옆 주차 자리까지는 경로 방향을 따르도록 넉넉히 잡은 값이다
 * (데모 시나리오의 정차 지점 두 곳은 경로에서 1 m 안).
 */
private const val ROUTE_SNAP_M = 300.0
