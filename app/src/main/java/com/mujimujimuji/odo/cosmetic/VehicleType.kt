package com.mujimujimuji.odo.cosmetic

/** 미니카 차종. id는 에셋 폴더명(app/src/main/assets/minicar/<id>/)과 DB 값으로 쓰인다. */
enum class VehicleType(val id: String, val label: String) {
    COMPACT("compact", "소형"),
    SUV("suv", "중형"),
    LARGE("large", "대형"),
    SEDAN("sedan", "세단"),
    SCOOTER("scooter", "2륜");

    companion object {
        fun byId(id: String?): VehicleType = entries.firstOrNull { it.id == id } ?: COMPACT
    }
}

/** 꾸미기 슬롯. id는 DB(loadout_slot.slot, cosmetic.slot)와 해금 키("slot:id")에 쓰인다. */
enum class Slot(val id: String) {
    VEHICLE("vehicle"),
    COLOR("color"),
    WHEEL("wheel"),
    LAMP("lamp"),
    ROOF("roof"),
    DECAL("decal"),
    PLATE("plate"),
    TRAIL("trail");

    companion object {
        fun byId(id: String?): Slot? = entries.firstOrNull { it.id == id }
    }
}
