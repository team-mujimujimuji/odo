package com.mujimujimuji.odo.ui.vehicle

import com.mujimujimuji.odo.cosmetic.CosmeticCatalog
import com.mujimujimuji.odo.cosmetic.Slot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 바디 색 8종별 밝기 판정과 투톤 지붕색·데칼색([VehiclePaint.isBright], [VehiclePaint.roofArgb], [VehiclePaint.decalArgb]),
 * 그리고 [VehiclePaint.of]가 색 필터에 넣는 낮·밤 층 색([VehiclePaint.tintsOf]). 표는 밤에 어둡게 하기 전 값이다.
 * 밝은 7색은 지붕 #6B727D(밝은 진회색)·데칼 graphite, graphite 차는 지붕·데칼 모두 pearl.
 */
class VehiclePaintTest {

    private companion object {
        const val ROOF_ON_BRIGHT = 0xFF6B727DL
        const val GRAPHITE = 0xFF3A3F47L
        const val PEARL = 0xFFF1F3F6L

        /** 밤 바디·투톤·데칼 밝기: 낮의 85 %(15 % 어둡게). */
        const val NIGHT_PERCENT = 85

        /** 색 id → 밝은 차인지, 투톤 지붕색, 데칼색. */
        val TABLE: Map<String, Triple<Boolean, Long, Long>> = linkedMapOf(
            "sky" to Triple(true, ROOF_ON_BRIGHT, GRAPHITE),
            "cherry" to Triple(true, ROOF_ON_BRIGHT, GRAPHITE),
            "mint" to Triple(true, ROOF_ON_BRIGHT, GRAPHITE),
            "lavender" to Triple(true, ROOF_ON_BRIGHT, GRAPHITE),
            "tangerine" to Triple(true, ROOF_ON_BRIGHT, GRAPHITE),
            "pearl" to Triple(true, ROOF_ON_BRIGHT, GRAPHITE),
            "sand" to Triple(true, ROOF_ON_BRIGHT, GRAPHITE),
            "graphite" to Triple(false, PEARL, PEARL),
        )

        fun body(id: String): Long = checkNotNull(CosmeticCatalog.item(Slot.COLOR, id).argb) { "색 $id 에 argb 없음" }

        fun hex(argb: Long): String = "#%08X".format(argb)
    }

    @Test
    fun `색 슬롯은 표의 8색이고 graphite·pearl 값이 표와 같다`() {
        assertEquals(TABLE.keys, CosmeticCatalog.bySlot(Slot.COLOR).map { it.id }.toSet())
        assertEquals(hex(GRAPHITE), hex(body("graphite")))
        assertEquals(hex(PEARL), hex(body("pearl")))
    }

    @Test
    fun `밝은 차는 graphite를 뺀 7색이다`() {
        for ((id, row) in TABLE) assertEquals(id, row.first, VehiclePaint.isBright(body(id)))
        assertEquals(7, TABLE.keys.count { VehiclePaint.isBright(body(it)) })
    }

    @Test
    fun `투톤 지붕색은 밝은 7색 위 6B727D, graphite 위 pearl`() {
        for ((id, row) in TABLE) assertEquals(id, hex(row.second), hex(VehiclePaint.roofArgb(body(id))))
    }

    @Test
    fun `데칼색은 밝은 7색 위 graphite, graphite 위 pearl`() {
        for ((id, row) in TABLE) assertEquals(id, hex(row.third), hex(VehiclePaint.decalArgb(body(id))))
    }

    /** 낮에는 몸·지붕·데칼 층 색이 바디색과 표의 지붕색·데칼색 그대로다. */
    @Test
    fun `낮 층 색은 바디색과 표의 지붕색·데칼색이다`() {
        for ((id, row) in TABLE) {
            val day = VehiclePaint.tintsOf(id, night = false)
            assertEquals("$id 몸", hex(body(id)), hex(day.body))
            assertEquals("$id 지붕", hex(row.second), hex(day.roof))
            assertEquals("$id 데칼", hex(row.third), hex(day.decal))
        }
    }

    /**
     * 밤에는 몸·지붕·데칼 층 색이 낮 값의 0.85배(15 % 어둡게)다. [VehiclePaint.of]가 색 필터에 넣는 값을 그대로 본다.
     * 기대값은 낮 값(바디색, 표의 지붕색·데칼색)의 채널마다 0.85배를 정수로 반올림해 따로 만든다(알파는 그대로).
     */
    @Test
    fun `밤 층 색은 8색 모두 낮 값의 85퍼센트다`() {
        for ((id, row) in TABLE) {
            val night = VehiclePaint.tintsOf(id, night = true)
            assertEquals("$id 몸", hex(scaled(body(id), NIGHT_PERCENT)), hex(night.body))
            assertEquals("$id 지붕", hex(scaled(row.second, NIGHT_PERCENT)), hex(night.roof))
            assertEquals("$id 데칼", hex(scaled(row.third, NIGHT_PERCENT)), hex(night.decal))
        }
    }

    /** 채널마다 [percent] %로 줄이고 반올림(0.5는 올림)한다. 알파는 그대로. */
    private fun scaled(argb: Long, percent: Int): Long {
        fun ch(shift: Int): Long = ((((argb shr shift) and 0xFF) * percent + 50) / 100) shl shift
        return (argb and 0xFF000000L) or ch(16) or ch(8) or ch(0)
    }
}
