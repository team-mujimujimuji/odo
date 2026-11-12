package com.mujimujimuji.odo.ui.vehicle

import androidx.compose.ui.geometry.Offset
import com.mujimujimuji.odo.cosmetic.Loadout
import com.mujimujimuji.odo.cosmetic.VehicleType
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.ui.map.CorridorData
import com.mujimujimuji.odo.ui.map.MapCamera
import com.mujimujimuji.odo.ui.map.bandAnchor
import com.mujimujimuji.odo.ui.map.homeHeadingDeg
import com.mujimujimuji.odo.ui.map.lastHeadingDeg
import com.mujimujimuji.odo.ui.map.routeBearingDeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * 미니카 에셋 계약 v2(compose.json version 2, 설계서 §2.5·§4.3)를 검사한다.
 * - 계약 fixture([V2_JSON]) 테스트: 파싱, 뷰별 skip, night, 그리기 순서 해석, 크기·기준점·회전([VehiclePose]), 홈 배치(순수 함수).
 *   에셋 폴더 상태와 상관없이 통과한다.
 * - `실제 에셋…` 테스트: 에셋 폴더(app/src/main/assets/minicar)를 JVM에서 직접 읽는다.
 *   "계약 v2와 같다", "165장", "투톤은 스트라이프 위" 세 개는 v2 에셋이 들어와야 통과한다(v1 에셋에서는 실패).
 *   환경 변수 `ODO_MINICAR_ASSETS`에 폴더를 주면 그 폴더(예: 후처리 후보)를 대신 검사한다. 앱에 넣기 전에 돌려 볼 때 쓴다.
 */
class VehicleManifestTest {

