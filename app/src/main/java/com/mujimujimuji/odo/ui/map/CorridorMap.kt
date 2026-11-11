package com.mujimujimuji.odo.ui.map

import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoColors
import com.mujimujimuji.odo.ui.theme.OdoSize
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 코리도 지도(설계서 §4.2). 데이터·카메라가 바뀔 때만 다시 그린다. 지도 위 글자 0, 트윈 0, 네트워크 0.
 * - [data]가 null(로딩 중)이거나 [camera].center가 코리도 bbox+2 km 밖이면 계기 모드(`ground` + 1 km 격자)로 그린다.
 * - [passed]: 지난 길. `inkDim` 50 % 3dp 단순 대시(8dp 선·8dp 틈), [trail]=true면 [trailColor] 45 % 6dp 리본(도로보다 어두운 색은 `ink`, [ribbonColor]). 경로는 지난 구간 뒤(남은 구간)만 그린다.
 * - [showRoute]=false면 경로(남은 구간)를 그리지 않는다. 요약의 띠 없는 폴백(658×552 창, [TripFitScene])이 스크림 아래 정적 지도만 깔 때 쓴다.
 * - 모의 미니카 점 2대(heading-up만): 차의 경로 진행 기준 앞 500 m·뒤 300 m. 오프셋만 3 s마다 ±30 m 안에서 새로 정하고(보간 없음) 자리는 매 프레임 다시 잡는다.
 * - [cover]: 지도를 덮는 패널 상자(캔버스 px). 휴게소 픽토가 이 상자에 걸치면 그리지 않는다(패널 가장자리에서 반쯤 잘린 픽토 방지).
 * - 속도 ≥80 km/h면 tertiary 도로를 숨긴다(휴게소 픽토는 유지).
 * - [content]: 화면 좌표 오버레이. 미니카는 호출자가 Box로 위에 올린다([NaviMinicar]).
 * 그리기 순서: ground → urban → forest → water → 강 → 도로 케이싱 → 도로 코어 → 고속도로 중앙 파선 → 지난 길(대시·리본) → 경로 → 휴게소 픽토 → 모의 미니카 점 → content → 수평선 스크림(heading-up만).
 * 성능: heading-up(매 프레임 이동·회전)에서는 ground~파선의 정적 층을 [TileCache] 타일 비트맵으로 그린다. north-up(정지 화면)은 벡터 그대로 그리고, 호출자([NorthUpScene]·[TripFitScene])가 오프스크린 합성층에 담아 다시 쓴다.
 */
