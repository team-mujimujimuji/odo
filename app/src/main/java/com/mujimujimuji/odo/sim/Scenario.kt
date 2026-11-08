package com.mujimujimuji.odo.sim

import com.mujimujimuji.odo.engine.Gear
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.engine.TurnSignal
import com.mujimujimuji.odo.engine.VehicleSnapshot
import org.json.JSONObject

/** 시나리오 JSON. t는 시나리오 시작부터의 초. */
data class Scenario(
    val name: String,
    val tickMs: Long,
    val speedup: Int,
    val samples: List<ScenarioSample>,
) {
    val durationSec: Double get() = samples.lastOrNull()?.t ?: 0.0

    /**
     * [tickMs] 간격의 스냅샷 목록. 샘플 사이 빈 시간은 직전 샘플 값을 유지해 채운다.
     * 스냅샷 시각은 가상 시각([startTimeMs] + t)이라 엔진은 배속과 상관없이 같은 결과를 낸다.
     */
    fun timeline(startTimeMs: Long): List<VehicleSnapshot> {
        if (samples.isEmpty()) return emptyList()
        val out = ArrayList<VehicleSnapshot>()
        var idx = 0
        var tMs = 0L
        val endMs = (durationSec * 1000).toLong()
        while (tMs <= endMs) {
            while (idx + 1 < samples.size && samples[idx + 1].t * 1000 <= tMs) idx++
            out += samples[idx].toSnapshot(startTimeMs + tMs)
            tMs += tickMs
        }
        return out
    }

    companion object {
        fun parse(json: String): Scenario {
            val o = JSONObject(json)
            val arr = o.getJSONArray("samples")
            val samples = (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                ScenarioSample(
                    t = s.getDouble("t"),
                    gear = parseGear(s.getString("gear")),
                    speedKmh = s.getDouble("speedKmh"),
                    location = if (s.has("lat") && s.has("lng")) LatLng(s.getDouble("lat"), s.getDouble("lng")) else null,
                    seatbelt = if (s.has("seatbelt")) s.getBoolean("seatbelt") else null,
                    turnSignal = if (s.has("turnSignal")) TurnSignal.valueOf(s.getString("turnSignal")) else null,
                    wiperOn = if (s.has("wiper")) s.getBoolean("wiper") else null,
                )
            }.sortedBy { it.t }
            return Scenario(
                name = o.optString("name", "시나리오"),
                tickMs = o.optLong("tickMs", 1_000L),
                speedup = o.optInt("speedup", 1).coerceAtLeast(1),
                samples = samples,
            )
        }

        private fun parseGear(g: String): Gear = when (g.uppercase()) {
            "P" -> Gear.PARK
            "D" -> Gear.DRIVE
            "R" -> Gear.REVERSE
            "N" -> Gear.NEUTRAL
            else -> Gear.UNKNOWN
        }
    }
}

data class ScenarioSample(
    val t: Double,
    val gear: Gear,
    val speedKmh: Double,
    val location: LatLng?,
    val seatbelt: Boolean?,
    val turnSignal: TurnSignal?,
    val wiperOn: Boolean?,
) {
    fun toSnapshot(timeMs: Long) = VehicleSnapshot(
        timeMs = timeMs,
        gear = gear,
        speedKmh = speedKmh,
        location = location,
        driverBelt = seatbelt,
        allBelts = null, // 데모는 운전자 혼자
        turnSignal = turnSignal,
        wiperOn = wiperOn,
        odometerKm = null,
        speedLimitKmh = null,
    )
}
