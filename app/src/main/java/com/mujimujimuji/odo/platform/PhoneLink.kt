package com.mujimujimuji.odo.platform

import kotlinx.coroutines.flow.StateFlow

/** 휴대폰 연결 상태(설계서 §7.2). 화면은 이 상태만 그린다. */
sealed interface PhoneLinkState {
    data object Idle : PhoneLinkState

    /** QR을 보여 주는 중. [code]는 사람이 읽는 8글자(4-4), [qrPayload]는 QR에 담는 문자열. */
    data class Presenting(val code: String, val qrPayload: String, val expiresAtMs: Long) : PhoneLinkState

    /** 폰이 확인했다는 응답을 받음(1.5 s 뒤 Linked로). */
    data object Confirmed : PhoneLinkState

    data class Linked(val label: String?, val linkedAtMs: Long) : PhoneLinkState

    data class Failed(val message: String) : PhoneLinkState
}

/**
 * 휴대폰 연결 포트. 차량 신호 원본·위치 기록은 어떤 구현도 내보내지 않는다 — 공유 범위는 트립 요약·업적·미니카 꾸미기뿐이다.
 * 1차에는 [com.mujimujimuji.odo.platform.fake.FakePhoneLink]만 있다(폰 앱은 준비 중).
 */
interface PhoneLink {
    val state: StateFlow<PhoneLinkState>

    /** 새 코드·QR을 만들어 [PhoneLinkState.Presenting]으로 간다. 이미 연결됐으면 아무것도 하지 않는다. */
    fun startPairing()

    /**
     * 연결 대기(코드 표시)를 접고 [PhoneLinkState.Idle]로. 연결 화면을 떠나거나 주행이 시작되면 라우팅이 부른다.
     * 연결됨은 그대로 두고, 폰이 이미 확인한 [PhoneLinkState.Confirmed]는 접지 않고 저장·연결됨까지 마친다.
     */
    fun cancelPairing()

    /** 연결을 끊고 저장된 연결 기록을 지운다. */
    fun disconnect()
}