    private companion object {
        const val PROVENANCE = "특정 차종을 본뜨지 않고 차급 비례를 일반화해 자체 제작한 스타일라이즈드 모델. 엠블럼·명칭을 재현하지 않음."

        /** 에셋 계약 v2의 compose.json(provenance만 지금 문구를 채웠다). */
        val V2_JSON = """
            {
             "version": 2,
             "views": {
              "hero": {"px": [576, 384], "dp": [288, 192], "groundY": 336, "skip": ["glow_tail"]},
              "navi": {"px": [256, 320], "dp": [128, 160], "anchor": [128, 200], "skip": ["wheel_steel", "wheel_dia", "wheel_aero", "lamp"]},
              "top":  {"px": [256, 384], "dp": [128, 192], "anchor": [128, 192], "skip": ["wheel_steel", "wheel_dia", "wheel_aero", "plate_gold"]}
             },
             "order": ["shadow", "body", "spec", "decal_stripe", "roof_twotone", "fixed", "wheel", "lamp", "roof_box", "plate_gold", "glow_head", "glow_tail"],
             "blend": {"body": "modulate", "spec": "screen", "lamp": "modulate", "roof_twotone": "modulate", "decal_stripe": "modulate", "glow_head": "screen", "glow_tail": "screen"},
             "night": ["glow_head", "glow_tail"],
             "vehicles": {"compact": {"label": "소형", "naviScale": 1.0}, "suv": {"label": "중형", "naviScale": 1.0}, "large": {"label": "대형", "naviScale": 1.0}, "sedan": {"label": "세단", "naviScale": 1.0}, "scooter": {"label": "2륜", "naviScale": 1.25}},
             "layers": ["shadow", "body", "spec", "fixed", "wheel_steel", "wheel_dia", "wheel_aero", "lamp", "roof_twotone", "roof_box", "decal_stripe", "plate_gold", "glow_head", "glow_tail"],
             "provenance": "$PROVENANCE", "generator": "tools/minicar/build_minicar.py", "blender": "4.5.9", "samples": 128
            }
        """.trimIndent()

        /** v1 compose.json(뷰 2개, skip·night 없음). 앱은 이것도 읽어야 한다. */
        val V1_JSON = """
            {
             "version": 1,
             "views": {"hero": {"px": [576, 384], "dp": [288, 192], "groundY": 336}, "navi": {"px": [192, 192], "dp": [96, 96], "anchor": [96, 104]}},
             "order": ["shadow", "body", "spec", "fixed", "wheel", "lamp", "roof_twotone", "roof_box", "decal_stripe", "plate_gold"],
             "blend": {"body": "modulate", "spec": "screen", "lamp": "modulate", "roof_twotone": "modulate", "decal_stripe": "modulate"},
             "vehicles": {"compact": {"label": "소형", "naviScale": 1.0}, "suv": {"label": "중형", "naviScale": 1.0}, "large": {"label": "대형", "naviScale": 1.0}, "sedan": {"label": "세단", "naviScale": 1.0}, "scooter": {"label": "2륜", "naviScale": 1.25}},
             "layers": ["shadow", "body", "spec", "fixed", "wheel_steel", "wheel_dia", "wheel_aero", "lamp", "roof_twotone", "roof_box", "decal_stripe", "plate_gold"],
             "provenance": "$PROVENANCE", "generator": "tools/minicar/build_minicar.py", "blender": "4.5.9", "samples": 128
            }
        """.trimIndent()

        val v2: VehicleManifest = VehicleManifest.parse(V2_JSON)
        val v1: VehicleManifest = VehicleManifest.parse(V1_JSON)

        val plain = Loadout.default
        val full = Loadout(wheelId = "dia", roofId = "twotone", decalId = "stripe", plateId = "gold")
        val boxed = Loadout(wheelId = "aero", roofId = "box")
        val loadouts = listOf(plain, full, boxed)

        const val WINDOW_W = 656f
        const val WINDOW_H = 548f

        // 실제 파일은 쓰는 테스트에서만 읽는다(fixture 테스트가 에셋 폴더 상태에 기대지 않게).
        val assetsDir: File by lazy {
            val override = System.getenv("ODO_MINICAR_ASSETS")?.takeIf { it.isNotBlank() }
            if (override != null) {
                File(override).also { check(it.isDirectory) { "ODO_MINICAR_ASSETS 폴더가 없음: $override" } }
            } else {
                listOf("src/main/assets/minicar", "app/src/main/assets/minicar")
                    .map(::File).firstOrNull { it.isDirectory }
                    ?: error("assets/minicar 폴더를 찾지 못함(작업 디렉터리: ${File(".").absolutePath})")
            }
        }
        val real: VehicleManifest by lazy { VehicleManifest.parse(File(assetsDir, "compose.json").readText()) }
        val corridor: CorridorData by lazy {
            val file = listOf("src/main/assets/", "app/src/main/assets/")
                .map { File(it + CorridorData.ASSET_PATH) }
                .first { it.exists() }
            CorridorData(file.readText())
        }

        /** 에셋 폴더 안의 레이어 파일(앱 경로 `minicar/<차종>/<뷰>/<레이어>.webp`에서 앞의 `minicar/`를 뗀 자리). */
        fun assetFile(vehicleId: String, view: VehicleView, layer: String): File =
            File(assetsDir, VehicleManifest.assetPath(vehicleId, view, layer).removePrefix("${VehicleManifest.DIR}/"))
    }

    // ── 계약 v2 fixture: 매니페스트 ─────────────────────────────────────────────

    @Test
    fun `계약 v2 파싱 뷰 3개와 순서·레이어·차종`() {
        assertEquals(2, v2.version)
        assertEquals(ViewSpec(576, 384, 288, 192, 336, 288f, 192f, setOf("glow_tail")), v2.hero)
        assertEquals(
            ViewSpec(256, 320, 128, 160, 320, 128f, 200f, setOf("wheel_steel", "wheel_dia", "wheel_aero", "lamp")),
            v2.navi,
        )
        assertEquals(
            ViewSpec(256, 384, 128, 192, 384, 128f, 192f, setOf("wheel_steel", "wheel_dia", "wheel_aero", "plate_gold")),
            v2.top,
        )
        assertEquals(VehicleView.entries.toList(), v2.views)
        VehicleView.entries.forEach { assertEquals(it, v2.assetView(it)) }
        assertEquals(v2.top, v2.spec(VehicleView.Top))
        assertEquals(VehicleManifest.DEFAULT_ORDER, v2.order)
        assertEquals(12, v2.order.size)
        assertEquals(14, v2.layers.size)
        assertEquals(setOf("glow_head", "glow_tail"), v2.night)
        assertEquals(VehicleType.entries.map { it.id }.toSet(), v2.labels.keys)
        VehicleType.entries.forEach { assertEquals(it.label, v2.labels[it.id]) }
        assertEquals(1.25f, v2.naviScaleOf("scooter"))
        assertEquals(1f, v2.naviScaleOf("compact"))
        assertEquals(1f, v2.naviScaleOf("unknown"))
        assertEquals(PROVENANCE, v2.provenance)
    }