@Composable
fun CorridorMap(
    data: CorridorData?,
    camera: MapCamera,
    speedKmh: Float,
    passed: List<LatLng>,
    trail: Boolean,
    modifier: Modifier = Modifier,
    trailColor: Color = LocalOdoColors.current.amber,
    cover: Rect = Rect.Zero,
    showRoute: Boolean = true,
    content: DrawScope.() -> Unit = {},
) {
    val colors = LocalOdoColors.current
    val densityF = LocalDensity.current.density
    val paths = remember(data) { data?.let { CorridorPaths.of(it) } }
    val scratch = remember(data) { MapScratch(data?.features?.size ?: 0) }
    val palette = remember(colors) { MapPalette(colors) }
    val tileCache = remember(paths, palette, densityF) { paths?.let { TileCache(it, palette, densityF) } }
    DisposableEffect(tileCache) { onDispose { tileCache?.close() } }
    val mpd = camera.metersPerDp
    // 지난 구간/남은 구간 분기점: heading-up은 카메라 중심(= 차), north-up은 지난 길의 마지막 점.
    val splitPoint = if (camera.northUp) passed.lastOrNull() else camera.center
    val splitM = remember(data, splitPoint) {
        if (data == null || splitPoint == null) 0.0 else data.routeProgress(data.localX(splitPoint), data.localY(splitPoint))
    }
    // heading-up 에서는 화면 깊이(≈3 km)보다 넉넉한 앞 구간만 Path 로 만든다(프레임마다 66 km 전체를 스트로크하지 않도록).
    val remaining = remember(paths, camera.northUp, (splitM / 10.0).toInt(), showRoute) {
        if (!showRoute) null else paths?.let { remainingRoute(it.data, splitM, if (camera.northUp) Double.POSITIVE_INFINITY else ROUTE_AHEAD_M) }
    }
    val stitch = remember(data, passed, mpd, trail) {
        when {
            data == null || passed.size < 2 -> null
            trail -> Stitch(ribbon = polyline(data, passed))
            else -> Stitch(dashes = stitchDashes(data, passed, STITCH_DASH_DP * mpd, STITCH_GAP_DP * mpd))
        }
    }
    val mockEnabled = data != null && !camera.northUp && data.contains(camera.center, OUTSIDE_MARGIN_M)
    val mockOffsets = rememberMockOffsets(mockEnabled)
    val mockRing = remember(densityF) { Stroke(1f * densityF) }
    val pictoStrokes = remember(densityF) { RestPictoStrokes(densityF) }
    val scrim = remember(colors.ground, densityF) {
        Brush.verticalGradient(listOf(colors.ground, colors.ground.copy(alpha = 0f)), startY = 0f, endY = SCRIM_DP * densityF)
    }
    val trailArgb = remember(trailColor, colors) { ribbonColor(trailColor, colors).toArgb() }
    // Canvas 람다 밖에서 Boolean 으로 바꿔 둔다. 람다가 Float 속도를 캡처하면 속도 값이 바뀔 때마다(실신호 10 Hz) 같은 그림을 다시 그린다.
    val hideTertiary = speedKmh >= TERTIARY_HIDE_KMH

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val px = density
        drawRect(colors.ground)
        val d = data
        val p = paths
        if (d == null || p == null || !d.contains(camera.center, OUTSIDE_MARGIN_M)) {
            drawInstrumentGrid(colors.line, camera, px)
            content()
            return@Canvas
        }
        val sc = scratch
        val af = camera.anchorFraction
        var ax = w * af.x
        var ay = h * af.y
        setQuad(sc.rect, 0f, 0f, w, 0f, w, h, 0f, h)
        val k = topWidthRatio(camera.tiltDeg)
        val tilted = k < 0.999f
        var extL = 0f
        var extR = 0f
        if (tilted) {
            // 원근: 소스 사다리꼴(상변이 1/k 배 넓음) → 화면 사각형. 멀수록 좁아지고 납작해진다.
            // 상변을 기준점 x(ax) 좌우로 같은 비율만큼 벌려 소실점이 미니카 바로 위에 오게 한다(ax = w/2면 좌우 대칭).
            val spread = 1f / k - 1f
            extL = ax * spread
            extR = (w - ax) * spread
            setQuad(sc.quad, -extL, 0f, w + extR, 0f, w, h, 0f, h)
            sc.persp.setPolyToPoly(sc.quad, 0, sc.rect, 0, 4)
            if (!sc.persp.invert(sc.inv)) return@Canvas
            sc.pt[0] = ax
            sc.pt[1] = ay
            sc.inv.mapPoints(sc.pt)
            ax = sc.pt[0]
            ay = sc.pt[1]
        } else {
            sc.persp.reset()
        }
        val s = px / mpd
        val cx = d.localX(camera.center).toFloat()
        val cy = d.localY(camera.center).toFloat()
        sc.map.setTranslate(-cx, -cy)
        if (!camera.northUp) sc.map.postRotate(camera.headingDeg)
        sc.map.postScale(s, -s)
        sc.map.postTranslate(ax, ay)
        sc.total.set(sc.map)
        sc.total.postConcat(sc.persp)
        if (!sc.total.invert(sc.inv)) return@Canvas
        sc.inv.mapPoints(sc.corners, 0, sc.rect, 0, 4)
        var x0 = sc.corners[0]
        var x1 = x0
        var y0 = sc.corners[1]
        var y1 = y0
        for (i in 1 until 4) {
            x0 = min(x0, sc.corners[2 * i])
            x1 = max(x1, sc.corners[2 * i])
            y0 = min(y0, sc.corners[2 * i + 1])
            y1 = max(y1, sc.corners[2 * i + 1])
        }
        val marginM = CULL_MARGIN_DP * mpd
        x0 -= marginM
        y0 -= marginM
        x1 += marginM
        y1 += marginM
        val tc = tileCache
        tc?.versionForFrame() // 타일이 완성되면 이 람다가 다시 실행된다(읽기 = 구독)

        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            val save = nc.save()
            if (tilted) nc.concat(sc.persp)
            nc.concat(sc.map)
            // 정적 층: heading-up 은 타일 비트맵(가까운 타일부터, 없는 칸은 ground 로 남겼다가 완성되면 채워짐), north-up 은 벡터.
            val tiled = tc != null && !camera.northUp &&
                drawTiles(nc, tc, sc, mpd, w, h, extL, extR, CULL_MARGIN_DP * px, !hideTertiary, x0, y0, x1, y1, ax, ay)
            if (!tiled) {
                d.collectVisible(x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble(), sc.visible)
                drawStaticLayers(nc, d, p, sc, palette, mpd, hideTertiary)
            }
            if (stitch != null) {
                val dashes = stitch.dashes
                if (dashes != null) {
                    // 지난 길: 단순 대시. 화면 bbox 안 선분만 모아 drawLines 1번(프레임당 할당 0, 예전 점+테두리 2겹은 고리 사슬로 보였다).
                    val buf = sc.linesFor(dashes.size)
                    var n = 0
                    var i = 0
                    while (i < dashes.size) {
                        val lx = dashes[i]
                        val ly = dashes[i + 1]
                        if (lx >= x0 && lx <= x1 && ly >= y0 && ly <= y1) {
                            buf[n] = lx
                            buf[n + 1] = ly
                            buf[n + 2] = dashes[i + 2]
                            buf[n + 3] = dashes[i + 3]
                            n += 4
                        }
                        i += 4
                    }
                    if (n > 0) {
                        sc.stitch.color = palette.stitch
                        sc.stitch.strokeWidth = STITCH_WIDTH_DP * mpd
                        nc.drawLines(buf, 0, n, sc.stitch)
                    }
                }
                val ribbon = stitch.ribbon
                if (ribbon != null) {
                    sc.stroke.color = trailArgb
                    sc.stroke.strokeWidth = RIBBON_DP * mpd
                    nc.drawPath(ribbon, sc.stroke)
                }
            }
            if (remaining != null) {
                sc.stroke.color = palette.routeOutline
                sc.stroke.strokeWidth = ROUTE_OUTLINE_DP * mpd
                nc.drawPath(remaining, sc.stroke)
                sc.stroke.color = palette.route
                sc.stroke.strokeWidth = ROUTE_DP * mpd
                nc.drawPath(remaining, sc.stroke)
            }
            nc.restoreToCount(save)
        }

        // 화면 좌표 요소(원근·축척과 무관하게 같은 크기).
        // 휴게소 픽토: 이미 그린 픽토와 36dp 안에 겹치면 건너뛴다(요약 bbox 맞춤 20 m/dp 이상에서 16곳이 뭉친다).
        // 패널([cover])에 걸치는 픽토도 건너뛴다(가장자리에서 반쯤 잘려 실수처럼 보였다).
        val pad = OdoSize.picto.toPx()
        val half = pad / 2f
        val covered = cover.width > 0f && cover.height > 0f
        val drawnPictos = sc.pictos
        var drawnCount = 0
        for (poi in d.pois) {
            sc.pt[0] = poi.x.toFloat()
            sc.pt[1] = poi.y.toFloat()
            sc.total.mapPoints(sc.pt)
            val sx = sc.pt[0]
            val sy = sc.pt[1]
            if (sx < -pad || sx > w + pad || sy < -pad || sy > h + pad) continue
            if (covered && sx + half > cover.left && sx - half < cover.right && sy + half > cover.top && sy - half < cover.bottom) continue
            var clash = false
            var j = 0
            while (j < drawnCount) {
                if (abs(drawnPictos[j] - sx) < pad && abs(drawnPictos[j + 1] - sy) < pad) {
                    clash = true
                    break
                }
                j += 2
            }
            if (clash) continue
            if (drawnCount + 1 < drawnPictos.size) {
                drawnPictos[drawnCount] = sx
                drawnPictos[drawnCount + 1] = sy
                drawnCount += 2
            }
            drawRestPicto(Offset(sx, sy), colors, pictoStrokes)
        }
        if (mockOffsets != null) {
            // 자리는 매 프레임 차의 경로 진행(splitM) + 오프셋 → 20배속 데모에서도 차 앞뒤 고정 거리에 머문다.
            for (off in mockOffsets) {
                if (!d.mockCarAt(splitM, off, sc.route)) continue
                sc.pt[0] = sc.route[0].toFloat()
                sc.pt[1] = sc.route[1].toFloat()
                sc.total.mapPoints(sc.pt)
                val c = Offset(sc.pt[0], sc.pt[1])
                if (c.x >= -pad && c.x <= w + pad && c.y >= -pad && c.y <= h + pad) {
                    drawCircle(colors.inkDim, MOCK_DOT_DP / 2f * px, c)
                    drawCircle(colors.ground, (MOCK_DOT_DP + 1f) / 2f * px, c, style = mockRing)
                }
            }
        }
        content()
        if (!camera.northUp) drawRect(brush = scrim, size = Size(w, SCRIM_DP * px))
    }
}

