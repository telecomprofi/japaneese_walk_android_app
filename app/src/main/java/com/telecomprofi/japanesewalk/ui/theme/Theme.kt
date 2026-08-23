package com.telecomprofi.japanesewalk.ui.theme

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
import com.telecomprofi.japanesewalk.core.HrZone

/** Zone colours come from the core model so rings, chips and charts never drift. */
fun HrZone.color(): Color = Color(argb)

object JwColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF141416)
    val SurfaceHigh = Color(0xFF1E1E22)
    val OnSurface = Color(0xFFF2F2F7)
    val Muted = Color(0xFF8E8E93)
    val Fast = Color(0xFFFF9F0A)
    val Slow = Color(0xFF30D158)
    val Accent = Color(0xFF0A84FF)
    val Track = Color(0xFF2C2C2E)
    val Danger = Color(0xFFFF453A)
    val Gold = Color(0xFFFFD60A)
    /** Fill for a completed session circle. */
    val SlowSoft = Color(0xFF7BE39A)
    /** Outline for a session not done yet. */
    val Outline = Color(0xFF48484A)
}

private val DarkScheme = darkColorScheme(
    primary = JwColors.Slow,
    onPrimary = Color.Black,
    secondary = JwColors.Fast,
    onSecondary = Color.Black,
    tertiary = JwColors.Accent,
    background = JwColors.Background,
    onBackground = JwColors.OnSurface,
    surface = JwColors.Surface,
    onSurface = JwColors.OnSurface,
    surfaceVariant = JwColors.SurfaceHigh,
    onSurfaceVariant = JwColors.Muted,
    error = JwColors.Danger,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1B8E3C),
    secondary = Color(0xFFC2700A),
    tertiary = Color(0xFF0A6BD0),
)

private val JwTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 76.sp,
        letterSpacing = (-2).sp,
    ),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

@Composable
fun JapaneseWalkTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    // The walk screen is designed for a black background, like the watch HR
    // apps it takes after; light mode is available but dark is the default.
    val scheme = if (darkTheme || isSystemInDarkTheme()) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme, typography = JwTypography, content = content)
}