    @Test
    fun `뷰별 파일은 13·10·10장, 5차종 165장`() {
        assertEquals(13, v2.filesFor(VehicleView.Hero).size)
        assertEquals(10, v2.filesFor(VehicleView.Navi).size)
        assertEquals(10, v2.filesFor(VehicleView.Top).size)
        assertEquals(165, VehicleType.entries.size * VehicleView.entries.sumOf { v2.filesFor(it).size })
        assertFalse("glow_tail" in v2.filesFor(VehicleView.Hero))
        assertFalse("lamp" in v2.filesFor(VehicleView.Navi))
        assertFalse("plate_gold" in v2.filesFor(VehicleView.Top))
        // 휠 3종은 hero에만 있다.
        for (wheel in listOf("wheel_steel", "wheel_dia", "wheel_aero")) {
            assertTrue(wheel in v2.filesFor(VehicleView.Hero))
            assertFalse(wheel in v2.filesFor(VehicleView.Navi))
            assertFalse(wheel in v2.filesFor(VehicleView.Top))
        }
        // skip·night에 적힌 이름은 전부 레이어 목록 안에 있다.
        VehicleView.entries.forEach { view -> v2.spec(view).skip.forEach { assertTrue(it, it in v2.layers) } }
        v2.night.forEach { assertTrue(it, it in v2.layers) }
    }

    @Test
    fun `그리기 순서 해석 기본 로드아웃과 선택 레이어, 뷰별 skip`() {
        // 낮. hero는 전부, navi는 휠·전조등 없이, top은 휠·번호판 없이.
        assertEquals(
            listOf("shadow", "body", "spec", "fixed", "wheel_steel", "lamp"),
            v2.layersFor(plain, VehicleView.Hero, night = false),
        )
        assertEquals(listOf("shadow", "body", "spec", "fixed"), v2.layersFor(plain, VehicleView.Navi, night = false))
        assertEquals(listOf("shadow", "body", "spec", "fixed", "lamp"), v2.layersFor(plain, VehicleView.Top, night = false))

        assertEquals(
            listOf("shadow", "body", "spec", "decal_stripe", "roof_twotone", "fixed", "wheel_dia", "lamp", "plate_gold"),
            v2.layersFor(full, VehicleView.Hero, night = false),
        )
        assertEquals(
            listOf("shadow", "body", "spec", "decal_stripe", "roof_twotone", "fixed", "plate_gold"),
            v2.layersFor(full, VehicleView.Navi, night = false),
        )
        assertEquals(
            listOf("shadow", "body", "spec", "decal_stripe", "roof_twotone", "fixed", "lamp"),
            v2.layersFor(full, VehicleView.Top, night = false),
        )

        assertEquals(
            listOf("shadow", "body", "spec", "fixed", "wheel_aero", "lamp", "roof_box"),
            v2.layersFor(boxed, VehicleView.Hero, night = false),
        )
        assertEquals(listOf("shadow", "body", "spec", "fixed", "roof_box"), v2.layersFor(boxed, VehicleView.Navi, night = false))
        assertEquals(listOf("shadow", "body", "spec", "fixed", "lamp", "roof_box"), v2.layersFor(boxed, VehicleView.Top, night = false))

        // 깊이 순서: 차체에 붙은 스트라이프가 먼저, 그 위에 얹힌 루프 박스가 나중(박스가 스트라이프를 가린다).
        assertEquals(
            listOf("shadow", "body", "spec", "decal_stripe", "fixed", "lamp", "roof_box"),
            v2.layersFor(Loadout(roofId = "box", decalId = "stripe"), VehicleView.Top, night = false),
        )

        // 매니페스트를 읽기 전의 기본 순서도 계약 v2와 같다.
        assertEquals(VehicleManifest.layersFor(full, v2.order), VehicleManifest.layersFor(full))
    }

