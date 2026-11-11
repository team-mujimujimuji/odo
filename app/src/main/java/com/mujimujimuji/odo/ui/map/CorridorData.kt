package com.mujimujimuji.odo.ui.map

import com.mujimujimuji.odo.engine.LatLng
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sqrt

/** 지도 레이어. 선언 순서 = 그리는 순서(시가지 → 숲 → 물 → 강 → 도로). */
enum class MapLayer { URBAN, FOREST, WATER, RIVER, ROAD }

/** 도로 등급: 코어 굵기(dp)와 같은 색의 알파(설계서 §4.2). 선언 순서 = 그리는 순서(낮은 등급이 아래). */
enum class RoadClass(val key: String, val widthDp: Float, val alpha: Float) {
    TERTIARY("tertiary", 2.5f, 0.6f),
    SECONDARY("secondary", 4f, 0.7f),
    PRIMARY("primary", 6f, 0.8f),
    TRUNK("trunk", 7f, 0.9f),
    MOTORWAY("motorway", 9f, 1f);

    companion object {
        fun byKey(key: String): RoadClass? = entries.firstOrNull { it.key == key }
    }
}

/** 지도 요소 하나: 로컬 정수 미터 좌표 [x0,y0,x1,y1,…]. 면(URBAN·FOREST·WATER)은 닫힌 고리(마지막 점 ≠ 첫 점). */
class MapFeature(val layer: MapLayer, val road: RoadClass?, val pts: IntArray) {
    val minX: Int
    val minY: Int
    val maxX: Int
    val maxY: Int

    init {
        require(pts.size >= 4 && pts.size % 2 == 0) { "feature needs ≥2 points" }
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var y1 = Int.MIN_VALUE
        var i = 0
        while (i < pts.size) {
            val x = pts[i]
            val y = pts[i + 1]
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
            i += 2
        }
        minX = x0
        minY = y0
        maxX = x1
        maxY = y1
    }

    val pointCount: Int get() = pts.size / 2
    val isPolygon: Boolean get() = layer == MapLayer.URBAN || layer == MapLayer.FOREST || layer == MapLayer.WATER
}

/** 휴게소 등 지점(로컬 m). `src`는 OSM 요소 ID(출처 추적용, 화면에는 안 그림). */
class MapPoi(val type: String, val x: Int, val y: Int, val src: String)

/** 한 프레임에 보이는 요소 ID를 중복 없이 모은다(스탬프 방식, 프레임당 할당 0). */
class VisibleSet(n: Int) {
    val ids = IntArray(n)
    var count = 0
        private set
    private val stamp = IntArray(n)
    private var frame = 0

    fun begin() {
        frame++
        count = 0
    }

    fun add(id: Int) {
        if (stamp[id] != frame) {
            stamp[id] = frame
            ids[count++] = id
        }
    }
}

/**
 * `assets/map/corridor.json`(tools/map/build_corridor.py 산출물) 파서 + 1 km 셀 색인 + 경로 보조 계산.
 * 순수 Kotlin(org.json만)이라 JVM 단위 테스트 가능. Path 생성은 렌더러([CorridorMap])가 1회 한다.
 *
 * 좌표계: 로컬 정수 미터, x = (lng − origin.lng)·111320·cos(origin.lat), y = (lat − origin.lat)·111320 (북쪽이 +y).
 * 출처: © OpenStreetMap contributors, ODbL 1.0. [attribution]을 정차 화면 캡션으로 보여 준다.
 */
class CorridorData(json: String) {
    val version: Int
    val originLat: Double
    val originLng: Double
    val attribution: String
    val cellM: Int
    val minX: Int
    val minY: Int
    val maxX: Int
    val maxY: Int
    val features: List<MapFeature>
    /** 시나리오 경로 [x,y,…] 로컬 m. */
    val route: IntArray
    val pois: List<MapPoi>
    /** 모든 요소 + 경로의 점 수(검증·로그용). */
    val pointCount: Int

    private val kx: Double
    private val ky = 111_320.0
    private val cols: Int
    private val rows: Int
    private val cells: Array<IntArray>
    private val routeCum: DoubleArray

