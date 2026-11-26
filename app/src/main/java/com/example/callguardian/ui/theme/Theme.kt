package com.example.callguardian.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.callguardian.R
import com.example.callguardian.model.RiskLevel

/**
 * "Terminal" theme: dark-first, near-black background, one electric-lime accent, a red threat
 * colour and JetBrains Mono (OFL, bundled in res/font) for everything, headings in heavier
 * weights. Every screen, including the schema-generated sheets and receipts, reads these tokens
 * through MaterialTheme, so generated UI matches hand-written UI.
 *
 * Contrast: text colours are ≥ 7:1 (Ink) / ≥ 4.5:1 (InkDim) on Void and on the glass surfaces.
 * Sizes are in sp, so system font scaling applies.
 */
object Brand {
    val Void = Color(0xFF07090B)        // background
    val Glass = Color(0xFF12161B)       // card fill (drawn at ~85% alpha over the grid)
    val Glass2 = Color(0xFF1A2027)
    val Line = Color(0xFF2A323C)        // hairlines
    val Ink = Color(0xFFE9F0E6)
    val InkDim = Color(0xFFA3AEA6)
    val Lime = Color(0xFFC6FF3D)        // the one accent
    val OnLime = Color(0xFF0B1100)
    val Threat = Color(0xFFFF3B4E)
    val Amber = Color(0xFFFFC23D)

    // Pipeline tiers keep their own hues, all tuned for the dark background.
    val Device = Lime
    val Cloud = Color(0xFF6FD3FF)
    val Rules = InkDim
    // Kept for existing call sites.
    val Indigo = Color(0xFF9FB3FF)
    val Teal = Lime
    val Night = Void
    val NightSurface = Glass
    val NightSurface2 = Glass2
}

fun RiskLevel.color(): Color = when (this) {
    RiskLevel.SAFE -> Brand.Lime
    RiskLevel.LOW -> Color(0xFF8FE3A0)
    RiskLevel.MEDIUM -> Brand.Amber
    RiskLevel.HIGH -> Color(0xFFFF7A3D)
    RiskLevel.CRITICAL -> Brand.Threat
}

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semi_bold, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    Font(R.font.jetbrains_mono_extra_bold, FontWeight.ExtraBold),
)

private val Scheme = darkColorScheme(
    primary = Brand.Lime, onPrimary = Brand.OnLime,
    primaryContainer = Color(0xFF2A3A07), onPrimaryContainer = Color(0xFFE4FFB0),
    secondary = Brand.Cloud, onSecondary = Color(0xFF00202C),
    secondaryContainer = Color(0xFF0E2A36), onSecondaryContainer = Color(0xFFCDEFFF),
    tertiary = Brand.Amber, onTertiary = Color(0xFF241A00),
    background = Brand.Void, onBackground = Brand.Ink,
    surface = Brand.Void, onSurface = Brand.Ink,
    surfaceVariant = Brand.Glass2, onSurfaceVariant = Brand.InkDim,
    surfaceContainerLowest = Brand.Void, surfaceContainerLow = Color(0xFF0D1014),
    surfaceContainer = Brand.Glass, surfaceContainerHigh = Brand.Glass2, surfaceContainerHighest = Color(0xFF222932),
    surfaceTint = Color.Transparent,
    inverseSurface = Brand.Ink, inverseOnSurface = Brand.Void, inversePrimary = Color(0xFF4F6B00),
    outline = Color(0xFF46505C), outlineVariant = Brand.Line,
    error = Brand.Threat, onError = Color.White, errorContainer = Color(0xFF3A0B12), onErrorContainer = Color(0xFFFFD9DC),
    scrim = Color(0xCC000000),
)

private fun TextStyle.mono(w: FontWeight, size: Int, line: Int, track: Double = 0.0) =
    copy(fontFamily = JetBrainsMono, fontWeight = w, fontSize = size.sp, lineHeight = line.sp, letterSpacing = track.sp)

private val AppTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.mono(FontWeight.ExtraBold, 48, 54, -1.5),
        displayMedium = t.displayMedium.mono(FontWeight.ExtraBold, 40, 46, -1.0),
        displaySmall = t.displaySmall.mono(FontWeight.ExtraBold, 32, 38, -0.8),
        headlineLarge = t.headlineLarge.mono(FontWeight.ExtraBold, 28, 34, -0.6),
        headlineMedium = t.headlineMedium.mono(FontWeight.Bold, 24, 30, -0.4),
        headlineSmall = t.headlineSmall.mono(FontWeight.Bold, 20, 26, -0.2),
        titleLarge = t.titleLarge.mono(FontWeight.Bold, 19, 25, -0.2),
        titleMedium = t.titleMedium.mono(FontWeight.SemiBold, 16, 22),
        titleSmall = t.titleSmall.mono(FontWeight.SemiBold, 14, 20),
        bodyLarge = t.bodyLarge.mono(FontWeight.Normal, 15, 22),
        bodyMedium = t.bodyMedium.mono(FontWeight.Normal, 14, 20),
        bodySmall = t.bodySmall.mono(FontWeight.Normal, 12, 18),
        labelLarge = t.labelLarge.mono(FontWeight.SemiBold, 14, 20, 0.2),
        labelMedium = t.labelMedium.mono(FontWeight.Medium, 12, 16, 0.4),
        labelSmall = t.labelSmall.mono(FontWeight.SemiBold, 11, 14, 0.8),
    )
}

private val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4),
    small = androidx.compose.foundation.shape.RoundedCornerShape(6),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(10),
    large = androidx.compose.foundation.shape.RoundedCornerShape(14),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(18),
)

/** Dark-first by design; `dark` is kept for previews. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun CallGuardianTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