    @Test
    fun `글로 레이어는 밤에만 들어간다`() {
        // hero는 앞모습이라 전조등 빛만, navi·top은 둘 다. 글로는 맨 위에 얹힌다.
        assertEquals(
            listOf("shadow", "body", "spec", "fixed", "wheel_steel", "lamp", "glow_head"),
            v2.layersFor(plain, VehicleView.Hero, night = true),
        )
        assertEquals(
            listOf("shadow", "body", "spec", "fixed", "glow_head", "glow_tail"),
            v2.layersFor(plain, VehicleView.Navi, night = true),
        )
        assertEquals(
            listOf("shadow", "body", "spec", "fixed", "lamp", "glow_head", "glow_tail"),
            v2.layersFor(plain, VehicleView.Top, night = true),
        )
        for (loadout in loadouts) for (view in VehicleView.entries) {
            val day = v2.layersFor(loadout, view, night = false)
            val night = v2.layersFor(loadout, view, night = true)
            assertTrue("$view 낮에 글로", day.none { it in v2.night })
            assertEquals("$view 밤 = 낮 + 글로", day, night.filter { it !in v2.night })
            assertEquals(day + night.filter { it in v2.night }, night)
        }
    }

    @Test
    fun `해석된 레이어는 모두 그 뷰에 파일이 있는 레이어다`() {
        for (loadout in loadouts) for (view in VehicleView.entries) for (night in listOf(false, true)) {
            val files = v2.filesFor(view)
            v2.layersFor(loadout, view, night).forEach { assertTrue("$view/$it", it in files) }
        }
    }

    @Test
    fun `레이어 그리기 방식은 매니페스트 blend 표와 같다`() {
        for (layer in v2.layers) assertEquals(layer, v2.blend[layer], LayerKind.of(layer).blend)
        v2.blend.keys.forEach { assertTrue(it, it in v2.layers) }
        assertEquals(LayerKind.Shadow, LayerKind.of("shadow"))
        assertEquals(LayerKind.Body, LayerKind.of("body"))
        assertEquals(LayerKind.Spec, LayerKind.of("spec"))
        assertEquals(LayerKind.Lamp, LayerKind.of("lamp"))
        assertEquals(LayerKind.Roof, LayerKind.of("roof_twotone"))
        assertEquals(LayerKind.Decal, LayerKind.of("decal_stripe"))
        assertEquals(LayerKind.GlowHead, LayerKind.of("glow_head"))
        assertEquals(LayerKind.GlowTail, LayerKind.of("glow_tail"))
        for (layer in listOf("fixed", "wheel_steel", "wheel_dia", "wheel_aero", "roof_box", "plate_gold")) {
            assertEquals(layer, LayerKind.Plain, LayerKind.of(layer))
        }
        // 밤에만 그리는 레이어는 전부 글로(Screen)다.
        v2.night.forEach { assertEquals(it, "screen", LayerKind.of(it).blend) }
    }

    // ── 계약 v2 fixture: 크기·기준점·회전 ───────────────────────────────────────

    @Test
    fun `Hero는 상자에 맞춰 줄이고 돌리지 않는다`() {
        // 차고 미리보기 228×152dp(밀도 1). 배율·차종 배율·heading은 hero에 쓰이지 않는다.
        val pose = VehiclePose.of(
            v2.hero, VehicleView.Hero, boxW = 228f, boxH = 152f, density = 1f,
            markerScale = 3f, naviScale = 1.25f, headingDeg = 90f,
        )
        assertEquals(228f / 576f, pose.scale, 1e-6f)
        assertEquals(0f, pose.rotationDeg)
        // 그림 중심이 상자 중심, 네 귀퉁이가 상자 귀퉁이.
        assertEquals(114f, pose.screenX(288f, 192f), 1e-3f)
        assertEquals(76f, pose.screenY(288f, 192f), 1e-3f)
        assertEquals(0f, pose.screenX(0f, 0f), 1e-3f)
        assertEquals(0f, pose.screenY(0f, 0f), 1e-3f)
        assertEquals(228f, pose.screenX(576f, 384f), 1e-3f)
        assertEquals(152f, pose.screenY(576f, 384f), 1e-3f)
        // 3:2가 아닌 상자면 짧은 쪽에 맞춘다(300×100 → 높이 100에 맞춰 150×100, 가로 가운데).
        val wide = VehiclePose.of(v2.hero, VehicleView.Hero, boxW = 300f, boxH = 100f, density = 2f)
        assertEquals(100f / 384f, wide.scale, 1e-6f)
        assertEquals(75f, wide.screenX(0f, 0f), 1e-3f)
        assertEquals(225f, wide.screenX(576f, 384f), 1e-3f)
        assertEquals(100f, wide.screenY(576f, 384f), 1e-3f)
    }