    init {
        val o = JSONObject(json)
        version = o.getInt("version")
        val origin = o.getJSONObject("origin")
        originLat = origin.getDouble("lat")
        originLng = origin.getDouble("lng")
        attribution = o.optString("attribution", ATTRIBUTION)
        cellM = o.optInt("cell_m", 1000).coerceAtLeast(100)
        kx = ky * cos(Math.toRadians(originLat))

        val list = ArrayList<MapFeature>()
        val polys = o.getJSONObject("polys")
        fun addAll(arr: JSONArray?, layer: MapLayer) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val pts = arr.getJSONArray(i).toIntArray()
                if (pts.size >= 4) list.add(MapFeature(layer, null, pts))
            }
        }
        addAll(polys.optJSONArray("urban"), MapLayer.URBAN)
        addAll(polys.optJSONArray("forest"), MapLayer.FOREST)
        addAll(polys.optJSONArray("water"), MapLayer.WATER)
        addAll(o.optJSONArray("rivers"), MapLayer.RIVER)
        val roads = o.getJSONArray("roads")
        for (i in 0 until roads.length()) {
            val r = roads.getJSONObject(i)
            val cls = RoadClass.byKey(r.getString("cls")) ?: continue
            val pts = r.getJSONArray("pts").toIntArray()
            if (pts.size >= 4) list.add(MapFeature(MapLayer.ROAD, cls, pts))
        }
        features = list
        route = o.getJSONArray("route").toIntArray()
        require(route.size >= 4 && route.size % 2 == 0) { "route needs ≥2 points" }
        val p = o.optJSONArray("pois")
        pois = if (p == null) emptyList() else List(p.length()) { i ->
            val q = p.getJSONObject(i)
            MapPoi(q.getString("type"), q.getInt("x"), q.getInt("y"), q.optString("src", ""))
        }

        var x0 = route[0]
        var y0 = route[1]
        var x1 = route[0]
        var y1 = route[1]
        var i = 2
        while (i < route.size) {
            if (route[i] < x0) x0 = route[i]
            if (route[i] > x1) x1 = route[i]
            if (route[i + 1] < y0) y0 = route[i + 1]
            if (route[i + 1] > y1) y1 = route[i + 1]
            i += 2
        }
        for (f in features) {
            if (f.minX < x0) x0 = f.minX
            if (f.maxX > x1) x1 = f.maxX
            if (f.minY < y0) y0 = f.minY
            if (f.maxY > y1) y1 = f.maxY
        }
        minX = x0
        minY = y0
        maxX = x1
        maxY = y1
        pointCount = features.sumOf { it.pointCount } + route.size / 2

        cols = (maxX - minX) / cellM + 1
        rows = (maxY - minY) / cellM + 1
        val counts = IntArray(cols * rows)
        for (f in features) forEachCellOf(f) { counts[it]++ }
        cells = Array(cols * rows) { IntArray(counts[it]) }
        val fill = IntArray(cols * rows)
        features.forEachIndexed { id, f -> forEachCellOf(f) { c -> cells[c][fill[c]++] = id } }

        routeCum = DoubleArray(route.size / 2)
        for (k in 1 until routeCum.size) {
            val dx = (route[2 * k] - route[2 * k - 2]).toDouble()
            val dy = (route[2 * k + 1] - route[2 * k - 1]).toDouble()
            routeCum[k] = routeCum[k - 1] + sqrt(dx * dx + dy * dy)
        }
    }

    private inline fun forEachCellOf(f: MapFeature, action: (Int) -> Unit) {
        val cx0 = (f.minX - minX) / cellM
        val cx1 = (f.maxX - minX) / cellM
        val cy0 = (f.minY - minY) / cellM
        val cy1 = (f.maxY - minY) / cellM
        for (cy in cy0..cy1) for (cx in cx0..cx1) action(cy * cols + cx)
    }

    private fun JSONArray.toIntArray(): IntArray = IntArray(length()) { getInt(it) }

    fun localX(p: LatLng): Double = (p.lng - originLng) * kx
    fun localY(p: LatLng): Double = (p.lat - originLat) * ky
    fun toLatLng(x: Double, y: Double): LatLng = LatLng(y / ky + originLat, x / kx + originLng)

    val routeStart: LatLng get() = toLatLng(route[0].toDouble(), route[1].toDouble())
    val routeLengthM: Double get() = routeCum[routeCum.size - 1]

    /** p가 코리도 bbox(+margin m) 안인지. 밖이면 글랜스는 계기 모드(격자)로 그린다. */
    fun contains(p: LatLng, marginM: Double): Boolean {
        val x = localX(p)
        val y = localY(p)
        return x >= minX - marginM && x <= maxX + marginM && y >= minY - marginM && y <= maxY + marginM
    }

    /** 로컬 m 사각형 [x0,x1]×[y0,y1]과 겹치는 셀의 요소 ID를 out에 중복 없이 모은다. */
    fun collectVisible(x0: Double, y0: Double, x1: Double, y1: Double, out: VisibleSet) {
        out.begin()
        if (x1 < minX || x0 > maxX || y1 < minY || y0 > maxY) return
        val cx0 = cellX(x0)
        val cx1 = cellX(x1)
        val cy0 = cellY(y0)
        val cy1 = cellY(y1)
        for (cy in cy0..cy1) for (cx in cx0..cx1) {
            val arr = cells[cy * cols + cx]
            for (id in arr) out.add(id)
        }
    }

    private fun cellX(x: Double): Int = ((x - minX) / cellM).toInt().coerceIn(0, cols - 1)
    private fun cellY(y: Double): Int = ((y - minY) / cellM).toInt().coerceIn(0, rows - 1)

    /**
     * 로컬 (x,y)에서 가장 가까운 경로 지점의 누적 거리(m). 경로 754점 전수 검사.
     * [CorridorMap]이 지난/남은 구간 분기점(splitPoint)이 바뀔 때마다 부른다. heading-up 글랜스에서는 위치 갱신마다
     * (데모 20배속이면 초당 20회, 50 ms마다). 점 754개 × 곱셈 몇 개라 메인 스레드에서 돌려도 프레임 예산에 비해 작다.
     */
    fun routeProgress(x: Double, y: Double): Double {
        var bestD2 = Double.MAX_VALUE
        var bestS = 0.0
        val n = route.size / 2
        for (i in 0 until n - 1) {
            val ax = route[2 * i].toDouble()
            val ay = route[2 * i + 1].toDouble()
            val bx = route[2 * i + 2].toDouble()
            val by = route[2 * i + 3].toDouble()
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx - x
            val py = ay + t * dy - y
            val d2 = px * px + py * py
            if (d2 < bestD2) {
                bestD2 = d2
                bestS = routeCum[i] + t * sqrt(len2)
            }
        }
        return bestS
    }

    /** 누적 거리 s(m)에 해당하는 경로 좌표를 out[0], out[1]에 쓴다(양 끝 밖은 끝점으로 고정). */
    fun routePoint(s: Double, out: DoubleArray) {
        val sc = s.coerceIn(0.0, routeLengthM)
        var lo = 0
        var hi = routeCum.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (routeCum[mid] <= sc) lo = mid else hi = mid
        }
        val segLen = routeCum[hi] - routeCum[lo]
        val t = if (segLen <= 0.0) 0.0 else (sc - routeCum[lo]) / segLen
        out[0] = route[2 * lo] + (route[2 * hi] - route[2 * lo]) * t
        out[1] = route[2 * lo + 1] + (route[2 * hi + 1] - route[2 * lo + 1]) * t
    }

    /** 누적 거리 s(m) 뒤에 오는 첫 경로 꼭짓점 인덱스(없으면 점 개수). */
    fun routeIndexAfter(s: Double): Int {
        var lo = 0
        var hi = routeCum.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (routeCum[mid] > s) hi = mid else lo = mid + 1
        }
        return lo
    }

    /**
     * 모의 미니카 1대의 자리(설계서 §4.2 레이더 유지): 경로 진행 [s](m)에서 [offsetM](앞 +, 뒤 −)만큼 떨어진 경로 위 점을 [out]에 쓴다(로컬 m).
     * 그 자리가 경로 밖(시작 전·끝 뒤)이면 false. 출발 직후 뒤 점이 시작점에 묶여 차와 겹치지 않게 그리지 않는다.
     */
    fun mockCarAt(s: Double, offsetM: Double, out: DoubleArray): Boolean {
        val t = s + offsetM
        if (t < 0.0 || t > routeLengthM) return false
        routePoint(t, out)
        return true
    }

    companion object {
        const val ATTRIBUTION = "© OpenStreetMap contributors, ODbL 1.0"
        const val ASSET_PATH = "map/corridor.json"
    }
}
