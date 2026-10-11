package app.inkproof.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.inkproof.core.model.ThemeMode

val InkBlack = Color(0xFF1C1917)
val WarmBg = Color(0xFFEFEAE2)
val WarmSurface = Color(0xFFFFFCF8)
val Proof = Color(0xFFB42318)
val ProofSoft = Color(0xFFE7B5A8)
val InkTeal = Color(0xFF1F4B57)
val Mist = Color(0xFF8A8175)
val Correct = Color(0xFF1F7A4D)
val Caution = Color(0xFFB7791F)
val DeskDark = Color(0xFF101216)
val SurfaceDark = Color(0xFF1C1F27)

private val LightColors = lightColorScheme(
    primary = Proof,
    onPrimary = Color.White,
    secondary = InkTeal,
    onSecondary = Color.White,
    background = WarmBg,
    onBackground = InkBlack,
    surface = WarmSurface,
    onSurface = InkBlack,
    surfaceVariant = Color(0xFFE7E0D6),
    onSurfaceVariant = Color(0xFF5C564E),
    error = Proof,
    outline = Color(0xFFD9D0C4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE07A72),
    onPrimary = Color(0xFF2A0C0A),
    secondary = Color(0xFF8FCBD4),
    onSecondary = Color(0xFF062126),
    background = DeskDark,
    onBackground = Color(0xFFF4F0E8),
    surface = SurfaceDark,
    onSurface = Color(0xFFF4F0E8),
    surfaceVariant = Color(0xFF2A2E38),
    onSurfaceVariant = Color(0xFFC8C0B6),
    error = Color(0xFFE07A72),
    outline = Color(0xFF3A3F4A),
)

private val InkType = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, color = InkBlack),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = Mist),
    labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun InkProofTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = InkType,
        content = content,
    )
}

fun deskColor(dark: Boolean): Int = if (dark) 0xFF101216.toInt() else 0xFFD7D0C6.toInt()
