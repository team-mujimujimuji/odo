package com.mujimujimuji.odo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mujimujimuji.odo.cosmetic.CosmeticItem
import com.mujimujimuji.odo.cosmetic.Slot
import com.mujimujimuji.odo.cosmetic.VehicleType
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoShape
import com.mujimujimuji.odo.ui.theme.OdoSize
import kotlin.math.cos
import kotlin.math.sin

/** 앱 안의 유일한 아이콘 세트(설계서 §1.3): 2dp 스트로크 벡터, 32dp. 이모지·유니코드 글리프 0. */
enum class OdoIcon { Home, Medal, Garage, Phone, Play, Reset, Check, Unlink, Close }

/** 아이콘 1개. 장식이라 의미 트리에 올리지 않는다(라벨이 뜻을 전한다). */
@Composable
fun OdoIconGlyph(
    icon: OdoIcon,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = OdoSize.icon,
) {
    Canvas(modifier.size(size)) { drawOdoIcon(icon, tint) }
}

/** 차종 실루엣(차고 차종 칩, 48dp). 옆모습 윤곽 + 바퀴 2개, 2dp 스트로크. */
@Composable
fun VehicleSilhouette(type: VehicleType, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 48.dp) {
    Canvas(modifier.size(size)) { drawVehicleSilhouette(type, tint) }
}

