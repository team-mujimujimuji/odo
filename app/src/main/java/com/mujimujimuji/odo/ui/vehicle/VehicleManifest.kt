package com.mujimujimuji.odo.ui.vehicle

import com.mujimujimuji.odo.cosmetic.Loadout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 미니카 뷰(에셋 계약 v2).
 * - Hero: 앞 3/4 모습. 차고·휴대폰 연결 전용. 상자에 맞춰 줄인다.
 * - Navi: 뒤에서 내려다본 추적 뷰. heading-up으로 기울인 글랜스 지도 전용. 돌리지 않는다(차 앞이 항상 위).
 * - Top: 바로 위에서 본 정사영. north-up 지도(홈·트립 요약) 전용. 진행 방향으로 돌린다(그림에서는 차 앞이 위).
 */
enum class VehicleView(val dir: String) { Hero("hero"), Navi("navi"), Top("top") }

/**
 * 뷰 한 장의 픽셀·dp 크기와 기준점(px). hero 기준점은 그림 중심, navi는 차 중심 아래 바닥점, top은 차 중심(회전축).
 * 그릴 때 기준점이 주어진 상자의 중심에 오고, 회전도 기준점을 축으로 한다.
 * [skip]: 이 뷰에는 파일이 없는 레이어(만들지도 그리지도 않는다).
 */
data class ViewSpec(
    val w: Int,
    val h: Int,
    val dpW: Int,
    val dpH: Int,
    val groundY: Int,
    val anchorX: Float,
    val anchorY: Float,
    val skip: Set<String> = emptySet(),
)

/**
 * `assets/minicar/compose.json`(에셋 계약 v2). 순수 코틀린 + org.json이라 JVM 테스트에서 그대로 읽는다.
 * v1 파일(뷰 2개, `skip`·`night` 없음)도 읽는다: [top]이 null이면 Top 요청은 navi 그림으로 대신한다([assetView]).
 */
