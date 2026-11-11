package com.mujimujimuji.odo.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.roundToInt

/**
 * 정적 지도층(바닥·면·강·도로·고속도로 파선)을 지도 좌표계의 정사각 타일 비트맵으로 미리 그려 두는 캐시(설계서 §4.2 성능 대책, 경로 캐싱).
 *
 * heading-up 카메라는 매 프레임 이동·회전하므로 수천 개 Path 를 원근 행렬로 다시 채우면 소프트웨어 GPU(에뮬레이터)에서
 * 렌더 스레드가 프레임당 20~60 ms 를 쓴다(실측 `gfxinfo framestats`: issue draw 중앙값 24 ms). 타일은 전용 스레드 1개가
 * 가까운 것부터 그리고, 프레임은 완성된 비트맵만 원근 행렬 그대로 drawBitmap 한다 → 프레임당 Path 그리기는 동적 층(경로·스티치)뿐.
 * - 키 = (m/dp 비트, 상세 플래그(tertiary 표시 여부), tx, ty). 한 변 [TILE_DP] dp, 비트맵 한 변 = TILE_DP × density px.
 * - 비트맵은 지도 좌표(x 동쪽, y 북쪽)로 그린다: 행 0 = 타일 남쪽 변. 화면 행렬이 y 를 뒤집으므로 결과는 바로 선다.
 * - 캐시 맵·요청 집합은 메인 스레드에서만 만진다. 작업 스레드는 비트맵을 만들어 메인 스레드로 넘길 뿐이다.
 * - LRU [MAX_TILES]장(ARGB 256 KB × density² 씩). recycle 은 하지 않는다(그리는 중인 비트맵을 해제하면 안 되므로 GC 에 맡긴다).
 * - 상세 플래그가 바뀌면(80 km/h 경계) 새 타일이 올 때까지 다른 플래그의 같은 자리 타일을 임시로 보여 준다.
 */
internal class TileCache(
    private val paths: CorridorPaths,
    private val palette: MapPalette,
    density: Float,
) {
    private val data = paths.data

    /** 타일이 하나 완성될 때마다 +1. Canvas 람다가 [versionForFrame]으로 읽으면 다시 그려진다. */
    var version by mutableIntStateOf(0)
        private set

    val sizePx = (TILE_DP * density).roundToInt().coerceAtLeast(16)
    private val requested = HashSet<Long>()
    private val tiles = object : LinkedHashMap<Long, Bitmap>(MAX_TILES * 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>): Boolean {
            val evict = size > MAX_TILES
            if (evict) requested.remove(eldest.key) // 밀려난 타일은 다시 요청할 수 있어야 한다
            return evict
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "odo-map-tiles").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }
    private val scratch = MapScratch(data.features.size) // 작업 스레드 전용(페인트·가시 집합)

    @Volatile
    private var closed = false

    /** Canvas 람다 안에서 호출해 타일 완성 상태를 구독한다(반환값은 쓰지 않아도 된다). */
    fun versionForFrame(): Int = version

    /** 타일 비트맵. 없으면 백그라운드로 요청하고(호출 순서 = 그리는 순서), 상세 플래그만 다른 같은 자리 타일이 있으면 그것을 임시로 돌려준다. */
    fun get(mpd: Float, detail: Boolean, tx: Int, ty: Int): Bitmap? {
        val key = tileKey(mpd, detail, tx, ty)
        tiles[key]?.let { return it }
        if (!closed && requested.add(key)) {
            try {
                worker.execute { render(key, mpd, detail, tx, ty) }
            } catch (e: RejectedExecutionException) {
                requested.remove(key) // close() 뒤에 들어온 요청
            }
        }
        return tiles[tileKey(mpd, !detail, tx, ty)]
    }

    fun close() {
        closed = true
        worker.shutdownNow()
        tiles.clear()
        requested.clear()
    }

    private fun render(key: Long, mpd: Float, detail: Boolean, tx: Int, ty: Int) {
        if (closed) return
        val tileM = TILE_DP * mpd
        val x0 = tx * tileM
        val y0 = ty * tileM
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = AndroidCanvas(bmp)
        c.drawColor(palette.ground)
        val s = sizePx / tileM
        c.scale(s, s)
        c.translate(-x0, -y0)
        val m = CULL_MARGIN_DP * mpd
        data.collectVisible((x0 - m).toDouble(), (y0 - m).toDouble(), (x0 + tileM + m).toDouble(), (y0 + tileM + m).toDouble(), scratch.visible)
        drawStaticLayers(c, data, paths, scratch, palette, mpd, hideTertiary = !detail)
        bmp.setHasAlpha(false)
        main.post {
            if (closed) return@post
            tiles[key] = bmp
            version++
        }
    }
}

/** 타일 키: m/dp 비트(32) | 상세(1) | tx+오프셋(15) | ty+오프셋(15). 음수 인덱스도 된다. */
internal fun tileKey(mpd: Float, detail: Boolean, tx: Int, ty: Int): Long =
    (mpd.toRawBits().toLong() shl 32) or
        ((if (detail) 1L else 0L) shl 30) or
        (((tx + TILE_INDEX_OFFSET).toLong() and 0x7FFF) shl 15) or
        ((ty + TILE_INDEX_OFFSET).toLong() and 0x7FFF)

/**
 * 타일 네 모서리 [q](소스 공간: 원근 적용 전 화면 px, y 아래)가 소스 사다리꼴 밖에 완전히 있으면 false.
 * 사다리꼴: 윗변 y=0, x∈[-extL, w+extR]; 아랫변 y=h, x∈[0, w]. 사다리꼴 변 4개만 분리축으로 쓰므로 보수적이다(보이는 타일은 거르지 않음).
 */
internal fun quadMayOverlapTrapezoid(q: FloatArray, w: Float, h: Float, extL: Float, extR: Float, margin: Float): Boolean {
    var above = 0
    var below = 0
    var left = 0
    var right = 0
    for (i in 0 until 4) {
        val x = q[2 * i]
        val y = q[2 * i + 1]
        if (y < -margin) above++
        if (y > h + margin) below++
        val t = 1f - y / h // 이 높이에서 빗변이 화면 밖으로 벌어진 비율
        if (x < -extL * t - margin) left++
        if (x > w + extR * t + margin) right++
    }
    return above < 4 && below < 4 && left < 4 && right < 4
}

internal const val TILE_DP = 256f
/**
 * 프레임 타일 버퍼 크기. 타일로 그릴지는 사다리꼴 컬링 뒤 수로 정한다: [MAX_TILES] - 1장을 넘으면(큰 축척·넓은 창) 타일 대신 벡터로 그린다(CorridorMap.kt drawTiles).
 * 컬링 전 AABB 칸 수는 반복 상한(× 4)으로만 쓴다. 글랜스 6 m/dp·분할 창 658×552 dp 에서 AABB 최대 7×6 = 42칸, 컬링 뒤 최대 약 25장.
 */
internal const val MAX_VISIBLE_TILES = 48
/** LRU 용량. 한 프레임에 쓰는 타일(컬링 뒤 최대 MAX_TILES - 1장)보다 커야 밀려난 타일을 다시 그리는 왕복이 안 생긴다(256 KB × density² × 48 ≈ 12 MB@1x). */
internal const val MAX_TILES = MAX_VISIBLE_TILES
private const val TILE_INDEX_OFFSET = 16_384
