package com.mujimujimuji.odo.platform.pleos

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.mujimujimuji.odo.platform.Voice
import com.mujimujimuji.odo.platform.VoiceCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.util.Locale

/**
 * Pleos TTS(온디바이스). 초기화에 실패하면 안드로이드 기본 TTS로 대신 읽는다.
 * 한 문장이 소리를 내기 전에 Pleos 엔진에서 실패하면 그 문장만 안드로이드 TTS로 한 번 넘겨 읽는다.
 * 같은 발화를 다른 엔진으로 넘길 뿐 새 발화를 만들지 않으므로 횟수 제한(VoicePolicy)은 그대로다.
 * STT 명령은 1차 범위에서 뺐다(README '알려진 한계 (1차)').
 *
 * 스레드: speak()는 Dispatchers.Default에서, Pleos 이벤트는 바인더 스레드에서 온다.
 * 그래서 상태는 메인 Looper 한 곳에서만 읽고 바꾼다(모두 main.post).
 */
class PleosVoice(context: Context) : Voice {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // 아래 상태는 메인 스레드에서만 만진다(생성자도 Application.onCreate의 메인 스레드다).
    private var pleos: TextToSpeech? = null
    private var pleosReady = false
    private var fallback: android.speech.tts.TextToSpeech? = null
    private var fallbackReady = false
    private var fallbackFailed = false
    private var released = false

    /** Pleos로 보냈지만 끝 신호(onDone·onStop·onError)를 아직 못 받은 문장 수. */
    private var inFlight = 0
    private var lastSentAtMs = 0L

    /** 가장 최근에 Pleos로 보낸 문장. 끝 신호를 받으면 비운다. */
    private var current: Utterance? = null

    /** 대체 엔진이 켜지기를 기다리는 문장. */
    private var pendingRescue: Rescue? = null

    private class Utterance(val text: String, val rescuable: Boolean) {
        var voiced = false
    }

    private class Rescue(val text: String, val atMs: Long)

    private val events = object : EventListener {
        override fun onReady() {
            main.post { Log.i(TAG, "pleos onReady") }
        }

        override fun onStart() {
            main.post { Log.i(TAG, "pleos onStart") }
        }

        override fun onUpdatedRms(rms: Double) {
            main.post { onPleosRms(rms) }
        }

        override fun onDone() {
            main.post { onPleosEnd("onDone", failed = false) }
        }

        override fun onStop() {
            main.post { onPleosEnd("onStop", failed = false) }
        }

        override fun onError(errorType: ErrorType) {
            main.post { onPleosEnd("onError($errorType)", failed = true) }
        }
    }

