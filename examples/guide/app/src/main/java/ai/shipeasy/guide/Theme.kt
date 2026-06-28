package ai.shipeasy.guide

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Shipeasy dark brand palette. These mirror the dashboard surfaces so the
 * sample feels on-brand. The values are referenced directly from the UI code
 * (chips, code blocks) as well as wired into the Material color scheme.
 */
object SeColors {
    val Window = Color(0xFF0A0A0B)   // window / screen background
    val Card = Color(0xFF141416)     // entity card surface
    val Code = Color(0xFF0F0F10)     // nested / code-block surface
    val Hairline = Color(0xFF1F1F22) // borders (~ white @ 8%)

    val TextPrimary = Color(0xFFF5F5F4)
    val TextMuted = Color(0xFFB9B9B6)
    val TextFaint = Color(0xFF7C7C79)

    // Per-entity accents.
    val Green = Color(0xFF34D399)
    val Blue = Color(0xFF60A5FA)
    val Purple = Color(0xFFC084FC)
    val Red = Color(0xFFF87171)
    val Cyan = Color(0xFF22D3EE)
    val Amber = Color(0xFFFBBF24)
}

/** Single monospace family used for keys, values, and code blocks. */
val SeMono: FontFamily = FontFamily.Monospace

private val SeDarkColors = darkColorScheme(
    primary = SeColors.Green,
    onPrimary = SeColors.Window,
    background = SeColors.Window,
    onBackground = SeColors.TextPrimary,
    surface = SeColors.Card,
    onSurface = SeColors.TextPrimary,
    surfaceVariant = SeColors.Code,
    onSurfaceVariant = SeColors.TextMuted,
    outline = SeColors.Hairline,
)

private val SeTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        color = SeColors.TextPrimary,
    ),
    titleMedium = TextStyle(
        fontFamily = SeMono,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        color = SeColors.TextPrimary,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
        color = SeColors.TextMuted,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = SeColors.TextFaint,
    ),
    labelSmall = TextStyle(
        fontFamily = SeMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

@Composable
fun ShipeasyGuideTheme(content: @Composable () -> Unit) {
    // Sample is brand-dark regardless of the system setting; isSystemInDarkTheme
    // is referenced only to keep the call idiomatic / future-proof.
    @Suppress("UNUSED_VARIABLE")
    val systemDark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = SeDarkColors,
        typography = SeTypography,
        content = content,
    )
}
