package de.rolfwalker.flightbuddy.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.rolfwalker.flightbuddy.core.model.ThemeMode

/** Cool aviation blue seed — not purple/pink. Dark primary on #141218-class surfaces. */
val AviationBlueLight = Color(0xFF1565C0)
val AviationBlueDark = Color(0xFF8AB4F8)
val DarkBg = Color(0xFF141218)
val DarkCard = Color(0xFF1A2030)
val MapArc = Color(0xFF3DDCFF)
val Success = Color(0xFF2E7D4F)
val Warning = Color(0xFFC77800)
val Destructive = Color(0xFFB3261E)

private val LightColors = lightColorScheme(
    primary = AviationBlueLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFFD6E3F0),
    onSecondary = Color(0xFF0D2137),
    secondaryContainer = Color(0xFFD6E3F0),
    onSecondaryContainer = Color(0xFF0D2137),
    tertiary = Color(0xFF007C91),
    onTertiary = Color.White,
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF161C24),
    surface = Color(0xFFF4F7FB),
    onSurface = Color(0xFF161C24),
    surfaceVariant = Color(0xFFDCE3EE),
    onSurfaceVariant = Color(0xFF3E4754),
    surfaceContainer = Color(0xFFE6EDF6),
    surfaceContainerHigh = Color(0xFFDCE6F2),
    surfaceContainerHighest = Color(0xFFD4DFEE),
    outline = Color(0xFF6F7A8A),
    error = Destructive,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = AviationBlueDark,
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF1A4A8A),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF2A3548),
    onSecondary = Color(0xFFD6E3F0),
    secondaryContainer = Color(0xFF2A3548),
    onSecondaryContainer = Color(0xFFD6E3F0),
    tertiary = MapArc,
    onTertiary = Color(0xFF003640),
    background = DarkBg,
    onBackground = Color(0xFFE4E8F0),
    surface = DarkBg,
    onSurface = Color(0xFFE4E8F0),
    surfaceVariant = DarkCard,
    onSurfaceVariant = Color(0xFFC5CDD8),
    surfaceContainer = DarkCard,
    surfaceContainerHigh = Color(0xFF222A3A),
    surfaceContainerHighest = Color(0xFF2C3548),
    outline = Color(0xFF8A93A3),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

private val FbTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = (-0.02).em),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, letterSpacing = (-0.02).em),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
)

private val FbShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun FlightBuddyTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = FbTypography,
        shapes = FbShapes,
        content = content,
    )
}

@Composable
fun ColorScheme.tonalCard() = surfaceContainer