    init {
        runCatching {
            TextToSpeech(appContext, Mode.ON_DEVICE).also { tts ->
                pleos = tts
                tts.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        main.post { onPleosInit(tts) }
                    }

                    override fun onFailure(error: ErrorType) {
                        main.post {
                            Log.w(TAG, "pleos tts init failed: $error")
                            startFallback()
                        }
                    }
                })
            }
        }.onFailure {
            Log.w(TAG, "pleos tts unavailable", it)
            startFallback()
        }
    }

    private fun onPleosInit(tts: TextToSpeech) {
        if (released) return
        runCatching { tts.addEventListener(events) }
            .onFailure { Log.w(TAG, "pleos event listener failed", it) }
        // SDK는 locale.toLanguageTag()를 엔진에 넘기므로 나라가 있어야 한다.
        // Locale.KOREAN("ko")만 주면 엔진이 'unsupported locale: ko-'로 실패한다(공식 예시도 Locale.KOREA).
        // setLocale은 SDK 코루틴 안에서 돌아 실패해도 여기서는 못 잡는다. 실패는 문장마다 onError로 온다.
        runCatching { tts.setLocale(Locale.KOREA) }
        pleosReady = true
        Log.i(TAG, "pleos tts ready")
    }

    private fun startFallback() {
        if (fallback != null || released) return
        // 초기화 콜백이 생성자 안에서 바로 올 수도 있어 post로 미룬다(그때는 fallback이 이미 들어 있다).
        fallback = android.speech.tts.TextToSpeech(appContext) { status ->
            main.post { onFallbackInit(status) }
        }
    }

    private fun onFallbackInit(status: Int) {
        val tts = fallback ?: return
        if (released) return
        val ok = status == android.speech.tts.TextToSpeech.SUCCESS
        val lang = if (ok) tts.setLanguage(Locale.KOREA) else android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
        // 한국어 음성이 없으면 다른 언어로 읽지 않도록 준비 안 됨으로 둔다.
        fallbackReady = ok &&
            lang != android.speech.tts.TextToSpeech.LANG_MISSING_DATA &&
            lang != android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
        fallbackFailed = !fallbackReady
        Log.i(TAG, "fallback tts ready=$fallbackReady status=$status lang=$lang")
        val rescue = pendingRescue ?: return
        pendingRescue = null
        val waitedMs = SystemClock.elapsedRealtime() - rescue.atMs
        when {
            !fallbackReady -> Log.w(TAG, "rescue dropped: fallback tts unavailable")
            waitedMs > RESCUE_MAX_DELAY_MS -> Log.w(TAG, "rescue dropped: too late ($waitedMs ms)")
            else -> speakFallback(rescue.text)
        }
    }

    override fun speak(text: String) {
        main.post { speakOnMain(text) }
    }

    private fun speakOnMain(text: String) {
        if (released) return
        Log.i(TAG, "speak: $text")
        // 새 문장이 앞 문장을 덮는다. 아직 못 읽은 앞 문장의 대신 읽기는 버린다.
        pendingRescue = null
        when {
            pleosReady -> speakPleos(text)
            fallbackReady -> speakFallback(text)
            else -> Log.w(TAG, "no tts engine ready")
        }
    }

    private fun speakPleos(text: String) {
        val tts = pleos ?: return
        val now = SystemClock.elapsedRealtime()
        // 끝 신호를 끝내 못 받은 작업은 충분히 지난 뒤 잃은 것으로 보고 센 값을 비운다.
        if (inFlight > 0 && now - lastSentAtMs > LOST_JOB_MS) inFlight = 0
        // Pleos 이벤트에는 어느 문장 것인지 표시가 없다. 앞 작업이 안 끝난 채 보낸 문장은
        // 앞 문장의 늦은 오류와 구별할 수 없으므로 대신 읽지 않는다(같은 문장을 두 번 듣지 않게).
        val utterance = Utterance(text, rescuable = inFlight == 0)
        // 대체 엔진이 앞 문장을 읽는 중이면 끊는다(Pleos도 새 요청이 오면 앞 재생을 끊는다).
        if (fallbackReady) fallback?.stop()
        current = utterance
        inFlight++
        lastSentAtMs = now
        runCatching { tts.speak(text, true) }.onFailure {
            Log.w(TAG, "pleos speak failed", it)
            inFlight--
            current = null
            if (utterance.rescuable) rescue(text)
        }
    }

    private fun onPleosRms(rms: Double) {
        if (released) return
        val utterance = current ?: return
        // 엔진은 합성한 소리 조각을 재생 줄에 넣을 때 RMS를 보낸다(0보다 클 때만).
        // onStart는 엔진을 만들기 전에 와서 소리의 증거가 못 된다. 첫 RMS를 '소리 시작'으로 본다.
        if (rms > 0.0 && !utterance.voiced) {
            utterance.voiced = true
            Log.i(TAG, "pleos voice started")
        }
    }

    private fun onPleosEnd(what: String, failed: Boolean) {
        if (released) return
        Log.i(TAG, "pleos $what")
        if (inFlight == 0) return // 이미 끝났거나 잊은 작업의 늦은 신호
        inFlight--
        if (inFlight > 0) return // 앞 문장의 끝 신호다. 지금 문장은 아직 진행 중이다.
        val utterance = current ?: return
        current = null
        if (!failed) return
        when {
            !utterance.rescuable -> Log.i(TAG, "rescue skipped: sent while an earlier job was running")
            utterance.voiced -> Log.i(TAG, "rescue skipped: sound had started")
            else -> rescue(utterance.text)
        }
    }

    /** 소리 없이 실패한 문장을 안드로이드 TTS로 한 번만 넘긴다. */
    private fun rescue(text: String) {
        when {
            fallbackReady -> {
                Log.i(TAG, "rescue via fallback tts")
                speakFallback(text)
            }
            fallbackFailed -> Log.w(TAG, "rescue dropped: fallback tts unavailable")
            else -> {
                Log.i(TAG, "rescue waits for fallback tts")
                pendingRescue = Rescue(text, SystemClock.elapsedRealtime())
                startFallback()
            }
        }
    }

    private fun speakFallback(text: String) {
        val result = fallback?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, text)
        Log.i(TAG, "fallback speak result=$result")
    }

    override val commands: Flow<VoiceCommand> = emptyFlow()

    override fun release() {
        if (Looper.myLooper() == main.looper) releaseOnMain() else main.post { releaseOnMain() }
    }

    private fun releaseOnMain() {
        if (released) return
        released = true
        pendingRescue = null
        current = null
        pleosReady = false
        pleos?.let { tts ->
            // 공식 안내: 해제 전에 등록한 같은 리스너 인스턴스를 뗀다.
            runCatching { tts.removeEventListener(events) }
            runCatching { tts.release() }
        }
        fallbackReady = false
        fallback?.shutdown()
    }

    private companion object {
        const val TAG = "OdoVoice"

        /**
         * 대체 엔진을 켜느라 기다린 시간이 이보다 길면 그 문장은 버린다.
         * 이벤트와 소리가 5초 넘게 벌어지면 무엇에 대한 말인지 알기 어렵고,
         * 운전 중 예고 없이 늦게 나오는 음성은 주의를 뺏는다.
         */
        const val RESCUE_MAX_DELAY_MS = 5_000L

        /**
         * 한 문장은 길어야 5초 안팎이고 끝 신호는 보통 곧 온다. 마지막으로 보낸 지 15초가 지나도
         * 끝 신호가 없으면 그 작업의 이벤트는 잃은 것으로 보고 센 값을 비운다(이후 문장이 영영 대신 읽히지 못하는 것을 막는다).
         */
        const val LOST_JOB_MS = 15_000L
    }
}
