package com.mujimujimuji.odo.platform

import kotlinx.coroutines.flow.Flow

/** 시스템의 운전 중 화면 제한(AAOS UX Restrictions). 화면 잠금은 이것과 기어·속도 판정 중 더 엄격한 쪽을 따른다. */
interface DrivingRestriction {
    val restricted: Flow<Boolean>
}
