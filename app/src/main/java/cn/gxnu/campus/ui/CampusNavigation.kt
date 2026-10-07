package cn.gxnu.campus.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cn.gxnu.campus.core.CampusRoute
import cn.gxnu.campus.ui.theme.CampusEasing
import cn.gxnu.campus.ui.theme.CampusMotion
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette

/**
 * 一个页面的身份。前三个是顶层 tab（顺序即底栏顺序，最右是「我的」），其余都是从某个 tab 或
 * 另一个二级页进去的二级页。
 */
enum class Destination(val title: String) {
    HOME("首页"),
    SERVICES("功能"),
    PROFILE("我的"),
    CAMPUS_NETWORK("校园网"),
    TIMETABLE("课表"),
    SETTINGS("设置"),
    ACCOUNT("校园账号");

    val isTab: Boolean get() = this in TOP_LEVEL_TABS
}

/** 底栏的三个 tab，顺序就是显示顺序。 */
internal val TOP_LEVEL_TABS: List<Destination> = listOf(
    Destination.HOME,
    Destination.SERVICES,
    Destination.PROFILE
)

/** 从功能页里的一个模块路由到它对应的页面。 */
internal fun destinationOf(route: CampusRoute): Destination = when (route) {
    CampusRoute.CAMPUS_NETWORK -> Destination.CAMPUS_NETWORK
    CampusRoute.TIMETABLE -> Destination.TIMETABLE
}

/**
 * 切换方向：往右切（下标变大）返回正数，往左切返回负数。只要有一端不是顶层 tab 就是 0 ——
 * 二级页之间的往返由「进入/返回」那套转场表达，不该被读成左右横移。
 */
internal fun tabTravel(from: Destination, to: Destination): Int {
    val fromIndex = TOP_LEVEL_TABS.indexOf(from)
    val toIndex = TOP_LEVEL_TABS.indexOf(to)
    if (fromIndex < 0 || toIndex < 0) return 0
    return (toIndex - fromIndex).coerceIn(-1, 1)
}

/**
 * 一个页面的上一级。二级页回到它自己进来的那一层，而 [accountReturn] 是打开校园账号页时记住的
 * 来源 —— 从「我的」进和从「校园网」进要各回各的地方。
 */
internal fun destinationParent(destination: Destination, accountReturn: Destination): Destination = when (destination) {
    Destination.CAMPUS_NETWORK, Destination.TIMETABLE -> Destination.SERVICES
    Destination.SETTINGS -> Destination.PROFILE
    Destination.ACCOUNT -> accountReturn.takeIf { it != Destination.ACCOUNT } ?: Destination.HOME
    Destination.HOME, Destination.SERVICES, Destination.PROFILE -> Destination.HOME
}

/** 页面在层级里的深度：顶层 tab 是 0，二级页是 1。转场的进退靠它判断。 */
internal fun destinationDepth(destination: Destination): Int = if (destination.isTab) 0 else 1

/**
 * 底栏此刻该点亮哪个 tab：二级页保持它所属的 tab 亮着，校园账号页跟着它进来时的来源走。
 */
internal fun litTab(destination: Destination, accountReturn: Destination): Destination = when (destination) {
    Destination.HOME -> Destination.HOME
    Destination.SERVICES, Destination.CAMPUS_NETWORK, Destination.TIMETABLE -> Destination.SERVICES
    Destination.PROFILE, Destination.SETTINGS -> Destination.PROFILE
    Destination.ACCOUNT -> when (accountReturn) {
        Destination.SERVICES, Destination.CAMPUS_NETWORK, Destination.TIMETABLE -> Destination.SERVICES
        Destination.PROFILE, Destination.SETTINGS -> Destination.PROFILE
        else -> Destination.HOME
    }
}

internal fun destinationIcon(destination: Destination): ImageVector = when (destination) {
    Destination.HOME -> Icons.Outlined.Home
    Destination.SERVICES -> Icons.Outlined.GridView
    Destination.PROFILE, Destination.ACCOUNT -> Icons.Outlined.PersonOutline
    Destination.CAMPUS_NETWORK -> Icons.Outlined.Wifi
    Destination.TIMETABLE -> Icons.Outlined.CalendarMonth
    Destination.SETTINGS -> Icons.Outlined.Settings
}

/**
 * 页面之间的转场。三种关系各有一套动作，且都用 Material 3 的时长与缓动：
 *
 * - tab ↔ tab：按切换方向水平位移 + 淡入淡出（进入 medium1 + emphasized decelerate，退出 short4
 *   + emphasized accelerate）；
 * - 进入二级页：新页从右侧整屏推入，旧页向左退四分之一屏并淡出；
 * - 返回：二级页整屏向右滑出，父页从左侧四分之一屏处回来。
 *
 * 官方依据：https://m3.material.io/styles/motion/transitions/transition-patterns
 * （进入用减速曲线、返回用加速曲线）与
 * https://developer.android.com/develop/ui/compose/animation/quick-guide
 */
internal fun AnimatedContentTransitionScope<Destination>.campusTransition(accountReturn: Destination): ContentTransform {
    val from = initialState
    val to = targetState
    val travel = tabTravel(from, to)
    return when {
        travel != 0 -> tabTransform(forward = travel > 0)
        to == destinationParent(from, accountReturn) -> backTransform()
        else -> forwardTransform()
    }
}

