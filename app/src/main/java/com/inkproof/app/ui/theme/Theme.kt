package com.inkproof.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp

// ============================================================
// InkProof identity. One theme system: every screen reads these
// through MaterialTheme / the composable accessors below — no
// per-screen ad-hoc shades.
// ============================================================

// Raw light palette
private val RawInkNavy = Color(0xFF1A2238)
private val RawPaperWhite = Color(0xFFFCFBF8)
private val RawPaperGray = Color(0xFFF1F2F6)
private val RawProofGreen = Color(0xFF2E7D62)
private val RawProofGreenSoft = Color(0xFFDCEEE7)
private val RawErrorRed = Color(0xFFC2504B)
private val RawErrorRedSoft = Color(0xFFF8E4E2)
private val RawWarnAmber = Color(0xFFB98A2F)
private val RawWarnAmberSoft = Color(0xFFF7EEDB)
private val RawInkText = Color(0xFF222838)
private val RawMuted = Color(0xFF6B7280)
private val RawDivider = Color(0xFFE4E7EE)

// Raw dark palette — comfortable dark surfaces, clear text, real contrast.
private val DarkBg = Color(0xFF14161C)
private val DarkSurface = Color(0xFF1C1F27)
private val DarkSurfaceVariant = Color(0xFF272B36)
private val DarkText = Color(0xFFE6E8EF)
private val DarkMuted = Color(0xFF9AA3B5)
private val DarkDivider = Color(0xFF373D4B)
private val DarkPrimary = Color(0xFFA9BCF0) // ink navy, lifted for dark
private val DarkOnPrimary = Color(0xFF16203C)
private val DarkPrimaryContainer = Color(0xFF32405F)
private val DarkProofGreen = Color(0xFF7CC4A8)
private val DarkProofGreenSoft = Color(0xFF1E3A30)
private val DarkErrorRed = Color(0xFFE98E89)
private val DarkErrorRedSoft = Color(0xFF452725)
private val DarkWarnAmber = Color(0xFFDDB264)
private val DarkWarnAmberSoft = Color(0xFF3B321C)

private val LightColors = lightColorScheme(
    primary = RawInkNavy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E8F4),
    onPrimaryContainer = RawInkNavy,
    secondary = RawProofGreen,
    onSecondary = Color.White,
    secondaryContainer = RawProofGreenSoft,
    onSecondaryContainer = Color(0xFF143A2D),
    tertiary = RawWarnAmber,
    tertiaryContainer = RawWarnAmberSoft,
    onTertiaryContainer = Color(0xFF4A3608),
    background = RawPaperGray,
    onBackground = RawInkText,
    surface = RawPaperWhite,
    onSurface = RawInkText,
    surfaceVariant = Color(0xFFEDEFF5),
    onSurfaceVariant = RawMuted,
    outline = RawDivider,
    outlineVariant = RawDivider,
    error = RawErrorRed,
    onError = Color.White,
    errorContainer = RawErrorRedSoft,
    onErrorContainer = Color(0xFF5E2320)
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = Color(0xFFDCE3F7),
    secondary = DarkProofGreen,
    onSecondary = Color(0xFF0D2B21),
    secondaryContainer = DarkProofGreenSoft,
    onSecondaryContainer = Color(0xFFBDE7D4),
    tertiary = DarkWarnAmber,
    tertiaryContainer = DarkWarnAmberSoft,
    onTertiaryContainer = Color(0xFFF2DDB2),
    background = DarkBg,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkMuted,
    outline = DarkDivider,
    outlineVariant = DarkDivider,
    error = DarkErrorRed,
    onError = Color(0xFF3A1210),
    errorContainer = DarkErrorRedSoft,
    onErrorContainer = Color(0xFFF5CBC8)
)

// ============================================================
// Theme-aware accessors. Existing call sites keep their names and
// automatically follow light/dark — no per-screen color drift.
// ============================================================

val InkNavy: Color @Composable get() = MaterialTheme.colorScheme.primary
val MutedText: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Divider: Color @Composable get() = MaterialTheme.colorScheme.outline
val ProofGreen: Color @Composable get() = MaterialTheme.colorScheme.secondary
val ProofGreenSoft: Color @Composable get() = MaterialTheme.colorScheme.secondaryContainer
val ErrorRed: Color @Composable get() = MaterialTheme.colorScheme.error
val ErrorRedSoft: Color @Composable get() = MaterialTheme.colorScheme.errorContainer
val WarnAmber: Color @Composable get() = MaterialTheme.colorScheme.tertiary
val WarnAmberSoft: Color @Composable get() = MaterialTheme.colorScheme.tertiaryContainer

private val InkTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp
    ),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.8.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.6.sp
    )
)

private val InkShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** User theme preference values stored in settings. */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
}

@Composable
fun InkProofTheme(
    themeMode: String = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = InkTypography,
        shapes = InkShapes,
        content = content
    )
}
