package com.mujimujimuji.odo.cosmetic

/**
 * 꾸미기 카탈로그(설계서 §3.2). 수집 대상 24개 + 선택 해제용 "none" 4개.
 * 색 값은 순수 코틀린 Long(0xFFRRGGBB)으로 두고 Compose Color 변환은 ui 쪽에서 한다.
 */
object CosmeticCatalog {
    const val NONE = "none"

    val items: List<CosmeticItem> = buildList {
        VehicleType.entries.forEach { add(CosmeticItem(it.id, Slot.VEHICLE, it.label)) }

        add(CosmeticItem("sky", Slot.COLOR, "하늘", 0xFF5AA9E6L))
        add(CosmeticItem("cherry", Slot.COLOR, "체리", 0xFFE5484DL))
        add(CosmeticItem("mint", Slot.COLOR, "민트", 0xFF3CC7A6L))
        add(CosmeticItem("lavender", Slot.COLOR, "라벤더", 0xFF9B8CFFL))
        add(CosmeticItem("tangerine", Slot.COLOR, "귤", 0xFFFF9F43L))
        add(CosmeticItem("pearl", Slot.COLOR, "펄 화이트", 0xFFF1F3F6L))
        add(CosmeticItem("graphite", Slot.COLOR, "그래파이트", 0xFF3A3F47L))
        add(CosmeticItem("sand", Slot.COLOR, "샌드", 0xFFD8C8A4L))

        add(CosmeticItem("steel", Slot.WHEEL, "스틸"))
        add(CosmeticItem("dia", Slot.WHEEL, "다이아"))
        add(CosmeticItem("aero", Slot.WHEEL, "에어로"))

        add(CosmeticItem("white", Slot.LAMP, "화이트", 0xFFFFFFFFL))
        add(CosmeticItem("warm", Slot.LAMP, "웜", 0xFFFFD6A5L))
        add(CosmeticItem("ice", Slot.LAMP, "아이스", 0xFFCFE6FFL))

        add(CosmeticItem(NONE, Slot.ROOF, "없음"))
        add(CosmeticItem("twotone", Slot.ROOF, "투톤 루프"))
        add(CosmeticItem("box", Slot.ROOF, "루프박스"))

        add(CosmeticItem(NONE, Slot.DECAL, "없음"))
        add(CosmeticItem("stripe", Slot.DECAL, "스트라이프"))

        add(CosmeticItem(NONE, Slot.PLATE, "없음"))
        add(CosmeticItem("gold", Slot.PLATE, "황금 번호판"))

        add(CosmeticItem(NONE, Slot.TRAIL, "없음"))
        add(CosmeticItem("ribbon", Slot.TRAIL, "지난 길 리본"))
    }

    /** 수집 대상 수(= "none"을 뺀 항목 수, 24). */
    val collectibleTotal: Int = items.count { it.id != NONE }

    fun bySlot(slot: Slot): List<CosmeticItem> = items.filter { it.slot == slot }

    fun byKey(key: String): CosmeticItem? = items.firstOrNull { it.key == key }

    fun defaultOf(slot: Slot): CosmeticItem = bySlot(slot).first { it.id == Loadout.default.idOf(slot) }

    /** 슬롯 안에서 id를 찾고, 모르는 id면 슬롯 기본값. */
    fun item(slot: Slot, id: String?): CosmeticItem = bySlot(slot).firstOrNull { it.id == id } ?: defaultOf(slot)

    /** 레벨에 열린 항목이면 그 id, 잠겼거나 모르는 id면 슬롯 기본값 id. */
    fun resolve(slot: Slot, id: String?, level: Int): String {
        val found = bySlot(slot).firstOrNull { it.id == id } ?: return defaultOf(slot).id
        return if (found.unlockLevel <= level) found.id else defaultOf(slot).id
    }

    /** 레벨까지 열린 수집 대상 수. */
    fun collected(level: Int): Int = items.count { it.id != NONE && it.unlockLevel <= level }
}
