package com.mujimujimuji.odo.platform

import kotlinx.coroutines.flow.Flow

enum class VoiceCommand { WAVE, GHOST_MODE, TODAY_RECORD }

interface Voice {
    /** 한 문장만 읽는다. 횟수 제한은 호출하는 쪽(VoicePolicy)이 지킨다. */
    fun speak(text: String)

    /** STT 명령. 1차는 흘리지 않는다. */
    val commands: Flow<VoiceCommand>

    fun release()
}