/** 요소별 `android.graphics.Path`(로컬 m). 데이터당 1회 생성(백그라운드 가능), 이후 행렬 변환만. */
class CorridorPaths private constructor(val data: CorridorData) {
    val paths: Array<Path> = Array(data.features.size) { i ->
        val f = data.features[i]
        polyline(f.pts, close = f.isPolygon)
    }

    companion object {
        @Volatile
        private var cached: CorridorPaths? = null

        /** 같은 [CorridorData]면 같은 Path 묶음(프로세스 캐시 1개). */
        fun of(data: CorridorData): CorridorPaths {
            cached?.let { if (it.data === data) return it }
            return CorridorPaths(data).also { cached = it }
        }

        fun polyline(pts: IntArray, close: Boolean): Path {
            val path = Path()
            path.moveTo(pts[0].toFloat(), pts[1].toFloat())
            var i = 2
            while (i < pts.size) {
                path.lineTo(pts[i].toFloat(), pts[i + 1].toFloat())
                i += 2
            }
            if (close) path.close()
            return path
        }
    }
}

/** 지난 길 표현: 대시 선분 [x0,y0,x1,y1,…](로컬 m) 또는 리본 Path. */
private class Stitch(val dashes: FloatArray? = null, val ribbon: Path? = null)

/**
 * 지난 길 리본 색: 내 차 색 45 %. 도로 코어보다 어두운 색(8색 중 그래파이트)은 도로 위에서 묻혀(1.2~1.3:1)
 * 지난 길이 사라지므로 `ink` 45 %로 바꾼다(펄 리본과 같은 수준, 도로 위 2.8/2.5:1).
 */