/** tab 之间：两页同向横移五分之一屏，方向由 tab 下标决定。 */
private fun tabTransform(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    val enter = slideInHorizontally(
        animationSpec = tween(CampusMotion.TAB_ENTER_MS, easing = CampusEasing.emphasizedDecelerate)
    ) { width -> direction * width / 5 } + fadeIn(tween(CampusMotion.TAB_ENTER_MS))
    val exit = slideOutHorizontally(
        animationSpec = tween(CampusMotion.TAB_EXIT_MS, easing = CampusEasing.emphasizedAccelerate)
    ) { width -> -direction * width / 5 } + fadeOut(tween(CampusMotion.TAB_EXIT_MS))
    return enter togetherWith exit
}

/** 进入二级页：新页从右整屏推入，父页向左退四分之一屏。 */
private fun forwardTransform(): ContentTransform {
    val enter = slideInHorizontally(
        animationSpec = tween(CampusMotion.PAGE_ENTER_MS, easing = CampusEasing.emphasizedDecelerate)
    ) { width -> width } + fadeIn(tween(CampusMotion.PAGE_ENTER_MS, easing = CampusEasing.standardDecelerate))
    val exit = slideOutHorizontally(
        animationSpec = tween(CampusMotion.PAGE_EXIT_MS, easing = CampusEasing.emphasizedAccelerate)
    ) { width -> -width / 4 } + fadeOut(tween(CampusMotion.PAGE_EXIT_MS))
    return enter togetherWith exit
}

/** 返回：二级页整屏向右滑出，父页从左侧四分之一屏处回来。 */
private fun backTransform(): ContentTransform {
    val enter = slideInHorizontally(
        animationSpec = tween(CampusMotion.PAGE_ENTER_MS, easing = CampusEasing.emphasizedDecelerate)
    ) { width -> -width / 4 } + fadeIn(tween(CampusMotion.PAGE_ENTER_MS, easing = CampusEasing.standardDecelerate))
    val exit = slideOutHorizontally(
        animationSpec = tween(CampusMotion.PAGE_EXIT_MS, easing = CampusEasing.emphasizedAccelerate)
    ) { width -> width } + fadeOut(tween(CampusMotion.PAGE_EXIT_MS))
    return enter togetherWith exit
}

/**
 * 底栏。指示器是一枚会滑动的胶囊：它按当前点亮的 tab 弹簧到位，而不是每个 tab 各自硬切一次
 * 颜色；位移写在 `offset { }` 里推迟到布局阶段求值，所以滑动过程不会触发任何重新测量。
 *
 * Material 3 navigation bar 的指示器语言见
 * https://m3.material.io/components/navigation-bar/guidelines
 */
@Composable
internal fun CampusNavigationBar(
    destination: Destination,
    accountReturn: Destination,
    enabled: Boolean,
    onSelect: (Destination) -> Unit
) {
    val palette = LocalCampusPalette.current
    val lit = litTab(destination, accountReturn)
    val litIndex = TOP_LEVEL_TABS.indexOf(lit).coerceAtLeast(0)
    Surface(color = palette.chrome) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
            HorizontalDivider(color = palette.border)
            BoxWithConstraints(
                Modifier.widthIn(max = 680.dp).fillMaxWidth()
                    .padding(horizontal = CampusSpace.md, vertical = CampusSpace.sm)
            ) {
                val slot = maxWidth / TOP_LEVEL_TABS.size
                val indicatorX: State<Dp> = animateDpAsState(
                    targetValue = slot * litIndex + (slot - CampusMotion.navIndicatorWidth) / 2,
                    animationSpec = CampusMotion.indicatorSpring,
                    label = "nav_indicator"
                )
                Box(
                    Modifier.align(Alignment.TopStart)
                        .offset { IntOffset(indicatorX.value.roundToPx(), NavItemTopPadding.roundToPx()) }
                        .size(CampusMotion.navIndicatorWidth, CampusMotion.navIndicatorHeight)
                ) {
                    Surface(
                        Modifier.fillMaxWidth().height(CampusMotion.navIndicatorHeight),
                        shape = CampusRadius.pillShape,
                        color = if (enabled) palette.selected else palette.muted
                    ) {}
                }
                Row(Modifier.fillMaxWidth().selectableGroup()) {
                    TOP_LEVEL_TABS.forEach { item ->
                        NavItem(
                            destination = item,
                            selected = item == lit,
                            enabled = enabled,
                            onClick = { onSelect(item) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** 指示器与图标台共用同一个上边距，两个值必须一致。 */
private val NavItemTopPadding = 6.dp

@Composable
private fun NavItem(
    destination: Destination,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalCampusPalette.current
    // 颜色跟着选中状态平滑过渡，而不是换一个颜色值。
    val ink = animateColorAsState(
        targetValue = when {
            !enabled -> palette.textTertiary
            selected -> palette.onAccentWash
            else -> palette.textSecondary
        },
        animationSpec = tween(CampusMotion.DURATION_MS, easing = CampusEasing.standard),
        label = "nav_item_ink"
    )
    Column(
        modifier
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(vertical = NavItemTopPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            Modifier.size(CampusMotion.navIndicatorWidth, CampusMotion.navIndicatorHeight),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                destinationIcon(destination),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = ink.value
            )
        }
        Text(destination.title, color = ink.value, style = MaterialTheme.typography.labelSmall)
    }
}
