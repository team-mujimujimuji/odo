package com.mujimujimuji.odo.cosmetic

import com.mujimujimuji.odo.cosmetic.CosmeticCatalog.NONE
import com.mujimujimuji.odo.engine.LevelTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadoutTest {

    private val fullKit = Loadout(
        vehicle = VehicleType.SEDAN,
        colorId = "cherry",
        wheelId = "dia",
        lampId = "warm",
        roofId = "twotone",
        decalId = "stripe",
        plateId = "gold",
        trailId = "ribbon",
    )

    @Test
    fun `Lv1은 차종만 남기고 전부 기본값`() {
        assertEquals(Loadout.default.copy(vehicle = VehicleType.SEDAN), fullKit.resolve(1))
    }

    @Test
    fun `Lv5는 색만 열린다`() {
        // 레벨 5 해금은 미니카 색상 5종뿐(기획서 레벨 표). 다이아 휠·웜 램프는 Lv 10.
        val r = fullKit.resolve(5)
        assertEquals("cherry", r.colorId)
        assertEquals("steel", r.wheelId)
        assertEquals("white", r.lampId)
        assertEquals(NONE, r.roofId)
        assertEquals(NONE, r.decalId)
        assertEquals(NONE, r.plateId)
        assertEquals(NONE, r.trailId)
    }

    @Test
    fun `Lv10은 투톤 루프·트레일·다이아 휠·웜 램프가 더 열린다`() {
        val r = fullKit.resolve(10)
        assertEquals("twotone", r.roofId)
        assertEquals("ribbon", r.trailId)
        assertEquals("dia", r.wheelId)
        assertEquals("warm", r.lampId)
        assertEquals(NONE, r.decalId)
        assertEquals(NONE, r.plateId)
    }

    @Test
    fun `Lv20은 에어로·아이스·루프박스·데칼`() {
        val kit = Loadout(wheelId = "aero", lampId = "ice", roofId = "box", decalId = "stripe", plateId = "gold")
        val r = kit.resolve(20)
        assertEquals(kit.copy(plateId = NONE), r)
        assertEquals("sky", kit.copy(colorId = "sand").resolve(20).colorId)
        assertEquals("sand", kit.copy(colorId = "sand").resolve(30).colorId)
    }

    @Test
    fun `Lv50은 전부 유지`() {
        assertEquals(fullKit, fullKit.resolve(50))
    }

    @Test
    fun `모르는 id는 슬롯 기본값`() {
        val odd = Loadout(colorId = "neon", wheelId = "x", lampId = "y", roofId = "z", decalId = "q", plateId = "w", trailId = "v")
        assertEquals(Loadout.default, odd.resolve(50))
        assertEquals(VehicleType.COMPACT, VehicleType.byId("truck"))
        assertEquals(VehicleType.COMPACT, VehicleType.byId(null))
        assertEquals(VehicleType.SCOOTER, VehicleType.byId("scooter"))
    }

    @Test
    fun `withItem과 idOf는 슬롯마다 맞물린다`() {
        Slot.entries.forEach { slot ->
            val id = if (slot == Slot.VEHICLE) "suv" else "probe"
            assertEquals(slot.id, id, Loadout.default.withItem(slot, id).idOf(slot))
        }
        assertEquals(VehicleType.SUV, Loadout.default.withItem(Slot.VEHICLE, "suv").vehicle)
        assertEquals(Slot.ROOF, Slot.byId("roof"))
        assertEquals(null, Slot.byId("hat"))
    }

    @Test
    fun `수집 수는 24, 데모 Lv4는 8, Lv5는 13`() {
        assertEquals(28, CosmeticCatalog.items.size)
        assertEquals(24, CosmeticCatalog.collectibleTotal)
        assertEquals(8, CosmeticCatalog.collected(4))
        assertEquals(13, CosmeticCatalog.collected(5))
        assertEquals(17, CosmeticCatalog.collected(10))
        assertEquals(21, CosmeticCatalog.collected(20))
        assertEquals(23, CosmeticCatalog.collected(30))
        assertEquals(24, CosmeticCatalog.collected(50))
    }

    @Test
    fun `키는 유일하고 해금표의 키는 모두 카탈로그에 있다`() {
        val keys = CosmeticCatalog.items.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        LevelTable.cosmeticUnlocks.keys.forEach { assertNotNull(it, CosmeticCatalog.byKey(it)) }
        // 잠긴 항목(Lv1 초과)은 정확히 해금표의 16개.
        val locked = CosmeticCatalog.items.filter { it.unlockLevel > 1 }.map { it.key }.toSet()
        assertEquals(LevelTable.cosmeticUnlocks.keys, locked)
        // 슬롯마다 기본값이 있고 Lv1에 열려 있다.
        Slot.entries.forEach { slot ->
            val d = CosmeticCatalog.defaultOf(slot)
            assertEquals(slot.id, 1, d.unlockLevel)
            assertEquals(d, CosmeticCatalog.item(slot, "no-such-id"))
        }
        // 색 항목은 전부 argb를 가진다(휠·루프 등은 없음).
        assertTrue(CosmeticCatalog.bySlot(Slot.COLOR).all { it.argb != null })
        assertTrue(CosmeticCatalog.bySlot(Slot.LAMP).all { it.argb != null })
        assertTrue(CosmeticCatalog.bySlot(Slot.WHEEL).all { it.argb == null })
    }
}