fun DrawScope.drawOdoIcon(icon: OdoIcon, tint: Color) {
    val u = size.minDimension / 24f
    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)

    // 24 단위 꺾은선(x, y 쌍). closed면 처음 점으로 닫는다.
    fun polyline(vararg xy: Float, closed: Boolean = false): Path {
        val path = Path()
        path.moveTo(xy[0] * u, xy[1] * u)
        var i = 2
        while (i < xy.size) {
            path.lineTo(xy[i] * u, xy[i + 1] * u)
            i += 2
        }
        if (closed) path.close()
        return path
    }
    when (icon) {
        OdoIcon.Home -> {
            val path = polyline(4f, 11f, 12f, 4f, 20f, 11f, 20f, 20f, 14f, 20f, 14f, 14f, 10f, 14f, 10f, 20f, 4f, 20f, closed = true)
            drawPath(path, tint, style = stroke)
        }
        OdoIcon.Medal -> {
            drawCircle(tint, radius = 5.5f * u, center = p(12f, 9f), style = stroke)
            line(9.5f, 14f, 7.5f, 21f)
            line(14.5f, 14f, 16.5f, 21f)
        }
        OdoIcon.Garage -> {
            drawPath(polyline(3f, 20f, 3f, 9f, 12f, 4f, 21f, 9f, 21f, 20f), tint, style = stroke)
            drawPath(polyline(7f, 20f, 7f, 13f, 17f, 13f, 17f, 20f), tint, style = stroke)
            line(7f, 16.5f, 17f, 16.5f)
        }
        OdoIcon.Phone -> {
            drawRoundRect(
                tint,
                topLeft = p(7f, 2f),
                size = Size(10f * u, 20f * u),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u),
                style = stroke,
            )
            line(10.5f, 18.5f, 13.5f, 18.5f)
        }
        OdoIcon.Play -> {
            drawPath(polyline(8f, 5f, 19f, 12f, 8f, 19f, closed = true), tint, style = stroke)
        }
        OdoIcon.Reset -> {
            val r = 7f * u
            val c = p(12f, 12.5f)
            val startDeg = -45f
            val sweep = 300f
            drawArc(
                tint,
                startAngle = startDeg,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(2 * r, 2 * r),
                style = stroke,
            )
            val endRad = Math.toRadians((startDeg + sweep).toDouble())
            val end = Offset(c.x + r * cos(endRad).toFloat(), c.y + r * sin(endRad).toFloat())
            // 시계방향 끝점의 접선 방향으로 화살촉 2획.
            val tx = -sin(endRad).toFloat()
            val ty = cos(endRad).toFloat()
            val head = 3.5f * u
            fun wing(angleDeg: Float): Offset {
                val a = Math.toRadians(angleDeg.toDouble())
                val wx = (tx * cos(a) - ty * sin(a)).toFloat()
                val wy = (tx * sin(a) + ty * cos(a)).toFloat()
                return Offset(end.x - wx * head, end.y - wy * head)
            }
            drawLine(tint, end, wing(35f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(tint, end, wing(-35f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
        OdoIcon.Check -> {
            drawPath(polyline(5f, 12.5f, 10f, 17.5f, 19f, 7.5f), tint, style = stroke)
        }
        OdoIcon.Unlink -> {
            val left = polyline(10f, 7f, 8f, 7f)
            left.arcTo(Rect(3f * u, 7f * u, 13f * u, 17f * u), -90f, -180f, false)
            left.lineTo(10f * u, 17f * u)
            val right = polyline(14f, 7f, 16f, 7f)
            right.arcTo(Rect(11f * u, 7f * u, 21f * u, 17f * u), -90f, 180f, false)
            right.lineTo(14f * u, 17f * u)
            drawPath(left, tint, style = stroke)
            drawPath(right, tint, style = stroke)
            line(9f, 20f, 15f, 4f)
        }
        // 닫기(취소): 체크와 같은 굵기의 X. 끊기 확인 행에서 [끊기]와 라벨 줄을 맞추려고 둔다.
        OdoIcon.Close -> {
            line(6f, 6f, 18f, 18f)
            line(18f, 6f, 6f, 18f)
        }
    }
}

fun DrawScope.drawVehicleSilhouette(type: VehicleType, tint: Color) {
    val u = size.minDimension / 48f
    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun wheel(x: Float, y: Float, r: Float) = drawCircle(tint, radius = r * u, center = Offset(x * u, y * u), style = stroke)
    fun poly(vararg xy: Float, closed: Boolean = true) {
        val path = Path()
        path.moveTo(xy[0] * u, xy[1] * u)
        var i = 2
        while (i < xy.size) {
            path.lineTo(xy[i] * u, xy[i + 1] * u)
            i += 2
        }
        if (closed) path.close()
        drawPath(path, tint, style = stroke)
    }
    // 비율을 과장해 라벨 없이도 갈린다: 소형 = 짧은 해치, 중형 = 높은 지붕·각진 뒤, 대형 = 상자, 세단 = 트렁크 단차.
    when (type) {
        VehicleType.COMPACT -> {
            poly(9f, 30f, 10f, 23f, 16f, 21f, 21f, 13f, 33f, 13f, 38f, 18f, 39f, 30f)
            wheel(16f, 31f, 4f)
            wheel(33f, 31f, 4f)
        }
        VehicleType.SUV -> {
            poly(5f, 31f, 5f, 21f, 11f, 19f, 16f, 9f, 40f, 9f, 43f, 12f, 43f, 31f)
            wheel(14f, 32f, 5f)
            wheel(35f, 32f, 5f)
        }
        VehicleType.LARGE -> {
            poly(4f, 31f, 4f, 13f, 14f, 10f, 44f, 10f, 44f, 31f)
            wheel(13f, 32f, 4.5f)
            wheel(36f, 32f, 4.5f)
        }
        VehicleType.SEDAN -> {
            poly(3f, 30f, 4f, 24f, 14f, 22f, 19f, 15f, 29f, 15f, 35f, 22f, 45f, 23f, 45f, 30f)
            wheel(12f, 31f, 4f)
            wheel(37f, 31f, 4f)
        }
        VehicleType.SCOOTER -> {
            wheel(11f, 33f, 5f)
            wheel(37f, 33f, 5f)
            poly(12f, 30f, 26f, 30f, 33f, 13f, closed = false)
            poly(29f, 13f, 38f, 13f, closed = false)
            poly(14f, 22f, 26f, 20f, closed = false)
            poly(14f, 22f, 12f, 30f, closed = false)
        }
    }
}

/**
 * 차고 휠 글리프(설계서 §1.3 2dp 스트로크). 상자 가운데 타이어 원 + 휠 디자인.
 * 스틸 = 림 원 + 허브 점, 다이아 = 스포크 5개, 에어로 = 휘어진 날개 3개.
 */
fun DrawScope.drawWheelGlyph(wheelId: String, tint: Color) {
    val r = size.minDimension / 2f - 1.dp.toPx()
    val c = center
    val sw = 2.dp.toPx()
    val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawCircle(tint, radius = r, center = c, style = stroke)
    fun at(radius: Float, deg: Float): Offset {
        val a = Math.toRadians(deg.toDouble())
        return Offset(c.x + radius * cos(a).toFloat(), c.y + radius * sin(a).toFloat())
    }
    when (wheelId) {
        "dia" -> {
            drawCircle(tint, radius = r * 0.22f, center = c, style = stroke)
            for (k in 0 until 5) {
                val deg = -90f + k * 72f
                drawLine(tint, at(r * 0.22f, deg), at(r * 0.78f, deg), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
        "aero" -> {
            drawCircle(tint, radius = r * 0.62f, center = c, style = stroke)
            for (k in 0 until 3) {
                val deg = -90f + k * 120f
                val path = Path()
                val from = at(r * 0.12f, deg)
                val via = at(r * 0.5f, deg + 10f)
                val to = at(r * 0.62f, deg + 55f)
                path.moveTo(from.x, from.y)
                path.quadraticTo(via.x, via.y, to.x, to.y)
                drawPath(path, tint, style = stroke)
            }
        }
        else -> {
            drawCircle(tint, radius = r * 0.6f, center = c, style = stroke)
            drawCircle(tint, radius = r * 0.14f, center = c)
        }
    }
}

/**
 * 차고 루프 글리프: 지금 차종의 실루엣([drawVehicleSilhouette], 48 단위 격자) 위에 루프 자리를 표시한다.
 * 4륜: 투톤 = 지붕 띠 채움, 루프박스 = 지붕 위 상자. 2륜: 투톤 = 레그실드 판 채움, 탑케이스 = 뒷자리 위 상자.
 */
fun DrawScope.drawRoofGlyph(type: VehicleType, roofId: String, tint: Color) {
    drawVehicleSilhouette(type, tint)
    val u = size.minDimension / 48f
    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun box(l: Float, t: Float, r: Float, b: Float, fill: Boolean) {
        val rr = CornerRadius(1.5f * u)
        if (fill) {
            drawRoundRect(tint, Offset(l * u, t * u), Size((r - l) * u, (b - t) * u), rr)
        } else {
            drawRoundRect(tint, Offset(l * u, t * u), Size((r - l) * u, (b - t) * u), rr, style = stroke)
        }
    }
    if (type == VehicleType.SCOOTER) {
        when (roofId) {
            "twotone" -> {
                val shield = Path()
                shield.moveTo(26f * u, 30f * u)
                shield.lineTo(33f * u, 13f * u)
                shield.lineTo(35.5f * u, 14.5f * u)
                shield.lineTo(29.5f * u, 30f * u)
                shield.close()
                drawPath(shield, tint)
            }
            "box" -> box(9f, 12f, 18f, 19f, fill = false)
        }
        return
    }
    // 지붕 선(48 단위): 왼쪽 x, 오른쪽 x, 높이 y. drawVehicleSilhouette의 다각형과 같은 값.
    val (left, right, top) = when (type) {
        VehicleType.COMPACT -> Triple(21f, 33f, 13f)
        VehicleType.SUV -> Triple(16f, 40f, 9f)
        VehicleType.LARGE -> Triple(14f, 44f, 10f)
        else -> Triple(19f, 29f, 15f)
    }
    when (roofId) {
        "twotone" -> box(left + 1f, top, right - 1f, top + 3.5f, fill = true)
        "box" -> box(left + 2f, top - 5.5f, right - 2f, top - 2f, fill = false)
    }
}

/** 번호판 글리프: 가로 판 윤곽(2dp) + 글자 자리 막대 2개. 황금 번호판은 판을 금색으로 채운다. */
fun DrawScope.drawPlateGlyph(plateId: String, tint: Color, gold: Color) {
    val sw = 2.dp.toPx()
    val w = size.width - sw
    val h = size.height * 0.5f
    val topLeft = Offset(sw / 2f, (size.height - h) / 2f)
    val radius = CornerRadius(3.dp.toPx())
    if (plateId == "gold") drawRoundRect(gold, topLeft, Size(w, h), radius)
    drawRoundRect(tint, topLeft, Size(w, h), radius, style = Stroke(sw))
    val y = size.height / 2f
    drawLine(tint, Offset(w * 0.28f, y), Offset(w * 0.46f, y), sw, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.58f, y), Offset(w * 0.76f, y), sw, StrokeCap.Round)
}

/**
 * 꾸미기 항목 하나를 사물 하나로(P8 "성장은 사물로 말한다", 요약 레벨업 카드). 색 = 페인트 점, 램프 = 빛 점 + 빛살,
 * 휠·루프·번호판 = 차고와 같은 글리프, 데칼 = 줄무늬 판, 트레일 = 물결 띠. 장식이라 의미 트리에 올리지 않는다(부르는 쪽이 이름을 전한다).
 */
@Composable
fun CosmeticGlyph(item: CosmeticItem, vehicle: VehicleType, modifier: Modifier = Modifier, size: Dp = OdoSize.icon) {
    val c = LocalOdoColors.current
    // 색 점 링은 Chip · 차고 색 칸과 같은 ink 38%(설계서 §1.1). line이면 그래파이트 점이 카드 면에 묻혔다.
    val ring = c.ink.copy(alpha = SWATCH_RING_ALPHA)
    Canvas(modifier.size(size)) { drawCosmeticGlyph(item, vehicle, ink = c.ink, gold = c.amber, ring = ring) }
}

private fun DrawScope.drawCosmeticGlyph(item: CosmeticItem, vehicle: VehicleType, ink: Color, gold: Color, ring: Color) {
    val sw = 2.dp.toPx()
    val hair = OdoShape.hairline.toPx()
    val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val paint = item.argb?.let { Color(it) } ?: ink
    val w = size.width
    val h = size.height
    when (item.slot) {
        Slot.COLOR -> {
            val r = size.minDimension / 2f - hair
            drawCircle(paint, radius = r)
            drawCircle(ring, radius = r - hair / 2f, style = Stroke(hair)) // 안쪽 링(차고 색 칸과 같은 자리)
        }
        Slot.LAMP -> {
            // 램프 색 원판 + ink 외곽선 + 부채꼴 빛줄기 3개. 램프 색(화이트·웜·아이스)은 모두 밝아서 hairline 링만으로는
            // 밝은 면에서 옅게 묻혔다(레벨업 카드) → 외곽선을 ink로, 원판은 상자 높이의 절반 남짓으로 키운다.
            val r = size.minDimension * 0.26f
            val dot = Offset(r + sw / 2f, h / 2f)
            drawCircle(paint, radius = r, center = dot)
            drawCircle(ink, radius = r, center = dot, style = Stroke(sw * 0.75f))
            val x0 = dot.x + r + sw * 1.5f
            for (k in -1..1) {
                drawLine(ink, Offset(x0, h / 2f + k * h * 0.12f), Offset(w - sw / 2f, h / 2f + k * h * 0.32f), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
        Slot.WHEEL -> drawWheelGlyph(item.id, ink)
        Slot.ROOF -> drawRoofGlyph(vehicle, item.id, ink)
        Slot.PLATE -> drawPlateGlyph(item.id, ink, gold)
        Slot.DECAL -> {
            val top = h * 0.25f
            val bottom = h * 0.75f
            drawRoundRect(ink, Offset(sw / 2f, top), Size(w - sw, bottom - top), CornerRadius(3.dp.toPx()), style = Stroke(sw))
            for (x in listOf(0.38f, 0.58f)) {
                drawLine(ink, Offset(w * x, bottom), Offset(w * (x + 0.16f), top), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
        Slot.TRAIL -> {
            val path = Path()
            path.moveTo(sw, h * 0.62f)
            path.cubicTo(w * 0.3f, h * 0.2f, w * 0.45f, h * 0.2f, w * 0.55f, h * 0.5f)
            path.cubicTo(w * 0.65f, h * 0.8f, w * 0.8f, h * 0.8f, w - sw, h * 0.38f)
            drawPath(path, ink, style = stroke)
        }
        Slot.VEHICLE -> drawVehicleSilhouette(VehicleType.byId(item.id), ink)
    }
}
