package com.mujimujimuji.odo.platform.pleos

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.util.Log
import com.mujimujimuji.odo.platform.Voice
import com.mujimujimuji.odo.platform.VoiceCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.util.Locale

/**
 * Pleos TTS(온디바이스). 초기화에 실패하면 안드로이드 기본 TTS로 대신 읽는다.
 * STT 명령은 1차 범위에서 뺐다(검토 보고서 §5).
 */
class PleosVoice(context: Context) : Voice {
    private val appContext = context.applicationContext
    private var pleos: TextToSpeech? = null
    private var pleosReady = false
    private var fallback: android.speech.tts.TextToSpeech? = null
    private var fallbackReady = false

    init {
        runCatching {
            TextToSpeech(appContext, Mode.ON_DEVICE).also { tts ->
                pleos = tts
                tts.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        runCatching { tts.setLocale(Locale.KOREAN) }
                        pleosReady = true
                        Log.i(TAG, "pleos tts ready")
                    }

                    override fun onFailure(error: ErrorType) {
                        Log.w(TAG, "pleos tts init failed: $error")
                        startFallback()
                    }
                })
            }
        }.onFailure {
            Log.w(TAG, "pleos tts unavailable", it)
            startFallback()
        }
    }

    private fun startFallback() {
        if (fallback != null) return
        fallback = android.speech.tts.TextToSpeech(appContext) { status ->
            fallbackReady = status == android.speech.tts.TextToSpeech.SUCCESS
            if (fallbackReady) fallback?.language = Locale.KOREAN
            Log.i(TAG, "fallback tts ready=$fallbackReady")
        }
    }

    override fun speak(text: String) {
        Log.i(TAG, "speak: $text")
        when {
            pleosReady -> runCatching { pleos?.speak(text, true) }
                .onFailure { Log.w(TAG, "pleos speak failed", it) }
            fallbackReady -> fallback?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, text)
            else -> Log.w(TAG, "no tts engine ready")
        }
    }

    override val commands: Flow<VoiceCommand> = emptyFlow()

    override fun release() {
        runCatching { pleos?.release() }
        fallback?.shutdown()
    }

    private companion object {
        const val TAG = "OdoVoice"
    }
}
