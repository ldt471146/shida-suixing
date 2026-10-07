package cn.gxnu.campus.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
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

/**
 * Easing tokens. The control points are the Material 3 easing tokens, declared here rather than read
 * from a library object so every curve in the app is reviewable in one place.
 *
 * 刻意没有 `emphasized` 这一档：Material 的 emphasized 是三段 pathInterpolator，单个 cubic-bezier
 * 表达不了它（官方 CSS 列写的就是 "N/A (Use Standard as a fallback)"），写成 (0.2, 0, 0, 1) 只会
 * 让人以为用的是 emphasized 而其实用的是 standard。进出场用下面那两条可以精确表达的曲线。
 *
 * Sources: https://m3.material.io/styles/motion/easing-and-duration/tokens-specs
 */
object CampusEasing {
    val emphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val standardDecelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)
    val standardAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
}

/**
 * Motion tokens. 时长取 Material 3 的 duration token，缓动取官方「哪种转场配哪条曲线、配多长」的
 * 那张表：进入屏幕用 emphasized decelerate、退出用 emphasized accelerate，而退出比进入短 ——
 * 官方原话是退出只值更少的注意力。
 *
 * Sources:
 * - https://m3.material.io/styles/motion/easing-and-duration/tokens-specs
 * - https://m3.material.io/styles/motion/easing-and-duration/applying-easing-and-duration
 * - https://developer.android.com/develop/ui/compose/animation/customize
 */
object CampusMotion {
    /** 颜色与背景的过渡：M3 short4 (200ms)。 */
    const val DURATION_MS = 200

    /**
     * 进入二级页：M3「Enter the screen」推荐 Emphasized decelerate + 400ms（medium4）。整屏推入
     * 覆盖的面积大，所以它比 tab 切换长 —— 官方把时长和转场覆盖的面积绑在一起。
     */
    const val PAGE_ENTER_MS = 400

    /** 退出二级页 / 返回：M3「Exit the screen」推荐 Emphasized accelerate + 200ms（short4）。 */
    const val PAGE_EXIT_MS = 200

    /** 顶层 tab 之间：进入 M3 medium1 (250ms)、退出 short4 (200ms)。 */
    const val TAB_ENTER_MS = 250
    const val TAB_EXIT_MS = 200

    /** 列表条目自身的入场窗口：M3 medium2 (300ms)。 */
    const val ITEM_ENTER_MS = 300

    /** 错峰入场时相邻条目的间隔基准。 */
    const val ITEM_STAGGER_MS = 35

    /** 条目入场时从下方位移的距离。 */
    val ITEM_ENTER_SHIFT = 14.dp

    /** 按压反馈的缩放：轻微，够察觉但不跳。 */
    const val PRESS_SCALE = 0.97f

    /** 按压时的变暗量（只动 alpha，不动颜色，避免每帧做颜色插值）。 */
    const val PRESS_DIM = 0.08f

    /**
     * 按压回弹：阻尼比 0.8 —— 落在官方常量的 LowBouncy (0.75) 与 NoBouncy (1.0) 之间，稍有过冲
     * 但立刻收住；刚度用 StiffnessMedium (1500)，按下到回弹收完约 100ms。
     *
     * Sources: https://developer.android.com/develop/ui/compose/animation/customize
     * （dampingRatio / stiffness 的含义）与 Spring 常量表：
     * https://developer.android.com/reference/kotlin/androidx/compose/animation/core/Spring
     */
    val pressSpring: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)

    /** 底栏指示器位移：同样的阻尼比，刚度用中低档（400，进出场动画的默认刚度），滑到位而不是弹到。 */
    val indicatorSpring: SpringSpec<Dp> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

    /** 底栏指示器胶囊与图标台的尺寸，指示器与被点亮的图标共用这两个值。 */
    val navIndicatorWidth = 64.dp
    val navIndicatorHeight = 30.dp
}

val LocalCampusPalette = staticCompositionLocalOf { LightPalette }