internal fun ribbonColor(trailColor: Color, colors: OdoColors): Color =
    (if (trailColor.luminance() < colors.roadCore.luminance()) colors.ink else trailColor).copy(alpha = 0.45f)

internal class MapPalette(c: OdoColors) {
    val ground = c.ground.toArgb()
    val urban = c.mapUrban.toArgb()
    val forest = c.mapForest.toArgb()
    val water = c.mapWater.toArgb()
    val casing = c.roadCasing.toArgb()
    val core = IntArray(RoadClass.entries.size) { c.roadCore.copy(alpha = RoadClass.entries[it].alpha).toArgb() }
    val dash = c.ink.copy(alpha = 0.12f).toArgb()
    /** 지난 길 대시. `routePassed`는 roadCore와 명도가 같아 도로 위에서 묻히므로 `inkDim` 50 %(바탕 위 2.6:1, 장식). */
    val stitch = c.inkDim.copy(alpha = STITCH_ALPHA).toArgb()
    val route = c.route.toArgb()
    val routeOutline = c.routeOutline.toArgb()
}

/** 프레임마다 재사용하는 행렬·페인트·버퍼(프레임당 할당 0). 스레드마다 하나(프레임용 1, 타일 작업 스레드용 1). */
internal class MapScratch(featureCount: Int) {
    val visible = VisibleSet(featureCount)
    val map = Matrix()
    val persp = Matrix()
    val total = Matrix()
    val inv = Matrix()
    val quad = FloatArray(8)
    val rect = FloatArray(8)
    val corners = FloatArray(8)
    val pt = FloatArray(2)
    /** 경로 위 한 점(로컬 m, 모의 미니카 자리). */
    val route = DoubleArray(2)
    /** 이번 프레임에 그린 휴게소 픽토의 화면 좌표(겹침 제거용, 최대 32곳). */
    val pictos = FloatArray(64)
    /** 타일 그리기용: 모서리 버퍼, 보이는 타일 (tx,ty) 목록과 앵커까지 거리², 대상 사각형, 비트맵 페인트(AA 없음 → 이웃 타일 사이 실금 없음). */
    val tileQuad = FloatArray(8)
    val tileIdx = IntArray(MAX_VISIBLE_TILES * 2)
    val tileDist = FloatArray(MAX_VISIBLE_TILES)
    val dst = RectF()
    val bitmap = Paint(Paint.FILTER_BITMAP_FLAG)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    /** 지난 길 대시: 끝이 각진 선분(BUTT). */
    val stitch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private var lines = FloatArray(256)
    private var dashMpd = -1f
    private var dash: DashPathEffect? = null

