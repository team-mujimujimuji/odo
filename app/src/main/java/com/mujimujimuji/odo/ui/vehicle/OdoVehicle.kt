package com.mujimujimuji.odo.ui.vehicle

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import com.mujimujimuji.odo.cosmetic.CosmeticCatalog
import com.mujimujimuji.odo.cosmetic.Loadout
import com.mujimujimuji.odo.cosmetic.Slot
import kotlin.math.roundToInt

/**
 * 주야(설계서 §1.6). 야간이 기본. 루트 테마(`OdoTheme(night)`)가 `LocalOdoNight provides night`로 내려 주면
 * 미니카 램프 알파·바디 명도가 따라간다. 테마가 아직 안 내려 주면 야간으로 그린다.
 */
val LocalOdoNight = compositionLocalOf { true }

/**
 * 지도 위 미니카(Navi·Top)의 크기 배율(에셋 계약 v2 '배치'). 화면별 시작값이고 심사 뒤에 조정한다.
 * 그림 원래 크기(px → dp 0.5배) × 이 배율 × 차종 naviScale로 그린다.
 */
object VehicleMarkerScale {
    /** 홈(정차 north-up 지도, Top). */
    const val HOME = 1f

    /** 글랜스(주행 heading-up 지도, Navi). */
    const val GLANCE = 1f

    /** 트립 요약(경로 fit north-up 지도, Top). */
    const val SUMMARY = 0.8f
}

/**
 * 미니카 1대(에셋 계약 v2). 레이어 WebP를 매니페스트 order대로 합성한다. 정지 그림이고 애니메이션은 없다.
 * - Hero(차고·휴대폰 연결): 그림 중심이 상자 중심. 상자에 맞춰 줄인다(3:2, 예: 288×192dp일 때 꽉 찬다).
 * - Navi(heading-up 글랜스 지도): 기준점이 상자 중심. 돌리지 않는다(차 앞이 항상 위).
 * - Top(north-up 지도: 홈·트립 요약): 차 중심이 상자 중심. [headingDeg](북 0°, 시계 방향)로 돈다.
 * Navi·Top의 크기는 상자와 무관하다: 그림 원래 크기 × [markerScale] × 차종 naviScale([VehiclePose.of]).
 * 전조등·후미등 글로(매니페스트 night 레이어)는 [night]일 때만 읽고 그린다.
 * 에셋이 준비되기 전에는 아무것도 그리지 않는다(플레이스홀더 상자 없음).
 */
@Composable
fun OdoVehicle(
    loadout: Loadout,
    view: VehicleView,
    modifier: Modifier = Modifier,
    headingDeg: Float = 0f,
    night: Boolean = LocalOdoNight.current,
    markerScale: Float = 1f,
) {
    val context = LocalContext.current
    // 레이어 구성(차종·휠·루프·데칼·번호판)이 바뀔 때만 다시 읽는다. 색·램프는 필터만 바뀐다.
    val structure = remember(loadout.vehicle, loadout.wheelId, loadout.roofId, loadout.decalId, loadout.plateId) {
        VehicleManifest.layersFor(loadout)
    }
    var frame by remember { mutableStateOf<VehicleFrame?>(null) }
    // 주야가 바뀌면 글로 레이어가 들고 나므로 다시 읽는다(나머지 장은 캐시에서 바로 나온다).
    LaunchedEffect(loadout.vehicle, view, structure, night) {
        val m = VehicleAssets.manifest(context)
        frame = VehicleAssets.load(context, loadout.vehicle, view, m.layersFor(loadout, view, night))
    }
    val paint = remember(loadout.colorId, loadout.lampId, night) { VehiclePaint.of(loadout, night) }
    Canvas(modifier) {
        val f = frame ?: return@Canvas
        if (f.view != view) return@Canvas
        drawVehicle(f, loadout.vehicle.id, paint, headingDeg, markerScale, night)
    }
}