data class VehicleManifest(
    val version: Int,
    val hero: ViewSpec,
    val navi: ViewSpec,
    val order: List<String>,
    val blend: Map<String, String>,
    val labels: Map<String, String>,
    val naviScale: Map<String, Float>,
    val layers: List<String>,
    val provenance: String,
    val top: ViewSpec? = null,
    /** 밤에만 그리는 레이어(전조등·후미등 글로). */
    val night: Set<String> = emptySet(),
) {
    /** 이 매니페스트에 그림이 있는 뷰. */
    val views: List<VehicleView> get() = VehicleView.entries.filter { it != VehicleView.Top || top != null }

    /** 그 뷰를 그릴 때 실제로 읽는 그림의 뷰. top이 없는 v1 에셋에서는 Top을 navi 그림으로 대신한다. */
    fun assetView(view: VehicleView): VehicleView = if (view == VehicleView.Top && top == null) VehicleView.Navi else view

    fun spec(view: VehicleView): ViewSpec = when (view) {
        VehicleView.Hero -> hero
        VehicleView.Navi -> navi
        VehicleView.Top -> top ?: navi
    }

    fun naviScaleOf(vehicleId: String): Float = naviScale[vehicleId] ?: 1f

    /**
     * 로드아웃·뷰·주야에 맞춰 그릴 레이어 파일명(매니페스트 order 순).
     * 뷰의 skip 레이어는 빠지고, night 레이어는 밤에만 들어간다.
     */
    fun layersFor(loadout: Loadout, view: VehicleView, night: Boolean): List<String> {
        val skip = spec(view).skip
        return layersFor(loadout, order).filter { it !in skip && (night || it !in this.night) }
    }

    /** 그 뷰에 파일이 있어야 하는 레이어(layers − 뷰 skip). 그림이 없는 뷰면 빈 목록. 에셋 검사용. */
    fun filesFor(view: VehicleView): List<String> {
        if (view !in views) return emptyList()
        val skip = spec(view).skip
        return layers.filter { it !in skip }
    }

    companion object {
        const val DIR = "minicar"
        const val PATH = "$DIR/compose.json"

        /**
         * 계약 v2의 그리기 순서(깊이 순). 매니페스트를 아직 못 읽었을 때의 기본값.
         * 차체에 붙은 덧칠(투톤·스트라이프)이 차체 바로 위, 그 앞에 놓이는 부품·휠·램프·루프 박스가 뒤에 온다.
         */
        val DEFAULT_ORDER: List<String> = listOf(
            "shadow", "body", "spec", "roof_twotone", "decal_stripe", "fixed", "wheel", "lamp",
            "roof_box", "plate_gold", "glow_head", "glow_tail",
        )

        fun assetPath(vehicleId: String, view: VehicleView, layer: String): String = "$DIR/$vehicleId/${view.dir}/$layer.webp"

        /**
         * order의 항목을 로드아웃에 맞춰 파일명으로 바꾼다. "wheel"은 선택한 휠 1장(wheel_<id>),
         * 루프·데칼·번호판은 선택했을 때만 들어간다. 트레일은 에셋이 아니라 지도 렌더라 여기 없다.
         * 뷰 skip과 주야는 여기서 거르지 않는다([VehicleManifest.layersFor]의 3인자 꼴이 거른다).
         */
        fun layersFor(loadout: Loadout, order: List<String> = DEFAULT_ORDER): List<String> = order.mapNotNull { entry ->
            when (entry) {
                "wheel" -> "wheel_${loadout.wheelId}"
                "roof_twotone" -> entry.takeIf { loadout.roofId == "twotone" }
                "roof_box" -> entry.takeIf { loadout.roofId == "box" }
                "decal_stripe" -> entry.takeIf { loadout.decalId == "stripe" }
                "plate_gold" -> entry.takeIf { loadout.plateId == "gold" }
                else -> entry
            }
        }

        fun parse(json: String): VehicleManifest {
            val o = JSONObject(json)
            val views = o.getJSONObject("views")
            fun view(name: String): ViewSpec {
                val v = views.getJSONObject(name)
                val px = v.getJSONArray("px")
                val dp = v.getJSONArray("dp")
                val w = px.getInt(0)
                val h = px.getInt(1)
                val anchor = v.optJSONArray("anchor")
                return ViewSpec(
                    w = w,
                    h = h,
                    dpW = dp.getInt(0),
                    dpH = dp.getInt(1),
                    groundY = v.optInt("groundY", h),
                    anchorX = anchor?.getDouble(0)?.toFloat() ?: (w / 2f),
                    anchorY = anchor?.getDouble(1)?.toFloat() ?: (h / 2f),
                    skip = v.optJSONArray("skip").strings().toSet(),
                )
            }
            val vehicles = o.getJSONObject("vehicles")
            val labels = LinkedHashMap<String, String>()
            val scales = LinkedHashMap<String, Float>()
            for (id in vehicles.keys()) {
                val v = vehicles.getJSONObject(id)
                labels[id] = v.getString("label")
                scales[id] = v.optDouble("naviScale", 1.0).toFloat()
            }
            val blendObj = o.getJSONObject("blend")
            val blend = LinkedHashMap<String, String>()
            for (k in blendObj.keys()) blend[k] = blendObj.getString(k)
            return VehicleManifest(
                version = o.getInt("version"),
                hero = view("hero"),
                navi = view("navi"),
                order = o.getJSONArray("order").strings(),
                blend = blend,
                labels = labels,
                naviScale = scales,
                layers = o.getJSONArray("layers").strings(),
                provenance = o.optString("provenance", ""),
                top = if (views.has("top")) view("top") else null,
                night = o.optJSONArray("night").strings().toSet(),
            )
        }

        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }
    }
}

/**
 * 레이어 한 장을 얹는 방식(계약 v2 '그리기'). [blend]는 매니페스트 blend 표의 이름이고 null이면 그대로 얹는다.
 * 순수 값이라 JVM 테스트가 매니페스트 blend 표와 어긋나지 않는지 확인한다.
 */
enum class LayerKind(val blend: String?) {
    /** 접지 그림자. 알파 0.9. */
    Shadow(null),