    @Test
    fun `Navi는 원래 크기에 배율만 곱하고 돌리지 않는다`() {
        // 96dp 상자(밀도 1): 256×320px 그림이 128×160dp로, 기준점 (128,200)이 상자 중심 (48,48)에.
        val pose = VehiclePose.of(v2.navi, VehicleView.Navi, boxW = 96f, boxH = 96f, density = 1f)
        assertEquals(0.5f, pose.scale, 1e-6f)
        assertEquals(0f, pose.rotationDeg)
        assertEquals(48f, pose.screenX(128f, 200f), 1e-3f)
        assertEquals(48f, pose.screenY(128f, 200f), 1e-3f)
        assertEquals(48f - 64f, pose.screenX(0f, 200f), 1e-3f)
        assertEquals(48f + 64f, pose.screenX(256f, 200f), 1e-3f)
        assertEquals(48f - 100f, pose.screenY(128f, 0f), 1e-3f)
        assertEquals(48f + 60f, pose.screenY(128f, 320f), 1e-3f)
        // 상자 크기와 무관하다. 기준점은 언제나 상자 중심.
        val big = VehiclePose.of(v2.navi, VehicleView.Navi, boxW = WINDOW_W, boxH = WINDOW_H, density = 1f)
        assertEquals(0.5f, big.scale, 1e-6f)
        assertEquals(WINDOW_W / 2f, big.screenX(128f, 200f), 1e-3f)
        assertEquals(WINDOW_H / 2f, big.screenY(128f, 200f), 1e-3f)
        // 밀도 2면 px 그대로, 2륜은 1.25배, markerScale은 그대로 곱한다.
        assertEquals(1f, VehiclePose.of(v2.navi, VehicleView.Navi, 192f, 192f, density = 2f).scale, 1e-6f)
        assertEquals(
            0.625f,
            VehiclePose.of(v2.navi, VehicleView.Navi, 96f, 96f, density = 1f, naviScale = v2.naviScaleOf("scooter")).scale,
            1e-6f,
        )
        assertEquals(
            0.5f * VehicleMarkerScale.GLANCE,
            VehiclePose.of(v2.navi, VehicleView.Navi, 96f, 96f, density = 1f, markerScale = VehicleMarkerScale.GLANCE).scale,
            1e-6f,
        )
        assertEquals(0.75f, VehiclePose.of(v2.navi, VehicleView.Navi, 96f, 96f, density = 1f, markerScale = 1.5f).scale, 1e-6f)
        // heading을 줘도 돌지 않는다(heading-up 지도에서는 차 앞이 항상 위).
        val turned = VehiclePose.of(v2.navi, VehicleView.Navi, 96f, 96f, density = 1f, headingDeg = 135f)
        assertEquals(0f, turned.rotationDeg)
        assertEquals(pose, turned)
    }

    @Test
    fun `Top은 차 중심을 축으로 heading만큼 돈다`() {
        val spec = v2.spec(VehicleView.Top)
        fun pose(heading: Float) = VehiclePose.of(spec, VehicleView.Top, boxW = 96f, boxH = 96f, density = 1f, headingDeg = heading)
        // 256×384px 그림이 128×192dp. 차 앞은 그림 위쪽 가운데 (128, 0).
        val north = pose(0f)
        assertEquals(0.5f, north.scale, 1e-6f)
        assertEquals(48f, north.screenX(128f, 0f), 1e-3f)
        assertEquals(48f - 96f, north.screenY(128f, 0f), 1e-3f)
        // 동쪽(90°): 차 앞이 화면 오른쪽, 차 오른쪽 옆구리 (256,192)는 화면 아래(남쪽).
        val east = pose(90f)
        assertEquals(90f, east.rotationDeg)
        assertEquals(48f + 96f, east.screenX(128f, 0f), 1e-3f)
        assertEquals(48f, east.screenY(128f, 0f), 1e-3f)
        assertEquals(48f, east.screenX(256f, 192f), 1e-3f)
        assertEquals(48f + 64f, east.screenY(256f, 192f), 1e-3f)
        // 남쪽(180°): 아래, 서쪽(270°): 왼쪽.
        assertEquals(48f + 96f, pose(180f).screenY(128f, 0f), 1e-3f)
        assertEquals(48f - 96f, pose(270f).screenX(128f, 0f), 1e-3f)
        // 차 중심(회전축)은 방향과 상관없이 상자 중심.
        for (heading in listOf(0f, 37f, 90f, 180f, 270f, 359f)) {
            assertEquals(48f, pose(heading).screenX(128f, 192f), 1e-3f)
            assertEquals(48f, pose(heading).screenY(128f, 192f), 1e-3f)
        }
        // 화면별 배율: 홈 1.0, 트립 요약 0.8. 2륜은 여기에 1.25배.
        assertEquals(1f, VehicleMarkerScale.HOME)
        assertEquals(1f, VehicleMarkerScale.GLANCE)
        assertEquals(0.8f, VehicleMarkerScale.SUMMARY)
        assertEquals(0.5f, VehiclePose.of(spec, VehicleView.Top, 96f, 96f, 1f, markerScale = VehicleMarkerScale.HOME).scale, 1e-6f)
        assertEquals(0.4f, VehiclePose.of(spec, VehicleView.Top, 96f, 96f, 1f, markerScale = VehicleMarkerScale.SUMMARY).scale, 1e-6f)
        assertEquals(
            0.5f,
            VehiclePose.of(spec, VehicleView.Top, 96f, 96f, 1f, VehicleMarkerScale.SUMMARY, v2.naviScaleOf("scooter")).scale,
            1e-6f,
        )
    }