    /** 보이는 대시 선분을 모을 버퍼. 트립이 길어질 때만 늘린다. */
    fun linesFor(size: Int): FloatArray {
        if (lines.size < size) lines = FloatArray(size)
        return lines
    }

    fun dashFor(mpd: Float): DashPathEffect {
        if (dashMpd != mpd || dash == null) {
            dash = DashPathEffect(floatArrayOf(DASH_ON_DP * mpd, DASH_OFF_DP * mpd), 0f)
            dashMpd = mpd
        }
        return dash!!
    }
}

/** 정적 층(면 → 강 → 도로 케이싱 → 코어 → 고속도로 파선)을 로컬 m 좌표계로 변환된 [nc]에 그린다. [sc].visible 은 호출자가 채운다. */
internal fun drawStaticLayers(nc: AndroidCanvas, d: CorridorData, p: CorridorPaths, sc: MapScratch, palette: MapPalette, mpd: Float, hideTertiary: Boolean) {
    val feats = d.features
    val vis = sc.visible
    sc.fill.color = palette.urban
    drawLayer(nc, feats, p, vis, MapLayer.URBAN, sc.fill)
    sc.fill.color = palette.forest
    drawLayer(nc, feats, p, vis, MapLayer.FOREST, sc.fill)
    sc.fill.color = palette.water
    drawLayer(nc, feats, p, vis, MapLayer.WATER, sc.fill)
    sc.stroke.pathEffect = null
    sc.stroke.color = palette.water
    sc.stroke.strokeWidth = RIVER_DP * mpd
    drawLayer(nc, feats, p, vis, MapLayer.RIVER, sc.stroke)
    sc.stroke.color = palette.casing
    for (cls in RoadClass.entries) {
        if (hideTertiary && cls == RoadClass.TERTIARY) continue
        sc.stroke.strokeWidth = (cls.widthDp + CASING_EXTRA_DP) * mpd
        drawRoads(nc, feats, p, vis, cls, sc.stroke)
    }
    for (cls in RoadClass.entries) {
        if (hideTertiary && cls == RoadClass.TERTIARY) continue
        sc.stroke.color = palette.core[cls.ordinal]
        sc.stroke.strokeWidth = cls.widthDp * mpd
        drawRoads(nc, feats, p, vis, cls, sc.stroke)
    }
    sc.stroke.color = palette.dash
    sc.stroke.strokeWidth = 1f * mpd
    sc.stroke.pathEffect = sc.dashFor(mpd)
    drawRoads(nc, feats, p, vis, RoadClass.MOTORWAY, sc.stroke)
    sc.stroke.pathEffect = null
}

