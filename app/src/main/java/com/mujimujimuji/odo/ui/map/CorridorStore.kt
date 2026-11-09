package com.mujimujimuji.odo.ui.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** `assets/map/corridor.json`을 프로세스당 1회 읽고(백그라운드) Path 캐시까지 미리 만든다. 네트워크 0. */
object CorridorStore {
    @Volatile
    private var loaded: CorridorData? = null
    private val mutex = Mutex()

    val peek: CorridorData? get() = loaded

    suspend fun load(context: Context): CorridorData {
        loaded?.let { return it }
        return mutex.withLock {
            loaded ?: withContext(Dispatchers.Default) {
                val json = context.assets.open(CorridorData.ASSET_PATH).bufferedReader().use { it.readText() }
                CorridorData(json).also { CorridorPaths.of(it) }
            }.also { loaded = it }
        }
    }
}

/** 코리도 데이터. 로딩 중에는 null(지도는 계기 모드로 그린다). */
@Composable
fun rememberCorridorData(): CorridorData? {
    val context = LocalContext.current.applicationContext
    var data by remember { mutableStateOf(CorridorStore.peek) }
    LaunchedEffect(Unit) { if (data == null) data = CorridorStore.load(context) }
    return data
}
