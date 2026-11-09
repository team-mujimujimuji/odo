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
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 주야(설계서 §1.6). 야간이 기본. 루트 테마(`OdoTheme(night)`)가 `LocalOdoNight provides night`로 내려 주면
 * 미니카 램프 알파·바디 명도가 따라간다. 테마가 아직 안 내려 주면 야간으로 그린다.
 */
val LocalOdoNight = compositionLocalOf { true }

/**
 * 미니카 1대(설계서 §2.5). 레이어 WebP를 매니페스트 order대로 합성한다.
 * - Hero: 그림 중심이 상자 중심. 상자는 3:2(예: 288×192dp)일 때 꽉 찬다.
 * - Navi: 접지 기준점이 상자 중심. heading-up 지도에서는 headingDeg=0(차가 항상 위), north-up 지도에서는 진행방향으로 회전.
 * 에셋이 준비되기 전에는 아무것도 그리지 않는다(플레이스홀더 상자 없음).
 */
@Composable
fun OdoVehicle(
    loadout: Loadout,
    view: VehicleView,
    modifier: Modifier = Modifier,
    headingDeg: Float = 0f,
    night: Boolean = LocalOdoNight.current,
) {
    val context = LocalContext.current
    // 레이어 구성(차종·휠·루프·데칼·번호판)이 바뀔 때만 다시 읽는다. 색·램프는 필터만 바뀐다.
    val structure = remember(loadout.vehicle, loadout.wheelId, loadout.roofId, loadout.decalId, loadout.plateId) {
        VehicleManifest.layersFor(loadout)
    }
    var frame by remember { mutableStateOf<VehicleFrame?>(null) }
    LaunchedEffect(loadout.vehicle, view, structure) {
        val m = VehicleAssets.manifest(context)
        frame = VehicleAssets.load(context, loadout.vehicle, view, m.layersFor(loadout))
    }
    val paint = remember(loadout.colorId, loadout.lampId, night) { VehiclePaint.of(loadout, night) }
    Canvas(modifier) {
        val f = frame ?: return@Canvas
        if (f.view != view) return@Canvas
        drawVehicle(f, loadout.vehicle.id, paint, headingDeg)
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

private fun DrawScope.drawVehicle(f: VehicleFrame, vehicleId: String, paint: VehiclePaint, headingDeg: Float) {
    val spec = f.manifest.spec(f.view)
    if (size.width <= 0f || size.height <= 0f) return
    var s = min(size.width / spec.w, size.height / spec.h)
    if (f.view == VehicleView.Navi) s *= f.manifest.naviScaleOf(vehicleId)
    val pivot = Offset(spec.anchorX, spec.anchorY)
    withTransform({
        translate(size.width / 2f - spec.anchorX, size.height / 2f - spec.anchorY)
        if (f.view == VehicleView.Navi && headingDeg != 0f) rotate(headingDeg, pivot)
        scale(s, s, pivot)
    }) {
        for ((name, bmp) in f.layers) {
            when (name) {
                "shadow" -> drawImage(bmp, alpha = SHADOW_ALPHA)
                "body" -> drawImage(bmp, colorFilter = paint.body)
                "spec" -> drawImage(bmp, blendMode = BlendMode.Screen)
                "lamp" -> drawImage(bmp, alpha = paint.lampAlpha, colorFilter = paint.lamp)
                "roof_twotone" -> drawImage(bmp, colorFilter = paint.roof)
                "decal_stripe" -> drawImage(bmp, colorFilter = paint.decal)
                else -> drawImage(bmp) // fixed, wheel_*, roof_box, plate_gold: 그대로
            }
        }
    }
}