/**
 * 보이는 타일(소스 사다리꼴과 겹치는 것)을 앵커에 가까운 순으로 drawBitmap 한다. [nc]는 로컬 m 좌표계(원근 포함)로 변환된 상태.
 * 사다리꼴 컬링을 통과한 타일이 [MAX_TILES] - 1장을 넘으면(축척이 크거나 창이 넓음) 아무것도 그리거나 요청하지 않고 false 를 돌려 호출자가 벡터로 그리게 한다.
 * 한도가 LRU 용량보다 1장 작은 까닭: 보이는 타일이 LRU 를 꽉 채우면 상세 플래그(80 km/h)가 바뀔 때 방금 도착한 가장 가까운 새 타일이 다음 도착분에 밀려나 구멍이 난다.
 * 컬링 전 AABB 칸 수는 반복 횟수만 막는 넉넉한 상한([MAX_VISIBLE_TILES] × 4)이다.
 */
private fun drawTiles(
    nc: AndroidCanvas, tc: TileCache, sc: MapScratch, mpd: Float, w: Float, h: Float, extL: Float, extR: Float, marginPx: Float, detail: Boolean,
    x0: Float, y0: Float, x1: Float, y1: Float, ax: Float, ay: Float,
): Boolean {
    val tileM = TILE_DP * mpd
    val txMin = floor(x0 / tileM).toInt()
    val txMax = floor(x1 / tileM).toInt()
    val tyMin = floor(y0 / tileM).toInt()
    val tyMax = floor(y1 / tileM).toInt()
    if ((txMax - txMin + 1).toLong() * (tyMax - tyMin + 1) > MAX_VISIBLE_TILES * 4) return false
    val q = sc.tileQuad
    val idx = sc.tileIdx
    val dist = sc.tileDist
    var n = 0
    for (ty in tyMin..tyMax) {
        for (tx in txMin..txMax) {
            val l = tx * tileM
            val b = ty * tileM
            setQuad(q, l, b, l + tileM, b, l + tileM, b + tileM, l, b + tileM)
            sc.map.mapPoints(q)
            if (!quadMayOverlapTrapezoid(q, w, h, extL, extR, marginPx)) continue
            if (n >= MAX_TILES - 1) return false // 아직 그리거나 요청한 타일이 없으니 여기서 벡터로 넘겨도 된다
            val cx = (q[0] + q[2] + q[4] + q[6]) * 0.25f - ax
            val cy = (q[1] + q[3] + q[5] + q[7]) * 0.25f - ay
            val dd = cx * cx + cy * cy
            // 거리 오름차순 삽입(≤ MAX_TILES - 1 = 47개)
            var i = n
            while (i > 0 && dist[i - 1] > dd) {
                dist[i] = dist[i - 1]
                idx[2 * i] = idx[2 * i - 2]
                idx[2 * i + 1] = idx[2 * i - 1]
                i--
            }
            dist[i] = dd
            idx[2 * i] = tx
            idx[2 * i + 1] = ty
            n++
        }
    }
    for (i in 0 until n) {
        val tx = idx[2 * i]
        val ty = idx[2 * i + 1]
        val bmp = tc.get(mpd, detail, tx, ty) ?: continue
        sc.dst.set(tx * tileM, ty * tileM, (tx + 1) * tileM, (ty + 1) * tileM)
        nc.drawBitmap(bmp, null, sc.dst, sc.bitmap)
    }
    return true
}

