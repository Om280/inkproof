package com.inkproof.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// InkProof identity: ink navy, warm paper, a restrained academic green accent.
val InkNavy = Color(0xFF1A2238)
val InkNavyLight = Color(0xFF2C3A5C)
val PaperWhite = Color(0xFFFCFBF8)
val PaperGray = Color(0xFFF1F2F6)
val ProofGreen = Color(0xFF2E7D62)
val ProofGreenSoft = Color(0xFFDCEEE7)
val ErrorRed = Color(0xFFC2504B)
val ErrorRedSoft = Color(0xFFF8E4E2)
val WarnAmber = Color(0xFFB98A2F)
val WarnAmberSoft = Color(0xFFF7EEDB)
val NeutralInkText = Color(0xFF222838)
val MutedText = Color(0xFF6B7280)
val Divider = Color(0xFFE4E7EE)

private val LightColors = lightColorScheme(
    primary = InkNavy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E8F4),
    onPrimaryContainer = InkNavy,
    secondary = ProofGreen,
    onSecondary = Color.White,
    secondaryContainer = ProofGreenSoft,
    onSecondaryContainer = Color(0xFF143A2D),
    background = PaperGray,
    onBackground = NeutralInkText,
    surface = PaperWhite,
    onSurface = NeutralInkText,
    surfaceVariant = Color(0xFFEDEFF5),
    onSurfaceVariant = MutedText,
    outline = Divider,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorRedSoft,
    onErrorContainer = Color(0xFF5E2320)
)

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

@Composable
fun InkProofTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = InkTypography,
        shapes = InkShapes,
        content = content
    )
}
