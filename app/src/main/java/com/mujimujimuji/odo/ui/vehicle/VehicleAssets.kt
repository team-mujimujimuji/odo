package com.mujimujimuji.odo.ui.vehicle

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mujimujimuji.odo.cosmetic.VehicleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** 그릴 준비가 끝난 레이어 묶음(매니페스트 order 순, 없는 파일은 빠진다). */
class VehicleFrame(
    val manifest: VehicleManifest,
    val view: VehicleView,
    val layers: List<Pair<String, ImageBitmap>>,
)

/**
 * 미니카 에셋 싱글턴(설계서 §2.5). compose.json은 1회 파싱, (차종, 뷰, 레이어) → ImageBitmap LRU(바이트 상한 [MAX_BYTES]),
 * 색 필터는 (색, 블렌드)별 캐시라 프레임마다 새로 만들지 않는다. 디코딩은 Dispatchers.IO.
 * 앱이 화면에서 사라지면 OdoApp.onTrimMemory가 [trimMemory]로 비운다.
 */
object VehicleAssets {
    /** hero 한 장 576×384 ARGB ≈ 0.88 MB, 로드아웃 한 벌 6~9장 ≈ 5~8 MB → 두세 벌과 navi 몇 장이 들어간다. */
    private const val MAX_BYTES = 20 * 1024 * 1024

    @Volatile private var manifestCache: VehicleManifest? = null

    // LruCache는 get·put이 스스로 동기화된다.
    private val bitmaps = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().allocationByteCount
    }

    private data class TintKey(val argb: Long, val mode: BlendMode)

    private val filters = HashMap<TintKey, ColorFilter>()

    suspend fun manifest(context: Context): VehicleManifest {
        manifestCache?.let { return it }
        val assets = context.applicationContext.assets
        return withContext(Dispatchers.IO) {
            val text = assets.open(VehicleManifest.PATH).bufferedReader().use { it.readText() }
            VehicleManifest.parse(text).also { manifestCache = it }
        }
    }

    /** 레이어들을 캐시에서 꺼내고, 없는 것만 IO에서 디코딩한다. 결과는 요청 순서를 지킨다. */
    suspend fun load(context: Context, vehicle: VehicleType, view: VehicleView, layerNames: List<String>): VehicleFrame {
        val m = manifest(context)
        val appContext = context.applicationContext
        val found = HashMap<String, ImageBitmap>()
        val missing = ArrayList<String>()
        for (name in layerNames) {
            val hit = bitmaps.get(cacheKey(vehicle, view, name))
            if (hit != null) found[name] = hit else missing += name
        }
        if (missing.isNotEmpty()) {
            val decoded = withContext(Dispatchers.IO) {
                missing.mapNotNull { name -> decode(appContext, vehicle, view, name)?.let { name to it } }
            }
            for ((name, bmp) in decoded) {
                bitmaps.put(cacheKey(vehicle, view, name), bmp)
                found[name] = bmp
            }
        }
        return VehicleFrame(m, view, layerNames.mapNotNull { name -> found[name]?.let { name to it } })
    }

    /** 메모리 압박 때 비트맵 캐시를 비운다. 화면에 그려지고 있는 장은 그 화면이 계속 붙잡고 있고, 다음 로드에서 필요한 장만 다시 디코딩한다. */
    fun trimMemory() = bitmaps.evictAll()

    /** (색, 블렌드)별로 하나만 만든다. */
    fun tint(argb: Long, mode: BlendMode): ColorFilter = synchronized(filters) {
        filters.getOrPut(TintKey(argb, mode)) { ColorFilter.tint(Color(argb), mode) }
    }

    private fun cacheKey(vehicle: VehicleType, view: VehicleView, layer: String) = "${vehicle.id}/${view.dir}/$layer"

    private fun decode(context: Context, vehicle: VehicleType, view: VehicleView, layer: String): ImageBitmap? =
        try {
            context.assets.open(VehicleManifest.assetPath(vehicle.id, view, layer)).use { stream ->
                // 밉맵 힌트: 0.5× 안팎으로 줄여 그릴 때 가장자리 계단을 줄인다.
                BitmapFactory.decodeStream(stream)?.also { it.setHasMipMap(true) }?.asImageBitmap()
            }
        } catch (e: IOException) {
            null
        }
}