private fun drawLayer(nc: AndroidCanvas, feats: List<MapFeature>, paths: CorridorPaths, vis: VisibleSet, layer: MapLayer, paint: Paint) {
    val ids = vis.ids
    for (k in 0 until vis.count) {
        val id = ids[k]
        if (feats[id].layer == layer) nc.drawPath(paths.paths[id], paint)
    }
}

private fun drawRoads(nc: AndroidCanvas, feats: List<MapFeature>, paths: CorridorPaths, vis: VisibleSet, cls: RoadClass, paint: Paint) {
    val ids = vis.ids
    for (k in 0 until vis.count) {
        val id = ids[k]
        if (feats[id].road == cls) nc.drawPath(paths.paths[id], paint)
    }
}

/** 틸트 각 → 상변 폭 비율(58° ≈ 0.45, 0°는 1 = 원근 없음). */
internal fun topWidthRatio(tiltDeg: Float): Float =
    if (tiltDeg <= 0f) 1f else (1f - 0.55f * (tiltDeg / MapCamera.GLANCE_TILT_DEG)).coerceIn(0.3f, 1f)

/** 분기점 s(m)부터 끝(또는 [maxLenM] 앞)까지의 경로 Path(로컬 m). */
private fun remainingRoute(d: CorridorData, splitM: Double, maxLenM: Double): Path {
    val path = Path()
    val tmp = DoubleArray(2)
    d.routePoint(splitM, tmp)
    path.moveTo(tmp[0].toFloat(), tmp[1].toFloat())
    var px = tmp[0]
    var py = tmp[1]
    var len = 0.0
    val r = d.route
    var i = d.routeIndexAfter(splitM)
    while (2 * i + 1 < r.size && len <= maxLenM) {
        val x = r[2 * i].toDouble()
        val y = r[2 * i + 1].toDouble()
        len += sqrt((x - px) * (x - px) + (y - py) * (y - py))
        px = x
        py = y
        path.lineTo(x.toFloat(), y.toFloat())
        i++
    }
    return path
}

private fun polyline(d: CorridorData, pts: List<LatLng>): Path {
    val path = Path()
    path.moveTo(d.localX(pts[0]).toFloat(), d.localY(pts[0]).toFloat())
    for (i in 1 until pts.size) path.lineTo(d.localX(pts[i]).toFloat(), d.localY(pts[i]).toFloat())
    return path
}

/**
 * 지난 길을 따라 [onM] 선·[offM] 틈을 번갈아 놓은 대시 선분들 [x0,y0,x1,y1,…](로컬 m). 첫 선은 시작점에서.
 * 대시가 꼭짓점을 넘으면 거기서 두 선분으로 나눈다(꺾인 길에서도 선분이 길을 벗어나지 않게).
 */
private fun stitchDashes(d: CorridorData, pts: List<LatLng>, onM: Float, offM: Float): FloatArray {
    val on = onM.toDouble().coerceAtLeast(0.5)
    val off = offM.toDouble().coerceAtLeast(0.5)
    var out = FloatArray(64)
    var n = 0
    var drawing = true
    var remain = on
    var px = d.localX(pts[0])
    var py = d.localY(pts[0])
    for (i in 1 until pts.size) {
        val qx = d.localX(pts[i])
        val qy = d.localY(pts[i])
        val len = sqrt((qx - px) * (qx - px) + (qy - py) * (qy - py))
        var pos = 0.0
        while (pos < len) {
            val step = min(remain, len - pos)
            if (drawing) {
                if (n + 4 > out.size) out = out.copyOf(out.size * 2)
                out[n++] = (px + (qx - px) * pos / len).toFloat()
                out[n++] = (py + (qy - py) * pos / len).toFloat()
                out[n++] = (px + (qx - px) * (pos + step) / len).toFloat()
                out[n++] = (py + (qy - py) * (pos + step) / len).toFloat()
            }
            pos += step
            remain -= step
            if (remain <= 1e-9) {
                drawing = !drawing
                remain = if (drawing) on else off
            }
        }
        px = qx
        py = qy
    }
    return if (n == out.size) out else out.copyOf(n)
}

