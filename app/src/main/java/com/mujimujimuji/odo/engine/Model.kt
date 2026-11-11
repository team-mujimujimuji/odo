package com.mujimujimuji.odo.engine

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class TurnSignal { OFF, LEFT, RIGHT }

data class LatLng(val lat: Double, val lng: Double)

/**
 * 한 시점의 차량 신호 묶음. 값이 null이면 그 신호를 받을 수 없다는 뜻이고,
 * 해당 신호를 쓰는 판정(벨트 이벤트, 보너스 등)은 생략한다.
 */
data class VehicleSnapshot(
    val timeMs: Long,
    val gear: Gear,
    val speedKmh: Double,
    val location: LatLng? = null,
    val driverBelt: Boolean? = null,
    /** 동승자가 있고 탑승 좌석이 모두 체결이면 true. 운전자 혼자이거나 좌석·탑승 신호가 없으면 null(보너스 생략). */
    val allBelts: Boolean? = null,
    val turnSignal: TurnSignal? = null,
    val wiperOn: Boolean? = null,
    val odometerKm: Double? = null,
    val speedLimitKmh: Int? = null,
    /**
     * [speedKmh] 값을 얻은 시각(차량이 값을 만든 시각, 모르면 받은 시각). null이면 [timeMs]와 같다고 본다(데모 재생).
     * 1초 틱처럼 예전에 얻은 차속을 새 시각에 다시 실은 스냅샷을 급가속·급감속 판정이 가려내는 데 쓴다.
     * 다시 실을 때는 이 값을 바꾸지 않는다. 판정은 바로 앞 스냅샷과 같은 값이면 다시 실은 것으로 본다.
     */
    val speedTimeMs: Long? = null,
)

enum class RiskType { HARSH_ACCEL, HARSH_DECEL, SPEEDING, NO_SEATBELT }

data class RiskEvent(val type: RiskType, val timeMs: Long)
