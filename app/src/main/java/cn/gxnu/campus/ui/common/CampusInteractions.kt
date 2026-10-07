package cn.gxnu.campus.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import cn.gxnu.campus.ui.theme.CampusEasing
import cn.gxnu.campus.ui.theme.CampusMotion
import cn.gxnu.campus.ui.theme.LocalCampusMotionEnabled

/**
 * 按压反馈：按下时轻微缩小并变暗，松手弹回。
 *
 * 两件事都写在 [Modifier.graphicsLayer] 的 block 里，而不是 `offset` / `padding` / `size`：缩放与
 * 变暗都发生在绘制阶段，跳过了测量与布局，所以按下一张卡片不会让整页重新测量一次。
 *
 * 官方依据：https://developer.android.com/develop/ui/compose/performance/best-practices
 * （把状态读取推迟到绘制阶段可以避免每一帧重新组合）。
 */
@Composable
fun Modifier.pressFeedback(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = CampusMotion.PRESS_SCALE
): Modifier {
    val motionEnabled = LocalCampusMotionEnabled.current
    val pressed by interactionSource.collectIsPressedAsState()
    // 一个 0..1 的进度驱动缩放与变暗两件事：只有一条动画，也只是数字的插值。
    val press = animateFloatAsState(
        targetValue = if (pressed && enabled && motionEnabled) 1f else 0f,
        animationSpec = if (motionEnabled) CampusMotion.pressSpring else tween(0),
        label = "campus_press"
    )
    return graphicsLayer {
        val progress = press.value
        val scale = 1f - (1f - pressedScale) * progress
        scaleX = scale
        scaleY = scale
        alpha = 1f - CampusMotion.PRESS_DIM * progress
    }
}

/**
 * 「这个页面这次会话里已经走过入场动画」的记账本。
 *
 * 记在组合之外的普通容器里是有意的：它不参与快照，所以在组合期写入安全；而它只增不减，所以
 * 同一个页面无论被重组多少次都只会拿到一次 true。
 */
@Stable
class EntranceGate internal constructor() {
    private val seen = mutableSetOf<String>()

    /** 第一次问 [key] 返回 true，之后一直是 false。 */
    internal fun consume(key: String): Boolean = seen.add(key)
}

@Composable
fun rememberEntranceGate(): EntranceGate = remember { EntranceGate() }

/**
 * 整页共用的入场时钟：一个动画推进全页条目错峰淡入 + 上移。
 *
 * 用「一个时钟 + 每个条目取自己的窗口」而不是「每个条目一条动画」，是为了让入场这件事天然只
 * 发生一次：时钟活在页面里，条目被 LazyColumn 回收再放回来时读到的仍是同一个时钟的当前值，
 * 于是滚回去看到的是第一条已经走完的条目，而不是重播一遍。
 */
@Stable
class CampusEntrance internal constructor(
    private val progress: () -> Float,
    private val itemCount: Int,
    private val windowMs: Int,
    private val staggerMs: Int
) {
    /** 第 [index] 个条目此刻的入场进度，0..1，已经过缓动。 */
    fun fraction(index: Int): Float {
        if (itemCount <= 0 || windowMs <= 0) return 1f
        val total = totalMs()
        val elapsed = progress() * total
        val start = index.coerceAtLeast(0) * staggerMs
        val raw = ((elapsed - start) / windowMs).coerceIn(0f, 1f)
        return CampusEasing.emphasizedDecelerate.transform(raw)
    }

    private fun totalMs(): Float = (windowMs + staggerMs * (itemCount - 1).coerceAtLeast(0)).toFloat()
}

/**
 * 创建页面级入场时钟。[run] 为 false（已经播过、或系统关了动画）时时钟直接停在终点，条目立刻
 * 处于最终状态，不存在「先看不见再跳出来」的一帧。
 */
@Composable
fun rememberCampusEntrance(run: Boolean, itemCount: Int): CampusEntrance {
    val motionEnabled = LocalCampusMotionEnabled.current
    val plays = run && motionEnabled && itemCount > 0
    val clock = remember { Animatable(if (plays) 0f else 1f) }
    LaunchedEffect(plays) {
        if (plays) {
            clock.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = CampusMotion.ITEM_ENTER_MS +
                        CampusMotion.ITEM_STAGGER_MS * (itemCount - 1).coerceAtLeast(0),
                    easing = LinearEasing
                )
            )
        } else {
            clock.snapTo(1f)
        }
    }
    return remember(clock, itemCount, plays) {
        CampusEntrance(
            progress = { clock.value },
            itemCount = if (plays) itemCount else 0,
            windowMs = CampusMotion.ITEM_ENTER_MS,
            staggerMs = CampusMotion.ITEM_STAGGER_MS
        )
    }
}

/**
 * 第 [index] 个条目的入场位移与淡入。和按压反馈一样只动绘制阶段的两个属性，不碰尺寸与位置。
 */
fun Modifier.entrance(entrance: CampusEntrance, index: Int): Modifier = graphicsLayer {
    val fraction = entrance.fraction(index)
    alpha = fraction
    translationY = (1f - fraction) * CampusMotion.ITEM_ENTER_SHIFT.toPx()
}

/** 页面级入场：把 [key] 交给 [gate] 记账，只有第一次进入这个页面时返回 true。 */
@Composable
fun rememberPageEntrance(gate: EntranceGate, key: String, itemCount: Int): CampusEntrance =
    rememberCampusEntrance(run = remember(gate, key) { gate.consume(key) }, itemCount = itemCount)
