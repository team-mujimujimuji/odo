package com.mujimujimuji.odo.engine

import java.time.LocalDateTime
import java.time.ZoneId

val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

fun epochMs(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, s: Int = 0): Long =
    LocalDateTime.of(y, mo, d, h, mi, s).atZone(SEOUL).toInstant().toEpochMilli()

val T0: Long = epochMs(2026, 11, 8, 10)

fun snap(
    tSec: Double,
    speed: Double,
    gear: Gear = if (speed > 0) Gear.DRIVE else Gear.PARK,
    base: Long = T0,
    location: LatLng? = null,
    driverBelt: Boolean? = true,
    allBelts: Boolean? = null,
    wiperOn: Boolean? = null,
    speedLimit: Int? = null,
) = VehicleSnapshot(
    timeMs = base + (tSec * 1000).toLong(),
    gear = gear,
    speedKmh = speed,
    location = location,
    driverBelt = driverBelt,
    allBelts = allBelts,
    wiperOn = wiperOn,
    speedLimitKmh = speedLimit,
)