    @Test
    fun `v1 매니페스트도 읽고 Top 요청은 navi 그림을 돌려 그린다`() {
        assertEquals(1, v1.version)
        assertEquals(ViewSpec(576, 384, 288, 192, 336, 288f, 192f), v1.hero)
        assertEquals(ViewSpec(192, 192, 96, 96, 192, 96f, 104f), v1.navi)
        assertNull(v1.top)
        assertTrue(v1.night.isEmpty())
        assertEquals(listOf(VehicleView.Hero, VehicleView.Navi), v1.views)
        assertEquals(VehicleView.Navi, v1.assetView(VehicleView.Top))
        assertEquals(VehicleView.Navi, v1.assetView(VehicleView.Navi))
        assertEquals(VehicleView.Hero, v1.assetView(VehicleView.Hero))
        assertEquals(v1.navi, v1.spec(VehicleView.Top))
        assertTrue(v1.filesFor(VehicleView.Top).isEmpty())
        assertEquals(12, v1.filesFor(VehicleView.Navi).size)
        // 레이어는 예전 그대로이고 밤에도 글로가 없다.
        val six = listOf("shadow", "body", "spec", "fixed", "wheel_steel", "lamp")
        for (view in VehicleView.entries) for (night in listOf(false, true)) assertEquals(six, v1.layersFor(plain, view, night))
        assertEquals(
            listOf("shadow", "body", "spec", "fixed", "wheel_dia", "lamp", "roof_twotone", "decal_stripe", "plate_gold"),
            v1.layersFor(full, VehicleView.Top, night = true),
        )
        // 크기는 예전과 같은 96dp(192px × 0.5), Top 요청이라 heading으로 돈다.
        val pose = VehiclePose.of(v1.spec(VehicleView.Top), VehicleView.Top, boxW = 96f, boxH = 96f, density = 1f, headingDeg = 90f)
        assertEquals(0.5f, pose.scale, 1e-6f)
        assertEquals(90f, pose.rotationDeg)
        assertEquals(48f, pose.screenX(96f, 104f), 1e-3f)
        assertEquals(48f, pose.screenY(96f, 104f), 1e-3f)
    }

    @Test
    fun `에셋 경로`() {
        assertEquals("minicar/suv/hero/wheel_dia.webp", VehicleManifest.assetPath("suv", VehicleView.Hero, "wheel_dia"))
        assertEquals("minicar/suv/navi/body.webp", VehicleManifest.assetPath("suv", VehicleView.Navi, "body"))
        assertEquals("minicar/scooter/top/glow_tail.webp", VehicleManifest.assetPath("scooter", VehicleView.Top, "glow_tail"))
        assertEquals("minicar/compose.json", VehicleManifest.PATH)
    }

    // ── 계약 v2 fixture: 배치(홈) ───────────────────────────────────────────────

