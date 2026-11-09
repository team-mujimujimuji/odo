package com.mujimujimuji.odo.ui.vehicle

import com.mujimujimuji.odo.cosmetic.Loadout
import com.mujimujimuji.odo.cosmetic.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 에셋 폴더(app/src/main/assets/minicar)를 JVM에서 직접 읽어 매니페스트·파일·총량을 검사한다. */
class VehicleManifestTest {

    private val assetsDir: File = listOf("src/main/assets/minicar", "app/src/main/assets/minicar")
        .map(::File).firstOrNull { it.isDirectory }
        ?: error("assets/minicar 폴더를 찾지 못함(작업 디렉터리: ${File(".").absolutePath})")

    private val manifest = VehicleManifest.parse(File(assetsDir, "compose.json").readText())

    @Test
    fun `매니페스트 구조는 설계서 2_3과 같다`() {
        assertEquals(1, manifest.version)
        assertEquals(ViewSpec(576, 384, 288, 192, 336, 288f, 192f), manifest.hero)
        assertEquals(ViewSpec(192, 192, 96, 96, 192, 96f, 104f), manifest.navi)
        assertEquals(VehicleManifest.DEFAULT_ORDER, manifest.order)
        assertEquals(12, manifest.layers.size)
        assertEquals(VehicleType.entries.map { it.id }.toSet(), manifest.labels.keys)
        VehicleType.entries.forEach { assertEquals(it.label, manifest.labels[it.id]) }
        assertEquals(1.25f, manifest.naviScaleOf("scooter"))
        assertEquals(1f, manifest.naviScaleOf("compact"))
        assertEquals(1f, manifest.naviScaleOf("unknown"))
        assertEquals("modulate", manifest.blend["body"])
        assertEquals("screen", manifest.blend["spec"])
        assertTrue(manifest.provenance.isNotBlank())
    }

    @Test
    fun `레이어 파일 120장이 있고 총량은 4 MiB 이하`() {
        var count = 0
        var blocksKiB = 0L
        for (v in VehicleType.entries) for (view in VehicleView.entries) for (layer in manifest.layers) {
            val f = File(assetsDir.parentFile, VehicleManifest.assetPath(v.id, view, layer))
            assertTrue("missing ${f.path}", f.isFile && f.length() > 0)
            count++
            blocksKiB += (f.length() + 4095) / 4096 * 4
        }
        assertEquals(120, count)
        assertTrue("assets/minicar = $blocksKiB KiB", blocksKiB <= 4096)
        val extra = assetsDir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.count()
        assertEquals("웹피 120 + compose.json 1", 121, extra)
    }

    @Test
    fun `layersFor는 기본 6장, 옵션은 선택했을 때만`() {
        assertEquals(listOf("shadow", "body", "spec", "fixed", "wheel_steel", "lamp"), manifest.layersFor(Loadout.default))
        val full = Loadout(wheelId = "dia", roofId = "twotone", decalId = "stripe", plateId = "gold")
        assertEquals(
            listOf("shadow", "body", "spec", "fixed", "wheel_dia", "lamp", "roof_twotone", "decal_stripe", "plate_gold"),
            manifest.layersFor(full),
        )
        val boxed = Loadout(wheelId = "aero", roofId = "box")
        assertEquals(listOf("shadow", "body", "spec", "fixed", "wheel_aero", "lamp", "roof_box"), manifest.layersFor(boxed))
        // 만들어지는 이름은 전부 매니페스트 레이어 목록 안에 있다.
        (manifest.layersFor(full) + manifest.layersFor(boxed)).forEach { assertTrue(it, it in manifest.layers) }
        assertEquals(manifest.layersFor(Loadout.default), VehicleManifest.layersFor(Loadout.default))
    }

    @Test
    fun `에셋 경로`() {
        assertEquals("minicar/suv/navi/wheel_dia.webp", VehicleManifest.assetPath("suv", VehicleView.Navi, "wheel_dia"))
        assertEquals("minicar/compose.json", VehicleManifest.PATH)
    }
}
