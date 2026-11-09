package com.mujimujimuji.odo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.mujimujimuji.odo.ui.vehicle.LocalOdoNight

/**
 * 루트 테마(설계서 §1.5·§1.6). [night]에 따라 `LocalOdoColors`·`LocalOdoNight`·M3 colorScheme을 함께 바꾼다.
 * M3 역할은 전부 정의해 기본 보라/분홍이 새어 나오지 않게 한다. 기본 글자 역할은 `OdoType.body`.
 */
@Composable
fun OdoTheme(night: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (night) OdoNight else OdoDay
    val scheme = remember(colors) { odoColorScheme(colors) }
    CompositionLocalProvider(LocalOdoColors provides colors, LocalOdoNight provides night) {
        MaterialTheme(colorScheme = scheme, typography = odoTypography) {
            CompositionLocalProvider(LocalContentColor provides colors.ink96) { // 1차 글자 = ink 96%(설계서 §1.1)
                ProvideTextStyle(OdoType.body, content)
            }
        }
    }
}

private fun odoColorScheme(c: OdoColors): ColorScheme = darkColorScheme(
    primary = c.amber,
    onPrimary = c.onAmber,
    primaryContainer = c.amber,
    onPrimaryContainer = c.onAmber,
    inversePrimary = c.amber,
    secondary = c.surface,
    onSecondary = c.ink,
    secondaryContainer = c.surface,
    onSecondaryContainer = c.ink,
    // tertiary = route: 지도 밖에서는 쓰지 않는다(파란 버튼 금지, §1.4).
    tertiary = c.route,
    onTertiary = c.ink,
    tertiaryContainer = c.route,
    onTertiaryContainer = c.ink,
    background = c.ground,
    onBackground = c.ink,
    surface = c.ground,
    onSurface = c.ink,
    surfaceVariant = c.surface,
    onSurfaceVariant = c.inkDim,
    surfaceTint = Color.Transparent,
    inverseSurface = c.ink,
    inverseOnSurface = c.ground,
    error = c.inkDim,
    onError = c.ground,
    errorContainer = c.surface,
    onErrorContainer = c.ink,
    outline = c.line,
    outlineVariant = c.line,
    scrim = Color.Black,
    surfaceBright = c.ground,
    surfaceDim = c.ground,
    surfaceContainer = c.ground,
    surfaceContainerHigh = c.surface,
    surfaceContainerHighest = c.surface,
    surfaceContainerLow = c.ground,
    surfaceContainerLowest = c.ground,
)

private val odoTypography = Typography(
    displayLarge = OdoType.numeralL,
    displayMedium = OdoType.numeralM,
    displaySmall = OdoType.numeralM,
    headlineLarge = OdoType.headline,
    headlineMedium = OdoType.headline,
    headlineSmall = OdoType.title,
    titleLarge = OdoType.title,
    titleMedium = OdoType.title,
    titleSmall = OdoType.body,
    bodyLarge = OdoType.body,
    bodyMedium = OdoType.body,
    bodySmall = OdoType.caption,
    labelLarge = OdoType.label,
    labelMedium = OdoType.caption,
    labelSmall = OdoType.caption,
)