    @Test
    fun `홈 정차 카메라는 현재 위치를 보이는 띠 가운데에 둔다`() {
        // 656×548dp 창: 위 캡션·칩 줄 56dp, 아래 패널 260dp → 띠 56~288dp, 가운데 y 172dp.
        val anchor = bandAnchor(WINDOW_H, 56f, 260f)
        assertEquals(0.5f, anchor.x)
        assertEquals(172f, anchor.y * WINDOW_H, 1e-3f)
        // 알림 줄(40 + 사이 4)이 패널 위에 있으면 띠 56~244dp, 가운데 150dp.
        assertEquals(150f, bandAnchor(WINDOW_H, 56f, 304f).y * WINDOW_H, 1e-3f)
        // 비율이라 화면 밀도와 무관하다.
        assertEquals(anchor.y, bandAnchor(WINDOW_H * 2f, 112f, 520f).y, 1e-6f)
        // 띠가 없으면 화면 가운데.
        assertEquals(Offset(0.5f, 0.5f), bandAnchor(WINDOW_H, 300f, 300f))
        assertEquals(Offset(0.5f, 0.5f), bandAnchor(0f, 0f, 0f))

        val at = LatLng(37.2384, 127.569596)
        val camera = MapCamera.parked(at).copy(headingDeg = 73f, parkedAnchor = anchor)
        assertTrue(camera.northUp)
        assertEquals(at, camera.center)
        assertEquals(MapCamera.PARKED_METERS_PER_DP, camera.metersPerDp)
        assertEquals(73f, camera.headingDeg)
        val pos = camera.anchor(WINDOW_W, WINDOW_H)
        assertEquals(328f, pos.x, 1e-3f)
        assertEquals(172f, pos.y, 1e-3f)
        // north-up 카메라의 heading은 미니카 방향일 뿐, 지도 좌표는 돌지 않는다: 북쪽 1 km는 화면 바로 위 50dp.
        val north = corridor.toLatLng(corridor.localX(at), corridor.localY(at) + 1_000.0)
        val up = camera.toScreenDp(corridor, north, WINDOW_W, WINDOW_H)
        assertEquals(328f, up.x, 0.05f)
        assertEquals(172f - 50f, up.y, 0.05f)
        // Top 미니카(128×192dp, 어느 방향이든 반대각 115.4dp 안)가 패널 위 선(288dp)을 넘지 않는다.
        val spec = v2.spec(VehicleView.Top)
        for (heading in 0 until 360 step 15) {
            val pose = VehiclePose.of(spec, VehicleView.Top, 96f, 96f, 1f, VehicleMarkerScale.HOME, headingDeg = heading.toFloat())
            for ((x, y) in listOf(0f to 0f, 256f to 0f, 0f to 384f, 256f to 384f)) {
                val bottom = pos.y - 48f + pose.screenY(x, y)
                assertTrue("heading $heading: $bottom", bottom <= WINDOW_H - 260f)
            }
        }
    }

    @Test
    fun `홈 미니카 방향은 주행 heading, 경로 방위, 0 순서`() {
        val start = corridor.routeStart
        val bearing = routeBearingDeg(corridor, start)
        assertTrue("경로 시작점의 방위", bearing != null)
        // 주행에서 얻은 heading이 있으면 그대로(0°도 값이다).
        assertEquals(123f, homeHeadingDeg(123f, corridor, start))
        assertEquals(0f, homeHeadingDeg(0f, corridor, start))
        // 없으면 경로 방위.
        assertEquals(bearing!!, homeHeadingDeg(null, corridor, start))
        // 지도 데이터·위치가 없거나 경로에서 멀면 0(북).
        assertEquals(0f, homeHeadingDeg(null, null, start))
        assertEquals(0f, homeHeadingDeg(null, corridor, null))
        assertEquals(0f, homeHeadingDeg(null, corridor, MapCamera.NOWHERE))
    }

    @Test
    fun `경로 방위는 가장 가까운 경로 지점의 진행 방향이고 멀면 없다`() {
        val start = corridor.routeStart
        val bearing = routeBearingDeg(corridor, start)!!
        assertTrue(bearing in 0f..360f)
        // 경로 첫 30 m를 위경도로 따로 잰 방위와 같다.
        val p = DoubleArray(2)
        corridor.routePoint(30.0, p)
        assertEquals(lastHeadingDeg(listOf(start, corridor.toLatLng(p[0], p[1]))), bearing, 1f)
        // 시작점 뒤로 200 m(가장 가까운 경로 지점 = 시작점): 같은 방위. 1 km 뒤: 경로에서 멀어 없다.
        val rad = Math.toRadians(bearing.toDouble())
        fun behind(m: Double) = corridor.toLatLng(corridor.localX(start) - m * sin(rad), corridor.localY(start) - m * cos(rad))
        assertEquals(bearing, routeBearingDeg(corridor, behind(200.0))!!, 0.01f)
        assertNull(routeBearingDeg(corridor, behind(1_000.0)))
        assertNull(routeBearingDeg(corridor, MapCamera.NOWHERE))
        assertNull(routeBearingDeg(null, start))
        assertNull(routeBearingDeg(corridor, null))
        // 경로 끝(데모의 휴게소 정차 지점)에서도 방위가 나온다.
        corridor.routePoint(corridor.routeLengthM, p)
        assertTrue(routeBearingDeg(corridor, corridor.toLatLng(p[0], p[1])) != null)
    }

