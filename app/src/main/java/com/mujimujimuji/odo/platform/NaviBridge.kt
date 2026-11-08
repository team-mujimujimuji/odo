package com.mujimujimuji.odo.platform

import com.mujimujimuji.odo.engine.LatLng
import kotlinx.coroutines.flow.Flow

interface NaviBridge {
    /** 현재 도로 제한속도. 내비가 안내 중이 아니면 null(과속 판정 생략). */
    val speedLimitKmh: Flow<Int?>

    /** 업적 장소를 목적지로 보낸다(2차). */
    fun sendDestination(name: String, location: LatLng)
}
