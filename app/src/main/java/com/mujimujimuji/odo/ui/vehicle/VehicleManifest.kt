package com.mujimujimuji.odo.ui.vehicle

import com.mujimujimuji.odo.cosmetic.Loadout
import org.json.JSONObject

/** 미니카 뷰. Hero: 홈·요약·차고의 3/4 측면. Navi: 지도 마커(위에서 뒤로 내려다본 모습, 차 머리가 위). */
enum class VehicleView(val dir: String) { Hero("hero"), Navi("navi") }

/**
 * 뷰 한 장의 픽셀·dp 크기와 기준점(px). hero 기준점은 그림 중심, navi 기준점은 매니페스트 anchor(접지 중심).
 * 그릴 때 기준점이 주어진 상자의 중심에 오고, 회전도 기준점을 축으로 한다.
 */
data class ViewSpec(
    val w: Int,
    val h: Int,
    val dpW: Int,
    val dpH: Int,
    val groundY: Int,
    val anchorX: Float,
    val anchorY: Float,
)

/** `assets/minicar/compose.json`(설계서 §2.3). 순수 코틀린 + org.json이라 JVM 테스트에서 그대로 읽는다. */
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
) {
    fun spec(view: VehicleView): ViewSpec = if (view == VehicleView.Hero) hero else navi

    fun naviScaleOf(vehicleId: String): Float = naviScale[vehicleId] ?: 1f

    /** 로드아웃이 그릴 레이어 파일명(매니페스트 order 순). */
    fun layersFor(loadout: Loadout): List<String> = layersFor(loadout, order)

    companion object {
        const val DIR = "minicar"
        const val PATH = "$DIR/compose.json"

        /** 설계서 §2.3의 그리기 순서. 매니페스트를 아직 못 읽었을 때의 기본값. */
        val DEFAULT_ORDER: List<String> = listOf(
            "shadow", "body", "spec", "fixed", "wheel", "lamp", "roof_twotone", "roof_box", "decal_stripe", "plate_gold",
        )

        fun assetPath(vehicleId: String, view: VehicleView, layer: String): String = "$DIR/$vehicleId/${view.dir}/$layer.webp"

        /**
         * order의 항목을 로드아웃에 맞춰 파일명으로 바꾼다. "wheel"은 선택한 휠 1장(wheel_<id>),
         * 루프·데칼·번호판은 선택했을 때만 들어간다. 트레일은 에셋이 아니라 지도 렌더라 여기 없다.
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
                order = o.getJSONArray("order").let { a -> List(a.length()) { a.getString(it) } },
                blend = blend,
                labels = labels,
                naviScale = scales,
                layers = o.getJSONArray("layers").let { a -> List(a.length()) { a.getString(it) } },
                provenance = o.optString("provenance", ""),
            )
        }
    }
}
