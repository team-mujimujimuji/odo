package com.mujimujimuji.odo.ui.profile

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.mujimujimuji.odo.R
import com.mujimujimuji.odo.cosmetic.CosmeticCatalog
import com.mujimujimuji.odo.cosmetic.CosmeticItem
import com.mujimujimuji.odo.cosmetic.Loadout
import com.mujimujimuji.odo.cosmetic.Slot
import com.mujimujimuji.odo.cosmetic.VehicleType
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.engine.LevelTable
import com.mujimujimuji.odo.ui.components.Chip
import com.mujimujimuji.odo.ui.components.CosmeticGlyph
import com.mujimujimuji.odo.ui.components.DISABLED_ALPHA
import com.mujimujimuji.odo.ui.components.Hairline
import com.mujimujimuji.odo.ui.components.Key
import com.mujimujimuji.odo.ui.components.KeyStrip
import com.mujimujimuji.odo.ui.components.OdoDurations
import com.mujimujimuji.odo.ui.components.OdoEasing
import com.mujimujimuji.odo.ui.components.OdoPanel
import com.mujimujimuji.odo.ui.components.PRESSED_ALPHA
import com.mujimujimuji.odo.ui.components.PanelHeader
import com.mujimujimuji.odo.ui.components.SWATCH_RING_ALPHA
import com.mujimujimuji.odo.ui.components.VehicleSilhouette
import com.mujimujimuji.odo.ui.components.drawPlateGlyph
import com.mujimujimuji.odo.ui.components.drawRoofGlyph
import com.mujimujimuji.odo.ui.components.drawWheelGlyph
import com.mujimujimuji.odo.ui.components.rememberMotionScale
import com.mujimujimuji.odo.ui.components.scaledBy
import com.mujimujimuji.odo.ui.map.CorridorData
import com.mujimujimuji.odo.ui.map.ScrimmedScene
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoShape
import com.mujimujimuji.odo.ui.theme.OdoSize
import com.mujimujimuji.odo.ui.theme.OdoSpace
import com.mujimujimuji.odo.ui.theme.OdoType
import com.mujimujimuji.odo.ui.vehicle.OdoVehicle
import com.mujimujimuji.odo.ui.vehicle.VehicleView
import kotlinx.coroutines.delay

/**
 * 차고(설계서 §5.5). 패널 1장: 헤더(차고 · 수집 n/24 · 홈) → 탭 KeyStrip 7칸 → [미리보기 228×152 + 사양 줄] | [탭별 항목 열].
 * 명세의 hero 288×192와 칩 3열(3×120 + 2×23)은 592dp 패널 안쪽 560dp에 함께 들어가지 않는다(288 + 16 + 406 > 560).
 * 그래서 미리보기는 228×152로 두고 항목 열(약 316dp)의 배치를 탭마다 바꾼다: 차종은 실루엣 칩 2열, 색은 이름표가 달린 페인트 칸 3열,
 * 휠·램프·루프·번호판은 썸네일이 붙은 1열 칩, 데칼 탭은 데칼·트레일 두 묶음(색과 같은 그림 칸 3열 — 글리프 + 이름, 같은 폭).
 * 칩·칸은 모두 높이 76, 가로·세로 간격 23. 글꼴 1.3처럼 넘칠 때는 항목 열만 세로로 스크롤된다(색 3행은 1.3에서도 딱 맞음).
 * 사양 줄은 열 폭을 재서 항목 사이에서만 줄을 바꾸고, 바뀐 자리에는 "·"를 남기지 않는다. 잠긴 항목을 누르면 사양 줄 자리에
 * 힌트 1문장이 3 s 나온다(칭호는 줄바꿈 금지 공백으로 묶어 중간에서 꺾이지 않음). 선택은 repo에 바로 저장되고 미리보기는 150 ms 크로스페이드.
 */
