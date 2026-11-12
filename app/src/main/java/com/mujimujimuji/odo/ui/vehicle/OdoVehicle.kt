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
 * 지도 위 미니카(Navi·Top)의 크기 배율(설계서 §4.3). 화면별 시작값이다.
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
 * 미니카 1대(compose.json version 2, 설계서 §2.5). 레이어 WebP를 매니페스트 order대로 합성한다. 정지 그림이고 애니메이션은 없다.
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

        /** 투톤 지붕색: 밝은 차 위의 밝은 진회색(graphite보다 밝다). 어두운 차 위는 pearl. */
        private const val ROOF_ON_BRIGHT = 0xFF6B727DL

        fun of(loadout: Loadout, night: Boolean): VehiclePaint {
            val tints = tintsOf(loadout.colorId, night)
            val lampArgb = argbOf(Slot.LAMP, loadout.lampId)
            return VehiclePaint(
                body = VehicleAssets.tint(tints.body, BlendMode.Modulate),
                lamp = VehicleAssets.tint(lampArgb, BlendMode.Modulate),
                lampAlpha = if (night) LAMP_ALPHA_NIGHT else LAMP_ALPHA_DAY,
                roof = VehicleAssets.tint(tints.roof, BlendMode.Modulate),
                decal = VehicleAssets.tint(tints.decal, BlendMode.Modulate),
            )
        }

        /**
         * 몸·투톤 지붕·데칼 층에 거는 색(ARGB). [of]가 이 값으로 색 필터를 만든다.
         * 지붕색·데칼색은 바디 밝기로 고르고([roofArgb], [decalArgb]), 밤에는 셋을 같이 0.85배로 어둡게 한다.
         */
        internal fun tintsOf(colorId: String, night: Boolean): VehicleTints {
            val bodyArgb = argbOf(Slot.COLOR, colorId)
            val shade: (Long) -> Long = { if (night) darken(it, NIGHT_DARKEN) else it }
            return VehicleTints(
                body = shade(bodyArgb),
                roof = shade(roofArgb(bodyArgb)),
                decal = shade(decalArgb(bodyArgb)),
            )
        }

        /** 바디가 밝은 차인지: sRGB 휘도([luma])가 0.35보다 크면 밝다. 지붕색·데칼색을 이것으로 고른다. */
        internal fun isBright(bodyArgb: Long): Boolean = luma(bodyArgb) > LUMA_SPLIT

        /** 투톤 지붕색(밤에 어둡게 하기 전): 밝은 차 위는 밝은 진회색([ROOF_ON_BRIGHT]), 어두운 차 위는 pearl. */
        internal fun roofArgb(bodyArgb: Long): Long =
            if (isBright(bodyArgb)) ROOF_ON_BRIGHT else argbOf(Slot.COLOR, "pearl")

        /** 데칼 줄 색(밤에 어둡게 하기 전): 밝은 차 위는 graphite, 어두운 차 위는 pearl. */
        internal fun decalArgb(bodyArgb: Long): Long =
            argbOf(Slot.COLOR, if (isBright(bodyArgb)) "graphite" else "pearl")

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

/** 몸·투톤 지붕·데칼 층의 색(ARGB, 밤이면 어둡게 한 값). [VehiclePaint.tintsOf]가 만든다. */
internal data class VehicleTints(val body: Long, val roof: Long, val decal: Long)

private const val SHADOW_ALPHA = 0.9f

/**
 * 밤 글로 세기(그림 알파에 곱한다). 그림은 차고(Hero)에 맞춰 구웠다.
 * - Navi(글랜스): 꼬리등 번짐이 지나온 길 위로 차 폭만큼 퍼지고, 전조등 번짐이 지붕 앞 경로선 위에 회색으로 뜬다. 둘 다 줄이고 전조등을 더 줄인다.
 * - Top(홈·요약): 전조등 빛이 지도 위에서 회색 연기처럼 앞으로 퍼진다. 꼬리등 빛은 차 뒤에 짧게 붙어 있어 그대로 둔다.
 * - Hero(차고·휴대폰 연결): 빈 바탕 위라 그대로.
 */
internal object GlowAlpha {
    const val NAVI_TAIL = 0.55f
    const val NAVI_HEAD = 0.35f
    const val TOP_HEAD = 0.6f
    const val TOP_TAIL = 1f
    const val HERO = 1f
}

/** [view]에서 글로 층 [kind]의 알파. 글로가 아닌 층은 1. */
internal fun glowAlpha(view: VehicleView, kind: LayerKind): Float = when (kind) {
    LayerKind.GlowHead -> when (view) {
        VehicleView.Navi -> GlowAlpha.NAVI_HEAD
        VehicleView.Top -> GlowAlpha.TOP_HEAD
        VehicleView.Hero -> GlowAlpha.HERO
    }
    LayerKind.GlowTail -> when (view) {
        VehicleView.Navi -> GlowAlpha.NAVI_TAIL
        VehicleView.Top -> GlowAlpha.TOP_TAIL
        VehicleView.Hero -> GlowAlpha.HERO
    }
    else -> 1f
}

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
                // 세기는 뷰마다 [glowAlpha]로 줄인다(지도 위에서 번짐이 길·픽토를 덮지 않게).
                LayerKind.GlowHead ->
                    drawImage(bmp, alpha = glowAlpha(f.view, LayerKind.GlowHead), colorFilter = paint.lamp, blendMode = BlendMode.Screen)
                LayerKind.GlowTail -> drawImage(bmp, alpha = glowAlpha(f.view, LayerKind.GlowTail), blendMode = BlendMode.Screen)
                LayerKind.Plain -> drawImage(bmp) // fixed, wheel_*, roof_box, plate_gold: 그대로
            }
        }
    }
}
