package cn.gxnu.campus.ui.theme

import android.app.Activity
import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import cn.gxnu.campus.ui.ThemeMode

/**
 * The palette is the design source; the Material colour scheme is derived from it so the few
 * stock Material components still in the app (dialogs, sheets, snackbars, text fields) inherit
 * the same surfaces, borders and single accent instead of falling back to Material defaults.
 */
private fun CampusPalette.toColorScheme(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentWash,
        onPrimaryContainer = onAccentWash,
        inversePrimary = LightPalette.accent,
        secondary = success,
        onSecondary = Color(0xFF0C2318),
        secondaryContainer = successWash,
        onSecondaryContainer = success,
        // The only stock component that reads tertiary is the portal success panel, so
        // tertiary carries the success colour.
        tertiary = success,
        onTertiary = Color(0xFF0C2318),
        tertiaryContainer = successWash,
        onTertiaryContainer = success,
        background = canvas,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = muted,
        onSurfaceVariant = textSecondary,
        surfaceTint = Color.Transparent,
        surfaceBright = muted,
        surfaceDim = canvas,
        surfaceContainerLowest = canvas,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = muted,
        surfaceContainerHighest = muted,
        outline = borderStrong,
        outlineVariant = border,
        error = danger,
        onError = Color(0xFF3A100D),
        errorContainer = dangerWash,
        onErrorContainer = danger,
        inverseSurface = textPrimary,
        inverseOnSurface = canvas,
        scrim = Color(0xCC000000)
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentWash,
        onPrimaryContainer = onAccentWash,
        inversePrimary = DarkPalette.accent,
        secondary = success,
        onSecondary = Color.White,
        secondaryContainer = successWash,
        onSecondaryContainer = success,
        tertiary = success,
        onTertiary = Color.White,
        tertiaryContainer = successWash,
        onTertiaryContainer = success,
        background = canvas,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = muted,
        onSurfaceVariant = textSecondary,
        surfaceTint = Color.Transparent,
        surfaceBright = surface,
        surfaceDim = canvas,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = muted,
        surfaceContainerHighest = muted,
        outline = borderStrong,
        outlineVariant = border,
        error = danger,
        onError = Color.White,
        errorContainer = dangerWash,
        onErrorContainer = danger,
        inverseSurface = Color(0xFF23262C),
        inverseOnSurface = Color(0xFFF2F3F5),
        scrim = Color(0x66000000)
    )
}

private fun textStyle(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = CampusSans,
    fontWeight = weight,
    fontSynthesis = FontSynthesis.None,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp
)

/** Page title 20/600, section title 15/600, list body 14/400, meta 12/400. */
private val CampusTypography = Typography(
    displayLarge = textStyle(28, 36, FontWeight.SemiBold),
    displayMedium = textStyle(26, 34, FontWeight.SemiBold),
    displaySmall = textStyle(24, 32, FontWeight.SemiBold),
    headlineLarge = textStyle(24, 32, FontWeight.SemiBold),
    headlineMedium = textStyle(20, 28, FontWeight.SemiBold),
    headlineSmall = textStyle(18, 26, FontWeight.SemiBold),
    titleLarge = textStyle(17, 24, FontWeight.SemiBold),
    titleMedium = textStyle(15, 22, FontWeight.Medium),
    titleSmall = textStyle(14, 20, FontWeight.Medium),
    bodyLarge = textStyle(15, 23),
    bodyMedium = textStyle(14, 21),
    bodySmall = textStyle(12, 18),
    labelLarge = textStyle(14, 20, FontWeight.SemiBold),
    labelMedium = textStyle(12, 17, FontWeight.Medium),
    labelSmall = textStyle(11, 16, FontWeight.Medium)
)

@Composable
fun CampusTheme(theme: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val view = LocalView.current
    val motionEnabled = rememberSystemMotionEnabled()
    if (!view.isInEditMode) {
        SideEffect {
            view.context.activity()?.let { activity ->
                WindowCompat.getInsetsController(activity.window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    CompositionLocalProvider(
        LocalCampusPalette provides if (dark) DarkPalette else LightPalette,
        LocalCampusMotionEnabled provides motionEnabled
    ) {
        MaterialTheme(
            colorScheme = (if (dark) DarkPalette else LightPalette).toColorScheme(dark),
            typography = CampusTypography,
            shapes = Shapes(
                extraSmall = CampusRadius.smShape,
                small = CampusRadius.mdShape,
                medium = CampusRadius.mdShape,
                large = CampusRadius.lgShape,
                extraLarge = CampusRadius.lgShape
            ),
            content = content
        )
    }
}

// Let Android resolve Chinese and Latin together with its native UI font and fallback.
// The same scale and weight apply to network names, labels and body copy.
val CampusSans = FontFamily.SansSerif
val CampusLatin = CampusSans

val LocalCampusMotionEnabled = staticCompositionLocalOf { true }

@Composable
private fun rememberSystemMotionEnabled(): Boolean {
    val context = LocalContext.current.applicationContext
    var enabled by remember(context) { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