    /** 차체. 차 색으로 Modulate. */
    Body("modulate"),

    /** 하이라이트. Screen. */
    Spec("screen"),

    /** 전조등 렌즈. 램프 색으로 Modulate, 알파는 낮 0.55 / 밤 1.0. */
    Lamp("modulate"),

    /** 투톤 루프. 바디 밝기에 따른 대비색으로 Modulate. */
    Roof("modulate"),

    /** 데칼 줄. 바디 밝기에 따른 대비색으로 Modulate. */
    Decal("modulate"),

    /** 전조등 빛(RGB=빛 색, 알파=세기). 램프 색으로 Modulate 틴트한 뒤 Screen. 밤에만. */
    GlowHead("screen"),

    /** 후미등 붉은 빛(RGB=빛 색, 알파=세기). 그대로 Screen. 밤에만. */
    GlowTail("screen"),

    /** fixed, wheel_*, roof_box, plate_gold: 그대로. */
    Plain(null),
    ;

    companion object {
        fun of(layer: String): LayerKind = when (layer) {
            "shadow" -> Shadow
            "body" -> Body
            "spec" -> Spec
            "lamp" -> Lamp
            "roof_twotone" -> Roof
            "decal_stripe" -> Decal
            "glow_head" -> GlowHead
            "glow_tail" -> GlowTail
            else -> Plain
        }
    }
}

/**
 * 미니카 그림을 상자에 놓는 변환(계약 v2 '크기'). 그림 px (x, y) → 캔버스 px:
 * 기준점([anchorX], [anchorY])이 상자 중앙([centerX], [centerY])에 오고, 기준점을 축으로 [rotationDeg](시계 방향)만큼 돌고 [scale]배 된다.
 */
data class VehiclePose(
    val scale: Float,
    val rotationDeg: Float,
    val anchorX: Float,
    val anchorY: Float,
    val centerX: Float,
    val centerY: Float,
) {
    /** 그림 px (x, y)가 놓이는 캔버스 x. */
    fun screenX(x: Float, y: Float): Float {
        val r = Math.toRadians(rotationDeg.toDouble())
        return (centerX + scale * ((x - anchorX) * cos(r) - (y - anchorY) * sin(r))).toFloat()
    }

    /** 그림 px (x, y)가 놓이는 캔버스 y(아래로 +). */
    fun screenY(x: Float, y: Float): Float {
        val r = Math.toRadians(rotationDeg.toDouble())
        return (centerY + scale * ((x - anchorX) * sin(r) + (y - anchorY) * cos(r))).toFloat()
    }

    companion object {
        /**
         * - Hero: 상자([boxW]×[boxH] px)에 맞춰 줄인다(fit). 회전 없음.
         * - Navi·Top: 그림의 원래 크기(px → dp 0.5배, 폭이 [ViewSpec.dpW] dp) × [markerScale] × [naviScale](차종). 상자 크기와 무관하다.
         *   Top만 [headingDeg](북 0°, 시계 방향)로 돈다. Navi는 돌지 않는다.
         * [view]는 요청한 뷰다. v1 에셋에서 Top을 navi 그림([spec])으로 대신할 때도 Top으로 넘겨 회전시킨다.
         */
        fun of(
            spec: ViewSpec,
            view: VehicleView,
            boxW: Float,
            boxH: Float,
            density: Float,
            markerScale: Float = 1f,
            naviScale: Float = 1f,
            headingDeg: Float = 0f,
        ): VehiclePose {
            val scale = when (view) {
                VehicleView.Hero -> min(boxW / spec.w, boxH / spec.h)
                VehicleView.Navi, VehicleView.Top -> spec.dpW.toFloat() / spec.w * density * markerScale * naviScale
            }
            return VehiclePose(
                scale = scale,
                rotationDeg = if (view == VehicleView.Top) headingDeg else 0f,
                anchorX = spec.anchorX,
                anchorY = spec.anchorY,
                centerX = boxW / 2f,
                centerY = boxH / 2f,
            )
        }
    }
}
