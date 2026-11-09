package com.mujimujimuji.odo.ui.summary

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.mujimujimuji.odo.R

/** 트립 요약 세 박자의 효과음(설계서 §8). 파일은 tools/audio/make_earcons.py가 만든다. */
enum class Earcon(val resId: Int) {
    Xp(R.raw.earcon_xp),
    LevelUp(R.raw.earcon_levelup),
    Achievement(R.raw.earcon_achievement),
}

/**
 * SoundPool 래퍼. USAGE_ASSISTANCE_SONIFICATION 으로 내비 음성과 섞이지 않게 한다.
 * 요약 화면에서만 울리고 주행 중에는 부르지 않는다. 로드가 끝나기 전에 재생을 부르면 로드 완료 때 한 번 울린다.
 */
class Earcons(context: Context) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val ids = HashMap<Earcon, Int>()
    private val loaded = HashSet<Int>()
    private val pending = ArrayDeque<Int>()
    private var released = false

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0 && !released) {
                loaded += sampleId
                if (pending.remove(sampleId)) pool.play(sampleId, VOLUME, VOLUME, 1, 0, 1f)
            }
        }
        for (e in Earcon.entries) ids[e] = pool.load(context, e.resId, 1)
    }

    fun play(earcon: Earcon) {
        if (released) return
        val id = ids[earcon] ?: return
        if (id in loaded) pool.play(id, VOLUME, VOLUME, 1, 0, 1f) else pending += id
    }

    fun release() {
        released = true
        pool.release()
    }

    private companion object {
        const val VOLUME = 0.8f
    }
}

@Composable
fun rememberEarcons(): Earcons {
    val context = LocalContext.current.applicationContext
    val earcons = remember { Earcons(context) }
    DisposableEffect(earcons) { onDispose { earcons.release() } }
    return earcons
}