    // ── 실제 에셋 ───────────────────────────────────────────────────────────────

    @Test
    fun `실제 에셋으로 요청한 뷰·로드아웃의 레이어 파일이 모두 있다`() {
        // v1 에셋(top 없음)이든 v2 에셋이든, 앱이 읽으려는 파일은 빠짐없이 있어야 한다.
        for (v in VehicleType.entries) for (view in VehicleView.entries) for (loadout in loadouts) for (night in listOf(false, true)) {
            val layers = real.layersFor(loadout.copy(vehicle = v), view, night)
            assertTrue("${v.id}/$view: 그릴 레이어가 없음", layers.isNotEmpty())
            for (layer in layers) {
                val f = assetFile(v.id, real.assetView(view), layer)
                assertTrue("missing ${f.path}", f.isFile && f.length() > 0)
            }
        }
    }

    @Test
    fun `실제 에셋 compose_json은 계약 v2와 같다`() {
        assertEquals("version", 2, real.version)
        assertEquals("views.hero", v2.hero, real.hero)
        assertEquals("views.navi", v2.navi, real.navi)
        assertEquals("views.top", v2.top, real.top)
        assertEquals("order", v2.order, real.order)
        assertEquals("blend", v2.blend, real.blend)
        assertEquals("night", v2.night, real.night)
        assertEquals("layers", v2.layers, real.layers)
        assertEquals("vehicles.label", v2.labels, real.labels)
        assertEquals("vehicles.naviScale", v2.naviScale, real.naviScale)
        assertEquals("provenance", PROVENANCE, real.provenance)
        assertEquals(v2, real)
    }

    @Test
    fun `실제 에셋과 기본 순서에서 투톤은 스트라이프 위다`() {
        // 둘을 같이 켜면 투톤이 겹치는 자리(4륜 지붕, 2륜 레그실드·앞 펜더)의 줄을 덮는다. 순서가 뒤집히면 줄이 투톤 위로 올라온다.
        for ((where, order) in listOf("DEFAULT_ORDER" to VehicleManifest.DEFAULT_ORDER, "compose.json" to real.order)) {
            val stripe = order.indexOf("decal_stripe")
            val twotone = order.indexOf("roof_twotone")
            assertTrue("$where: decal_stripe 없음", stripe >= 0)
            assertTrue("$where: roof_twotone 없음", twotone >= 0)
            assertTrue("$where: roof_twotone($twotone)이 decal_stripe($stripe)보다 뒤(위)여야 한다", twotone > stripe)
        }
    }

    @Test
    fun `실제 에셋 파일은 뷰별 skip을 뺀 165장이고 총량은 4 MiB 이하`() {
        var count = 0
        var blocksKiB = 0L
        for (v in VehicleType.entries) for (view in VehicleView.entries) {
            val files = real.filesFor(view)
            for (layer in files) {
                val f = assetFile(v.id, view, layer)
                assertTrue("missing ${f.path}", f.isFile && f.length() > 0)
                count++
                blocksKiB += (f.length() + 4095) / 4096 * 4
            }
            // 뷰의 skip 레이어는 만들지 않는다.
            for (layer in real.layers - files.toSet()) {
                val f = assetFile(v.id, view, layer)
                assertFalse("skip 레이어 파일이 있음: ${f.path}", f.exists())
            }
        }
        assertEquals("웹피 수(차종당 13 + 10 + 10)", 165, count)
        assertTrue("assets/minicar = $blocksKiB KiB", blocksKiB <= 4096)
        val all = assetsDir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.count()
        assertEquals("웹피 165 + compose.json 1", 166, all)
    }
}
