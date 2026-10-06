package cn.gxnu.campus.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The single source of colour truth. Components read these fields instead of raw hex, so both
 * themes stay consistent and a palette change lands everywhere at once.
 *
 * Ladders follow the App Mode language: a raised surface ladder (canvas -> chrome -> card),
 * a three-step text ladder, exactly one brand accent per theme, and status colours reserved
 * for state rather than decoration.
 */
@Immutable
data class CampusPalette(
    val isDark: Boolean,
    val canvas: Color,
    val chrome: Color,
    val surface: Color,
    val muted: Color,
    val hover: Color,
    val selected: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentWash: Color,
    val onAccentWash: Color,
    val accentBorder: Color,
    val success: Color,
    val successWash: Color,
    val warning: Color,
    val warningWash: Color,
    val danger: Color,
    val dangerWash: Color,
    val cardShadow: Dp
)

/** Soft-gray canvas, white raised cards. */
internal val LightPalette = CampusPalette(
    isDark = false,
    canvas = Color(0xFFF4F5F7),
    chrome = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    muted = Color(0xFFF7F8FA),
    hover = Color(0x0A000000),
    selected = Color(0xFFE6ECF4),
    border = Color(0xFFE6E8EC),
    borderStrong = Color(0xFFD3D8E0),
    textPrimary = Color(0xFF1C1C1E),
    textSecondary = Color(0xFF606974),
    textTertiary = Color(0xFF8A9099),
    accent = Color(0xFF325FA4),
    onAccent = Color(0xFFFFFFFF),
    accentWash = Color(0xFFE6ECF4),
    onAccentWash = Color(0xFF2A5490),
    accentBorder = Color(0xFFB9CBE4),
    success = Color(0xFF256B47),
    successWash = Color(0xFFE4F1E9),
    warning = Color(0xFF8A5A12),
    warningWash = Color(0xFFFAEFD8),
    danger = Color(0xFFB3261E),
    dangerWash = Color(0xFFFBEAE8),
    cardShadow = 1.dp
)

/**
 * Soft charcoal canvas with elevated charcoal cards. Never a pure-black page, and the card
 * surface stays lighter than the canvas so raised surfaces still read as raised. The accent is
 * the brighter twin of the same hue, carrying dark ink so the single accent can serve both as a
 * filled button and as small coloured text on dark.
 */
internal val DarkPalette = CampusPalette(
    isDark = true,
    canvas = Color(0xFF1B1E24),
    chrome = Color(0xFF15171C),
    surface = Color(0xFF262A31),
    muted = Color(0xFF2F343C),
    hover = Color(0x14FFFFFF),
    selected = Color(0xFF324157),
    border = Color(0xFF333841),
    borderStrong = Color(0xFF454B56),
    textPrimary = Color(0xFFF2F4F7),
    textSecondary = Color(0xFFA2A9B4),
    textTertiary = Color(0xFF767D89),
    accent = Color(0xFF5B93DA),
    onAccent = Color(0xFF10233A),
    accentWash = Color(0xFF26364B),
    onAccentWash = Color(0xFF8FB8E8),
    accentBorder = Color(0xFF3A5580),
    success = Color(0xFF66D19A),
    successWash = Color(0xFF1E3A2C),
    warning = Color(0xFFF0C24E),
    warningWash = Color(0xFF3A2F16),
    danger = Color(0xFFFF8A80),
    dangerWash = Color(0xFF452523),
    cardShadow = 0.dp
)

/** Radius ladder, shared by both themes. */
object CampusRadius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp

    val smShape = RoundedCornerShape(sm)
    val mdShape = RoundedCornerShape(md)
    val lgShape = RoundedCornerShape(lg)
    val pillShape = RoundedCornerShape(999.dp)
}

/** 4dp grid. */
object CampusSpace {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
}

/** Motion is limited to colour and background changes, 150-200ms. */
object CampusMotion {
    const val DURATION_MS = 180
}

val LocalCampusPalette = staticCompositionLocalOf { LightPalette }

