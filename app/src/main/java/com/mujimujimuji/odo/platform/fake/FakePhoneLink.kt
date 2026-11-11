package com.mujimujimuji.odo.platform.fake

import com.mujimujimuji.odo.data.OdoRepository
import com.mujimujimuji.odo.platform.PhoneLink
import com.mujimujimuji.odo.platform.PhoneLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * 폰 앱이 없는 1차용 가상 연결(설계서 §7.2). 코드를 보여 준 뒤 [confirmDelayMs] 뒤에 "확인됨 → 연결됨"으로 간다.
 * 코드는 120 s마다 조용히 다시 발급한다. 연결 기록은 Room `phone_link`에 남겨 재시작 뒤에도 "연결됨"을 유지한다.
 * 기기 밖으로 나가는 데이터는 없다(네트워크 호출 0).
 */
class FakePhoneLink(
    private val scope: CoroutineScope,
    private val repo: OdoRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val confirmDelayMs: Long = CONFIRM_DELAY_MS,
) : PhoneLink {
    private val _state = MutableStateFlow<PhoneLinkState>(PhoneLinkState.Idle)
    override val state: StateFlow<PhoneLinkState> = _state.asStateFlow()

    private val random = SecureRandom()
    private var pairingJob: Job? = null

    init {
        // 저장된 연결을 따라간다: 시작 시 "연결됨" 복원, 데모 초기화(resetToSeed가 phone_link를 지움) 뒤에는 연결 안 됨으로.
        // 연결 진행 중(Presenting·Confirmed)에는 건드리지 않는다.
        scope.launch {
            repo.phoneLinkFlow.collect { saved ->
                val now = _state.value
                if (saved.linked && now is PhoneLinkState.Idle) {
                    _state.value = PhoneLinkState.Linked(saved.label, saved.linkedAtMs ?: clock())
                } else if (!saved.linked && now is PhoneLinkState.Linked) {
                    _state.value = PhoneLinkState.Idle
                }
            }
        }
    }

    override fun startPairing() {
        if (_state.value is PhoneLinkState.Linked || _state.value is PhoneLinkState.Confirmed) return
        pairingJob?.cancel()
        pairingJob = scope.launch {
            while (true) {
                val issuedAt = clock()
                _state.value = PhoneLinkState.Presenting(
                    code = newCode(),
                    qrPayload = PAYLOAD_PREFIX + base32(random16()),
                    expiresAtMs = issuedAt + EXPIRY_MS,
                )
                delay(confirmDelayMs.coerceAtMost(EXPIRY_MS))
                if (confirmDelayMs <= EXPIRY_MS) {
                    _state.value = PhoneLinkState.Confirmed
                    delay(CONFIRMED_HOLD_MS)
                    val now = clock()
                    repo.savePhoneLink(linked = true, linkedAtMs = now, label = null)
                    _state.value = PhoneLinkState.Linked(label = null, linkedAtMs = now)
                    return@launch
                }
                // 만료 → 조용히 재발급(루프 계속).
            }
        }
    }

    override fun cancelPairing() {
        // 폰이 이미 확인했으면(확인됨 1.5 s) 연결은 끝난 것이다. 화면을 떠나도 저장·연결됨까지 마치게 둔다.
        if (_state.value is PhoneLinkState.Confirmed) return
        pairingJob?.cancel()
        pairingJob = null
        if (_state.value !is PhoneLinkState.Linked) _state.value = PhoneLinkState.Idle
    }

    override fun disconnect() {
        pairingJob?.cancel()
        pairingJob = null
        _state.value = PhoneLinkState.Idle
        scope.launch { repo.clearPhoneLink() }
    }

    private fun random16(): ByteArray = ByteArray(16).also(random::nextBytes)

    /** 사람이 읽는 코드: 자음만 20자(BCDFGHJKLMNPQRSTVWXZ) 4-4. 모음이 없어 낱말이 되지 않는다. */
    private fun newCode(): String {
        val sb = StringBuilder(9)
        repeat(8) {
            if (it == 4) sb.append('-')
            sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)])
        }
        return sb.toString()
    }

    companion object {
        const val PAYLOAD_PREFIX = "odo-link:v1:"
        const val CODE_ALPHABET = "BCDFGHJKLMNPQRSTVWXZ"
        const val EXPIRY_MS = 120_000L
        const val CONFIRM_DELAY_MS = 3_000L
        const val CONFIRMED_HOLD_MS = 1_500L
        private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        /** RFC 4648 base32, 패딩 없음. 16바이트 → 26글자. */
        fun base32(bytes: ByteArray): String {
            val sb = StringBuilder((bytes.size * 8 + 4) / 5)
            var buffer = 0
            var bits = 0
            for (b in bytes) {
                buffer = (buffer shl 8) or (b.toInt() and 0xFF)
                bits += 8
                while (bits >= 5) {
                    sb.append(B32[(buffer shr (bits - 5)) and 0x1F])
                    bits -= 5
                }
            }
            if (bits > 0) sb.append(B32[(buffer shl (5 - bits)) and 0x1F])
            return sb.toString()
        }
    }
}