/**
 * 모의 미니카 2대의 경로 거리 오프셋(m, 앞 +·뒤 −). heading-up에서만.
 * 3 s마다(80 km/h 이상에서의 실레이더 갱신 주기, 기획서 로드 레이더 표) 앞 500 m·뒤 300 m 둘레 ±30 m 안에서 새로 정한다. 보간 없음.
 * 자리를 경로 위 절대 좌표로 두면 20배속 데모(실시간 1초에 약 556 m)에서 1초 남짓 만에 차 뒤로 빠져 점이 0~1개만 보였다.
 */
@Composable
private fun rememberMockOffsets(enabled: Boolean): DoubleArray? {
    var offsets by remember { mutableStateOf<DoubleArray?>(null) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            offsets = null
            return@LaunchedEffect
        }
        val rnd = Random(MOCK_SEED)
        while (true) {
            offsets = doubleArrayOf(
                MOCK_AHEAD_M + rnd.nextDouble(-MOCK_JITTER_M, MOCK_JITTER_M),
                -(MOCK_BEHIND_M + rnd.nextDouble(-MOCK_JITTER_M, MOCK_JITTER_M)),
            )
            delay(MOCK_REFRESH_MS)
        }
    }
    return offsets
}

/** 사각형 네 모서리 (x,y)×4 를 [q]에 쓴다. */
private fun setQuad(q: FloatArray, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
    q[0] = x0
    q[1] = y0
    q[2] = x1
    q[3] = y1
    q[4] = x2
    q[5] = y2
    q[6] = x3
    q[7] = y3
}

/** 계기 모드: 카메라 중심을 지나는 1 km 격자(`line` 1dp). 글자 없음. */
private fun DrawScope.drawInstrumentGrid(line: Color, camera: MapCamera, px: Float) {
    val step = GRID_M / camera.metersPerDp * px
    if (step < 8f) return
    val a = camera.anchor(size.width, size.height)
    var x = a.x - floor(a.x / step) * step
    while (x <= size.width) {
        drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = px)
        x += step
    }
    var y = a.y - floor(a.y / step) * step
    while (y <= size.height) {
        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = px)
        y += step
    }
}

private const val OUTSIDE_MARGIN_M = 2_000.0
private const val GRID_M = 1_000f
private const val TERTIARY_HIDE_KMH = 80f
/** 컬링 여백(dp). 프레임의 화면 bbox 와 [TileCache]의 타일 bbox 양쪽에 쓴다. */
internal const val CULL_MARGIN_DP = 16f
private const val CASING_EXTRA_DP = 2f
private const val RIVER_DP = 3f
private const val DASH_ON_DP = 24f
private const val DASH_OFF_DP = 36f
/** 지난 길 대시: 3dp 굵기, 8dp 선·8dp 틈, `inkDim` 50 %. */
private const val STITCH_WIDTH_DP = 3f
private const val STITCH_DASH_DP = 8f
private const val STITCH_GAP_DP = 8f
private const val STITCH_ALPHA = 0.5f
/** 트레일(Lv10) 리본 굵기. */
private const val RIBBON_DP = 6f
private const val ROUTE_OUTLINE_DP = 12f
private const val ROUTE_DP = 8f
/** heading-up 에서 Path 로 만드는 남은 경로 길이(화면 깊이 ≈3 km 의 여유분). */
private const val ROUTE_AHEAD_M = 8_000.0
private const val MOCK_DOT_DP = 10f
private const val MOCK_REFRESH_MS = 3_000L
/** 글랜스(6 m/dp, 틸트 58°)에서 앞 500 m ≈ 미니카 위 105dp, 뒤 300 m ≈ 아래 80dp → 미니카(±34dp)와 겹치지 않는다. */
private const val MOCK_AHEAD_M = 500.0
private const val MOCK_BEHIND_M = 300.0
private const val MOCK_JITTER_M = 30.0
private const val MOCK_SEED = 7
private const val SCRIM_DP = 80f
