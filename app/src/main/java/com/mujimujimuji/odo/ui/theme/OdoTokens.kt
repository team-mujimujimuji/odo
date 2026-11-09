package com.mujimujimuji.odo.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 색 토큰(설계서 §1.1). 앱 안에서 `Color(0xFF…)` 리터럴은 이 파일에만 둔다.
 * 화면·지도는 `LocalOdoColors.current`로 읽고, 주/야 전환은 루트 테마가 `LocalOdoColors provides OdoDay|OdoNight`로 내려 준다.
 */
@Immutable
data class OdoColors(
    val ground: Color,
    val surface: Color,
    val line: Color,
    val ink: Color,
    val inkDim: Color,
    val track: Color,
    val amber: Color,
    val onAmber: Color,
    val route: Color,
    val routeOutline: Color,
    val routePassed: Color,
    val mapWater: Color,
    val mapForest: Color,
    val mapUrban: Color,
    val roadCasing: Color,
    val roadCore: Color,
    val qrPlate: Color,
    val qrModule: Color,
) {
    /** 본문·숫자 글자색 = `ink` 96%(설계서 §1.1 알파 단계, 폰 목업 HI와 같음). 아이콘·도형은 `ink` 그대로, 비활성 글자는 `ink` 56%. */
    val ink96: Color get() = ink.copy(alpha = INK_TEXT_ALPHA)
}

private const val INK_TEXT_ALPHA = 0.96f

val OdoNight = OdoColors(
    ground = Color(0xFF0B0F14),
    surface = Color(0xFF121821),
    line = Color(0xFF2E3845),
    ink = Color(0xFFF4F6F8),
    inkDim = Color(0xFF8D9AAB),
    track = Color(0xFF6E7A8B),
    amber = Color(0xFFF0A83A),
    onAmber = Color(0xFF231B00),
    route = Color(0xFF3D86FF),
    routeOutline = Color(0xFF0E2E6B),
    routePassed = Color(0xFF4A5566),
    mapWater = Color(0xFF0E1826),
    mapForest = Color(0xFF0E1711),
    mapUrban = Color(0xFF141920),
    roadCasing = Color(0xFF06080B),
    roadCore = Color(0xFF4A5563),
    qrPlate = Color(0xFFD0D4DA),
    qrModule = Color(0xFF0B0F14),
)

val OdoDay = OdoNight.copy(
    ground = Color(0xFF171C23),
    surface = Color(0xFF1F262F),
    line = Color(0xFF3E4857),
    ink = Color(0xFFFFFFFF),
    inkDim = Color(0xFFA3AEBC),
    track = Color(0xFF7E8A9C),
    routePassed = Color(0xFF5C6778),
    mapWater = Color(0xFF1B2838),
    mapForest = Color(0xFF1A241C),
    mapUrban = Color(0xFF20262E),
    roadCasing = Color(0xFF0F1317),
    roadCore = Color(0xFF5A6573),
)

val LocalOdoColors = staticCompositionLocalOf { OdoNight }

/** 간격 토큰(설계서 §1.3). 4·8·12·16·24·32만 쓴다(48은 쓰는 곳이 없어 뺐다). */
object OdoSpace {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val margin = 32.dp
}

/** 모양 토큰. 엘리베이션은 1dp `line` 헤어라인 하나뿐. */
object OdoShape {
    val panel = RoundedCornerShape(16.dp)
    val panelEdgeLeft = RoundedCornerShape(0.dp, 16.dp, 16.dp, 0.dp)
    val chip = RoundedCornerShape(12.dp)
    val key: Shape = RectangleShape
    val hairline = 1.dp
}

/** 크기 토큰. */
object OdoSize {
    val touch = 76.dp
    val touchGap = 23.dp
    val icon = 32.dp
    val picto = 36.dp
    val medal = 36.dp
    val gaugeDrive = 12.dp
    val gaugeParked = 4.dp
    val heroW = 288.dp
    val heroH = 192.dp
    val navi = 96.dp
    val qr = 240.dp
}
