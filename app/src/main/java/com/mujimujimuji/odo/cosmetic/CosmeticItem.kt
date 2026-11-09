package com.mujimujimuji.odo.cosmetic

import com.mujimujimuji.odo.engine.LevelTable

/** 꾸미기 항목. argb는 색을 가진 항목(페인트·램프)만 0xFFRRGGBB로 가진다. 해금 레벨은 LevelTable이 결정한다. */
data class CosmeticItem(val id: String, val slot: Slot, val label: String, val argb: Long? = null) {
    val key: String get() = "${slot.id}:$id"
    val unlockLevel: Int get() = LevelTable.unlockLevelOf(key)
}

/** 차종 하나의 꾸미기 선택값. 저장값을 그대로 담고, 화면에 쓰기 전 resolve(level)로 잠긴 항목을 기본값으로 되돌린다. */
data class Loadout(
    val vehicle: VehicleType = VehicleType.COMPACT,
    val colorId: String = "sky",
    val wheelId: String = "steel",
    val lampId: String = "white",
    val roofId: String = CosmeticCatalog.NONE,
    val decalId: String = CosmeticCatalog.NONE,
    val plateId: String = CosmeticCatalog.NONE,
    val trailId: String = CosmeticCatalog.NONE,
) {
    fun idOf(slot: Slot): String = when (slot) {
        Slot.VEHICLE -> vehicle.id
        Slot.COLOR -> colorId
        Slot.WHEEL -> wheelId
        Slot.LAMP -> lampId
        Slot.ROOF -> roofId
        Slot.DECAL -> decalId
        Slot.PLATE -> plateId
        Slot.TRAIL -> trailId
    }

    fun withItem(slot: Slot, id: String): Loadout = when (slot) {
        Slot.VEHICLE -> copy(vehicle = VehicleType.byId(id))
        Slot.COLOR -> copy(colorId = id)
        Slot.WHEEL -> copy(wheelId = id)
        Slot.LAMP -> copy(lampId = id)
        Slot.ROOF -> copy(roofId = id)
        Slot.DECAL -> copy(decalId = id)
        Slot.PLATE -> copy(plateId = id)
        Slot.TRAIL -> copy(trailId = id)
    }

    /** 레벨에 아직 잠긴 항목과 카탈로그에 없는 ID를 슬롯 기본값으로 되돌린다. 차종은 모두 Lv1이라 그대로 둔다. */
    fun resolve(level: Int): Loadout = Loadout(
        vehicle = vehicle,
        colorId = CosmeticCatalog.resolve(Slot.COLOR, colorId, level),
        wheelId = CosmeticCatalog.resolve(Slot.WHEEL, wheelId, level),
        lampId = CosmeticCatalog.resolve(Slot.LAMP, lampId, level),
        roofId = CosmeticCatalog.resolve(Slot.ROOF, roofId, level),
        decalId = CosmeticCatalog.resolve(Slot.DECAL, decalId, level),
        plateId = CosmeticCatalog.resolve(Slot.PLATE, plateId, level),
        trailId = CosmeticCatalog.resolve(Slot.TRAIL, trailId, level),
    )

    companion object {
        val default = Loadout()
    }
}