@Composable
fun GarageScreen(
    loadout: Loadout,
    level: Int,
    data: CorridorData?,
    center: LatLng?,
    onSelect: (Slot, String) -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalOdoColors.current
    val context = LocalContext.current
    var tab by remember { mutableStateOf(Slot.VEHICLE) }
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(HINT_MS)
            hint = null
        }
    }
    val roofTab = stringResource(
        if (loadout.vehicle == VehicleType.SCOOTER) R.string.garage_tab_roof_scooter else R.string.garage_tab_roof,
    )
    val tabs = listOf(
        Slot.VEHICLE to stringResource(R.string.garage_tab_vehicle),
        Slot.COLOR to stringResource(R.string.garage_tab_color),
        Slot.WHEEL to stringResource(R.string.garage_tab_wheel),
        Slot.LAMP to stringResource(R.string.garage_tab_lamp),
        Slot.ROOF to roofTab,
        Slot.DECAL to stringResource(R.string.garage_tab_decal),
        Slot.PLATE to stringResource(R.string.garage_tab_plate),
    )
    val onLocked: (CosmeticItem) -> Unit = { item ->
        val title = LevelTable.titleForLevel(item.unlockLevel).replace(' ', NBSP)
        hint = context.getString(R.string.garage_locked_hint, item.unlockLevel, title)
    }
    Box(modifier.fillMaxSize().background(c.ground)) {
        ScrimmedScene(data, center, Modifier.fillMaxSize())
        OdoPanel(Modifier.align(Alignment.Center).size(PANEL_W, PANEL_H)) {
            Column(Modifier.fillMaxSize()) {
                PanelHeader(
                    title = stringResource(R.string.garage_title),
                    caption = stringResource(R.string.garage_collected, CosmeticCatalog.collected(level), CosmeticCatalog.collectibleTotal),
                    onHome = onHome,
                )
                Hairline()
                KeyStrip(
                    tabs.map { (slot, label) -> Key(label, selected = tab == slot, onClick = { tab = slot }) },
                    Modifier.fillMaxWidth(),
                    compact = true,
                )
                Hairline()
                Row(Modifier.fillMaxSize().padding(OdoSpace.l)) {
                    Preview(loadout, roofTab, hint, Modifier.width(PREVIEW_W).fillMaxHeight())
                    Spacer(Modifier.width(OdoSpace.l))
                    ItemPane(tab, loadout, level, onSelect, onLocked, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/** 왼쪽 열: 미니카 hero(150 ms 크로스페이드) + 사양 줄(캡션). 잠김 힌트가 있으면 3 s 동안 사양 줄 자리에 힌트를 쓴다. */
@Composable
private fun Preview(loadout: Loadout, roofTab: String, hint: String?, modifier: Modifier) {
    val c = LocalOdoColors.current
    val motion = rememberMotionScale()
    val roofNone = stringResource(R.string.garage_none_of, roofTab)
    val roofLabel = if (loadout.roofId == CosmeticCatalog.NONE) roofNone else itemLabel(CosmeticCatalog.item(Slot.ROOF, loadout.roofId), loadout.vehicle)
    val sheet = remember(loadout, roofLabel) {
        buildList {
            add(loadout.vehicle.label)
            add(CosmeticCatalog.item(Slot.COLOR, loadout.colorId).label)
            add(CosmeticCatalog.item(Slot.WHEEL, loadout.wheelId).label)
            add(CosmeticCatalog.item(Slot.LAMP, loadout.lampId).label)
            add(roofLabel)
            for (slot in listOf(Slot.DECAL, Slot.PLATE, Slot.TRAIL)) {
                val id = loadout.idOf(slot)
                if (id != CosmeticCatalog.NONE) add(CosmeticCatalog.item(slot, id).label)
            }
        }.map { it.replace(' ', NBSP) }
    }
    Column(modifier) {
        Crossfade(
            targetState = loadout,
            animationSpec = tween(OdoDurations.GARAGE_MS.scaledBy(motion), easing = OdoEasing),
            label = "garage-hero",
        ) { shown ->
            OdoVehicle(loadout = shown, view = VehicleView.Hero, modifier = Modifier.size(PREVIEW_W, PREVIEW_H))
        }
        Spacer(Modifier.height(OdoSpace.m))
        if (hint != null) {
            Text(hint, style = OdoType.caption, color = c.ink96)
        } else {
            SpecLines(sheet, c.inkDim)
        }
    }
}

/**
 * 사양 줄: 항목을 " · "로 잇되, 열 폭을 재서 항목 사이에서만 줄을 바꾸고 바뀐 자리의 "·"는 지운다
 * (줄 끝에 "·"가 매달리지 않게). 항목 안 공백은 줄바꿈 금지 공백이라 이름 중간에서 꺾이지 않는다.
 */
@Composable
private fun SpecLines(items: List<String>, color: Color) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxPx = constraints.maxWidth
        val text = remember(items, maxPx, measurer) { packLines(measurer, items, OdoType.caption, maxPx) }
        Text(text, style = OdoType.caption, color = color)
    }
}

/** [items]를 한 줄 폭 [maxPx]에 들어가는 만큼씩 [SHEET_SEPARATOR]로 잇고, 줄 사이는 "\n"(구분점 없이). */
private fun packLines(measurer: TextMeasurer, items: List<String>, style: TextStyle, maxPx: Int): String {
    val lines = mutableListOf<String>()
    var line = ""
    for (item in items) {
        val joined = if (line.isEmpty()) item else line + SHEET_SEPARATOR + item
        if (line.isEmpty() || measurer.measure(joined, style, maxLines = 1, softWrap = false).size.width <= maxPx) {
            line = joined
        } else {
            lines += line
            line = item
        }
    }
    if (line.isNotEmpty()) lines += line
    return lines.joinToString("\n")
}

/** 오른쪽 열: 탭별 배치. 탭을 바꾸면 스크롤은 맨 위로. */
@Composable
private fun ItemPane(
    tab: Slot,
    loadout: Loadout,
    level: Int,
    onSelect: (Slot, String) -> Unit,
    onLocked: (CosmeticItem) -> Unit,
    modifier: Modifier,
) {
    val c = LocalOdoColors.current
    val scroll = remember(tab) { ScrollState(0) }
    Column(modifier.verticalScroll(scroll)) {
        when (tab) {
            Slot.VEHICLE -> ChipGrid(Slot.VEHICLE, columns = 2, loadout, level, onSelect, onLocked)
            Slot.COLOR -> CellGrid(Slot.COLOR, loadout, level, onSelect, onLocked)
            Slot.DECAL -> for ((index, slot) in listOf(Slot.DECAL, Slot.TRAIL).withIndex()) {
                if (index > 0) Spacer(Modifier.height(OdoSpace.l))
                val title = if (slot == Slot.DECAL) R.string.garage_tab_decal else R.string.garage_tab_trail
                Text(stringResource(title), style = OdoType.caption, color = c.inkDim)
                Spacer(Modifier.height(OdoSpace.xs))
                CellGrid(slot, loadout, level, onSelect, onLocked)
            }
            else -> ChipGrid(tab, columns = 1, loadout, level, onSelect, onLocked)
        }
    }
}

/** 칩을 [columns]열 격자로(같은 폭). 1열이면 칩이 열 폭을 다 쓴다. */
@Composable
private fun ChipGrid(
    slot: Slot,
    columns: Int,
    loadout: Loadout,
    level: Int,
    onSelect: (Slot, String) -> Unit,
    onLocked: (CosmeticItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OdoSize.touchGap)) {
        for (row in CosmeticCatalog.bySlot(slot).chunked(columns)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(OdoSize.touchGap)) {
                for (item in row) ItemChip(slot, item, loadout, level, onSelect, onLocked, Modifier.weight(1f))
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ItemChip(
    slot: Slot,
    item: CosmeticItem,
    loadout: Loadout,
    level: Int,
    onSelect: (Slot, String) -> Unit,
    onLocked: (CosmeticItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalOdoColors.current
    val locked = item.unlockLevel > level
    val selected = item.id == loadout.idOf(slot)
    val tint = if (locked) c.ink.copy(alpha = DISABLED_ALPHA) else c.ink
    val gold = if (locked) c.amber.copy(alpha = DISABLED_ALPHA) else c.amber
    val label = itemLabel(item, loadout.vehicle)
    val glyph: (@Composable () -> Unit)? = when (slot) {
        Slot.VEHICLE -> ({ VehicleSilhouette(VehicleType.byId(item.id), tint = tint) })
        Slot.WHEEL -> ({ Canvas(Modifier.size(OdoSize.icon)) { drawWheelGlyph(item.id, tint) } })
        Slot.ROOF -> ({ VehicleGlyph { drawRoofGlyph(loadout.vehicle, item.id, tint) } })
        Slot.PLATE -> ({ Canvas(Modifier.size(OdoSize.icon)) { drawPlateGlyph(item.id, tint, gold) } })
        else -> null
    }
    Chip(
        label = label,
        selected = selected,
        locked = locked,
        onClick = { if (locked) onLocked(item) else onSelect(slot, item.id) },
        modifier = modifier,
        swatch = if (slot == Slot.LAMP) item.argb?.let { Color(it) } else null,
        lockedCaption = stringResource(R.string.level_short, item.unlockLevel),
        contentDescription = itemDescription(label, item.unlockLevel, selected, locked),
        leading = glyph,
    )
}

@Composable
private fun VehicleGlyph(draw: DrawScope.() -> Unit) {
    Canvas(Modifier.size(SILHOUETTE), onDraw = draw)
}

/**
 * 색·데칼·트레일 탭: 그림 칸 3열(같은 폭, 가로·세로 간격 23 — 설계서 §13 #11). 칸 = 28dp 그림 + 이름 + 잠기면 "Lv N"
 * (두 줄은 [OdoType.captionTight] — KeyStrip 작은 라벨과 같은 토큰), 높이 76. 1.0에서 3행 = 3×76 + 2×23 = 274dp로
 * 항목 열(298dp)에 들어가고, 1.3에서는 줄 높이가 28dp가 되어 칸 84dp, 3행 298dp로 꼭 맞는다(에뮬레이터에서 스크롤 없음 확인).
 * 그보다 큰 글꼴에서만 항목 열이 스크롤된다.
 * 색은 페인트 점 + 1dp `ink` 38% 안쪽 링(램프 칩 견본과 같은 규칙, 설계서 §1.1), 데칼·트레일은 테두리 없이 글리프만("없음"은 1dp 사선).
 * 선택만 2dp `amber` 테두리(점은 원, 글리프는 둥근 사각)로 두르고, 잠긴 칸은 그림을 흐린다(링은 그대로라 흐린 견본도 윤곽이 보인다).
 * 긴 이름("그래파이트")은 칸 사이 간격까지 넘쳐 그려도 칸 격자는 그대로다.
 */
@Composable
private fun CellGrid(slot: Slot, loadout: Loadout, level: Int, onSelect: (Slot, String) -> Unit, onLocked: (CosmeticItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(OdoSize.touchGap)) {
        for (row in CosmeticCatalog.bySlot(slot).chunked(CELL_COLUMNS)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(OdoSize.touchGap)) {
                for (item in row) {
                    val locked = item.unlockLevel > level
                    PictoCell(
                        item = item,
                        vehicle = loadout.vehicle,
                        selected = item.id == loadout.idOf(slot),
                        locked = locked,
                        onClick = { if (locked) onLocked(item) else onSelect(slot, item.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(CELL_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PictoCell(
    item: CosmeticItem,
    vehicle: VehicleType,
    selected: Boolean,
    locked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val c = LocalOdoColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val description = itemDescription(item.label, item.unlockLevel, selected, locked)
    Column(
        modifier
            .heightIn(min = OdoSize.touch)
            .selectable(selected, interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            // 칸을 clip하지 않는다: clip하면 칸보다 긴 이름("그래파이트" @1.0, "지난 길 리본" @1.3)이 칸 가장자리에서 잘린다.
            // 눌림 채움만 둥근 모양으로 그린다.
            .background(if (pressed) c.ink.copy(alpha = PRESSED_ALPHA) else Color.Transparent, OdoShape.chip)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (item.slot == Slot.COLOR) {
            val paint = (item.argb?.let { Color(it) } ?: c.inkDim).copy(alpha = if (locked) LOCKED_PAINT_ALPHA else 1f)
            val swatchRing = c.ink.copy(alpha = SWATCH_RING_ALPHA)
            val amber = c.amber
            Canvas(Modifier.size(PICTO_BOX)) {
                val dot = DOT_RADIUS.toPx()
                val hair = OdoShape.hairline.toPx()
                drawCircle(paint, radius = dot)
                drawCircle(swatchRing, radius = dot - hair / 2f, style = Stroke(hair))
                if (selected) {
                    val sel = SELECTED_RING.toPx()
                    drawCircle(amber, radius = size.minDimension / 2f - sel / 2f, style = Stroke(sel))
                }
            }
        } else {
            val tile = if (selected) Modifier.border(SELECTED_RING, c.amber, TILE_SHAPE) else Modifier
            Box(Modifier.size(PICTO_BOX).then(tile), contentAlignment = Alignment.Center) {
                if (item.id != CosmeticCatalog.NONE) {
                    CosmeticGlyph(item, vehicle, Modifier.alpha(if (locked) DISABLED_ALPHA else 1f), size = TILE_GLYPH)
                } else {
                    NoneGlyph(c.inkDim)
                }
            }
        }
        Text(
            item.label,
            Modifier.wrapContentWidth(unbounded = true),
            style = CellText,
            color = if (locked) c.ink.copy(alpha = DISABLED_ALPHA) else c.ink96,
            maxLines = 1,
            softWrap = false,
        )
        // 열린 칸도 빈 줄을 두어 한 행의 칸 높이·그림 위치를 맞춘다.
        Text(
            if (locked) stringResource(R.string.level_short, item.unlockLevel) else "",
            style = CellText,
            color = c.inkDim,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** "없음" 칸: 글리프 자리(20dp)에 왼쪽 아래→오른쪽 위 1dp 사선 하나. 빈 칸·빈 선택 테두리로 보이지 않게. */
@Composable
private fun NoneGlyph(color: Color) {
    Canvas(Modifier.size(TILE_GLYPH)) {
        val hair = OdoShape.hairline.toPx()
        drawLine(color, Offset(hair, size.height - hair), Offset(size.width - hair, hair), strokeWidth = hair)
    }
}

@Composable
private fun itemDescription(label: String, unlockLevel: Int, selected: Boolean, locked: Boolean): String = when {
    locked -> stringResource(R.string.garage_chip_locked_desc, label, unlockLevel)
    selected -> stringResource(R.string.garage_chip_selected_desc, label)
    else -> label
}

/** 칩·사양 줄의 항목 이름. 2륜은 루프 자리가 레그실드·탑케이스라 같은 항목을 다른 이름으로 부른다(설계서 §4 레이어 표: roof_twotone→레그실드, roof_box→탑케이스). */
@Composable
private fun itemLabel(item: CosmeticItem, vehicle: VehicleType): String = when {
    vehicle != VehicleType.SCOOTER || item.slot != Slot.ROOF -> item.label
    item.id == "twotone" -> stringResource(R.string.garage_roof_twotone_scooter)
    item.id == "box" -> stringResource(R.string.garage_roof_box_scooter)
    else -> item.label
}

private val PANEL_W = 592.dp
private val PANEL_H = 484.dp
private val PREVIEW_W = 228.dp
private val PREVIEW_H = 152.dp
private val SILHOUETTE = 48.dp
// 그림 칸: 28dp 상자(페인트 점 24dp, 또는 20dp 글리프 — 선택이면 바깥 2dp 테두리) + 줄 높이 24 두 줄 = 76.
private val PICTO_BOX = 28.dp
private val DOT_RADIUS = 12.dp
private val TILE_GLYPH = 20.dp
private val TILE_SHAPE = RoundedCornerShape(6.dp)
private val SELECTED_RING = 2.dp
private val CellText: TextStyle = OdoType.captionTight
private const val CELL_COLUMNS = 3
private const val LOCKED_PAINT_ALPHA = 0.38f
private const val HINT_MS = 3_000L
private const val NBSP = '\u00A0'
private const val SHEET_SEPARATOR = "\u00A0· "