/** 레이어별 색 필터와 알파. 색·램프·주야가 같으면 같은 객체(remember)라 프레임마다 새로 만들지 않는다. */
class VehiclePaint private constructor(
    val body: ColorFilter,
    val lamp: ColorFilter,
    val lampAlpha: Float,
    val roof: ColorFilter,
    val decal: ColorFilter,
) {
    companion object {
        private const val LUMA_SPLIT = 0.35f
        private const val NIGHT_DARKEN = 0.15f
        private const val LAMP_ALPHA_NIGHT = 1f
        private const val LAMP_ALPHA_DAY = 0.55f

        fun of(loadout: Loadout, night: Boolean): VehiclePaint {
            val bodyArgb = argbOf(Slot.COLOR, loadout.colorId)
            val lampArgb = argbOf(Slot.LAMP, loadout.lampId)
            // 투톤 루프: 바디 휘도 > 0.35 → graphite, 아니면 pearl. 데칼: 바디가 밝으면 graphite 줄, 어두우면 pearl 줄.
            val bright = luma(bodyArgb) > LUMA_SPLIT
            val graphite = argbOf(Slot.COLOR, "graphite")
            val pearl = argbOf(Slot.COLOR, "pearl")
            val accent = if (bright) graphite else pearl
            val shade: (Long) -> Long = { if (night) darken(it, NIGHT_DARKEN) else it }
            return VehiclePaint(
                body = VehicleAssets.tint(shade(bodyArgb), BlendMode.Modulate),
                lamp = VehicleAssets.tint(lampArgb, BlendMode.Modulate),
                lampAlpha = if (night) LAMP_ALPHA_NIGHT else LAMP_ALPHA_DAY,
                roof = VehicleAssets.tint(shade(accent), BlendMode.Modulate),
                decal = VehicleAssets.tint(shade(accent), BlendMode.Modulate),
            )
        }

        private fun argbOf(slot: Slot, id: String): Long =
            CosmeticCatalog.item(slot, id).argb ?: CosmeticCatalog.defaultOf(slot).argb ?: 0xFFFFFFFFL

        /** sRGB 값 기준 상대 휘도(0..1). 토큰 비교용이라 선형화하지 않는다. */
        internal fun luma(argb: Long): Float {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            return 0.2126f * r + 0.7152f * g + 0.0722f * b
        }

        internal fun darken(argb: Long, amount: Float): Long {
            val k = 1f - amount
            val r = (((argb shr 16) and 0xFF) * k).roundToInt().coerceIn(0, 255).toLong()
            val g = (((argb shr 8) and 0xFF) * k).roundToInt().coerceIn(0, 255).toLong()
            val b = ((argb and 0xFF) * k).roundToInt().coerceIn(0, 255).toLong()
            return (argb and 0xFF000000L) or (r shl 16) or (g shl 8) or b
        }
    }
}

private const val SHADOW_ALPHA = 0.9f

private fun DrawScope.drawVehicle(
    f: VehicleFrame,
    vehicleId: String,
    paint: VehiclePaint,
    headingDeg: Float,
    markerScale: Float,
    night: Boolean,
) {
    if (size.width <= 0f || size.height <= 0f) return
    val m = f.manifest
    val pose = VehiclePose.of(
        spec = m.spec(f.view),
        view = f.view,
        boxW = size.width,
        boxH = size.height,
        density = density,
        markerScale = markerScale,
        naviScale = m.naviScaleOf(vehicleId),
        headingDeg = headingDeg,
    )
    val pivot = Offset(pose.anchorX, pose.anchorY)
    withTransform({
        translate(pose.centerX - pose.anchorX, pose.centerY - pose.anchorY)
        if (pose.rotationDeg != 0f) rotate(pose.rotationDeg, pivot)
        scale(pose.scale, pose.scale, pivot)
    }) {
        for ((name, bmp) in f.layers) {
            // 글로는 밤에만. 밤→낮으로 바뀐 직후 새 묶음을 읽기 전의 프레임에서도 그리지 않는다.
            if (!night && name in m.night) continue
            when (LayerKind.of(name)) {
                LayerKind.Shadow -> drawImage(bmp, alpha = SHADOW_ALPHA)
                LayerKind.Body -> drawImage(bmp, colorFilter = paint.body)
                LayerKind.Spec -> drawImage(bmp, blendMode = BlendMode.Screen)
                LayerKind.Lamp -> drawImage(bmp, alpha = paint.lampAlpha, colorFilter = paint.lamp)
                LayerKind.Roof -> drawImage(bmp, colorFilter = paint.roof)
                LayerKind.Decal -> drawImage(bmp, colorFilter = paint.decal)
                // 글로 그림은 RGB=빛 색, 알파=세기. 전조등 빛은 램프 색으로 물들인 뒤 Screen, 후미등 빛은 그대로 Screen.
                LayerKind.GlowHead -> drawImage(bmp, colorFilter = paint.lamp, blendMode = BlendMode.Screen)
                LayerKind.GlowTail -> drawImage(bmp, blendMode = BlendMode.Screen)
                LayerKind.Plain -> drawImage(bmp) // fixed, wheel_*, roof_box, plate_gold: 그대로
            }
        }
    }
}
