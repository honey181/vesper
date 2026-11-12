package com.vesper.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ObsidianBg = Color(0xFF0A0C10)
val ObsidianSurface = Color(0xFF12161F)
val ObsidianSurfaceVariant = Color(0xFF1B2230)
val ObsidianOutline = Color(0xFF2B3548)
val VesperAmber = Color(0xFFD9A760)
val VesperSlateBlue = Color(0xFF84A9D6)
val VesperTextPrimary = Color(0xFFECEFF4)
val VesperTextSecondary = Color(0xFFA0AAB8)
val VesperError = Color(0xFFE57373)

private val VesperColorScheme = darkColorScheme(
    primary = VesperAmber,
    onPrimary = Color(0xFF1A1205),
    primaryContainer = Color(0xFF3A2C16),
    onPrimaryContainer = Color(0xFFF7DFB8),
    secondary = VesperSlateBlue,
    onSecondary = Color(0xFF0A1624),
    secondaryContainer = Color(0xFF1F2F46),
    onSecondaryContainer = Color(0xFFD1E3F8),
    background = ObsidianBg,
    onBackground = VesperTextPrimary,
    surface = ObsidianSurface,
    onSurface = VesperTextPrimary,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = VesperTextSecondary,
    outline = ObsidianOutline,
    error = VesperError,
    onError = Color(0xFF2B0808)
)

private val VesperTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp
    )
)

@Composable
fun VesperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VesperColorScheme,
        typography = VesperTypography,
        content = content
    )
}
