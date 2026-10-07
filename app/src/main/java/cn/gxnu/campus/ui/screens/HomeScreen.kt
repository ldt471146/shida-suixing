package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cn.gxnu.campus.core.CampusRoute
import cn.gxnu.campus.core.CampusModules
import cn.gxnu.campus.core.TIMETABLE_WEEKDAYS
import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableCourse
import cn.gxnu.campus.core.TimetableCourseSlots
import cn.gxnu.campus.ui.TimetableUiState
import cn.gxnu.campus.ui.common.CampusCard
import cn.gxnu.campus.ui.common.CampusGhostButton
import cn.gxnu.campus.ui.common.CampusMark
import cn.gxnu.campus.ui.common.CampusPageHeader
import cn.gxnu.campus.ui.common.CampusPill
import cn.gxnu.campus.ui.common.EntranceGate
import cn.gxnu.campus.ui.common.entrance
import cn.gxnu.campus.ui.common.rememberPageEntrance
import cn.gxnu.campus.ui.theme.CampusMotion
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette
import java.time.LocalDate

/**
 * 首页：今天上什么课，以及两个最常用的入口。
 *
 * 这一页刻意没有校园网状态卡 —— 校园网是要点开才进的功能，不该在打开应用时就挡在这里。页面
 * 上没有一个段落式的说明，回答的是「现在」这一个问题。
 */
@Composable
fun HomeScreen(
    timetableState: TimetableUiState,
    gate: EntranceGate,
    onRoute: (CampusRoute) -> Unit,
    modifier: Modifier = Modifier
) {
    val todayEpochDay = rememberTodayEpochDay()
    val today = remember(
        timetableState.timetable,
        timetableState.restoring,
        timetableState.termStartEpochDay,
        timetableState.todayWeekday,
        todayEpochDay
    ) {
        homeToday(
            timetable = timetableState.timetable,
            restoring = timetableState.restoring,
            todayEpochDay = todayEpochDay,
            todayWeekday = timetableState.todayWeekday,
            termStartEpochDay = timetableState.termStartEpochDay
        )
    }
    val modules = CampusModules.available
    val entrance = rememberPageEntrance(gate, "home", itemCount = 3)

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = CampusSpace.lg,
            end = CampusSpace.lg,
            top = CampusSpace.md,
            bottom = CampusSpace.xxl
        ),
        verticalArrangement = Arrangement.spacedBy(CampusSpace.lg)
    ) {
        item(key = "header") {
            Box(Modifier.entrance(entrance, 0)) {
                CampusPageHeader(
                    title = "师大随行",
                    subtitle = "广西师范大学 · 育才校区",
                    leading = { CampusMark() }
                )
            }
        }
        item(key = "today") {
            Box(Modifier.entrance(entrance, 1)) {
                TodayCard(
                    today = today,
                    timetable = timetableState.timetable,
                    onOpenTimetable = { onRoute(CampusRoute.TIMETABLE) }
                )
            }
        }
        item(key = "shortcuts") {
            Row(
                Modifier.fillMaxWidth().entrance(entrance, 2),
                horizontalArrangement = Arrangement.spacedBy(CampusSpace.md)
            ) {
                modules.forEach { module ->
                    CampusModuleTile(
                        title = module.title,
                        icon = moduleIcon(module.route),
                        onClick = { onRoute(module.route) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * 今日课程。四种状态各说一句话，没有一句是解释性的：缺什么就给一个去补它的按钮，有课就把课
 * 列出来，没课就直说没课。
 */
@Composable
private fun TodayCard(
    today: HomeToday,
    timetable: Timetable?,
    onOpenTimetable: () -> Unit
) {
    val palette = LocalCampusPalette.current
    val day = today as? HomeToday.Day
    val weekdayLabel = day?.let { TIMETABLE_WEEKDAYS[it.weekday - 1] }
    val slots = remember(timetable) {
        timetable?.let { TimetableCourseSlots.assign(it.courses, COURSE_TINT_COUNT) } ?: emptyMap()
    }
    CampusCard {
        Column(Modifier.padding(CampusSpace.lg), verticalArrangement = Arrangement.spacedBy(CampusSpace.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "今日课程",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        when (today) {
                            HomeToday.Loading -> "正在读取本机课表…"
                            HomeToday.MissingTimetable -> "还没有课表"
                            HomeToday.MissingTermStart -> "还没设置开学日期"
                            is HomeToday.Day -> "$weekdayLabel · 第 ${today.week} 周"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary
                    )
                }
                if (day != null && day.courses.isNotEmpty()) {
                    CampusPill("${day.courses.size} 节", palette.textSecondary, palette.muted)
                }
            }
            when (today) {
                HomeToday.Loading -> Unit
                HomeToday.MissingTimetable -> QuietAction("去取课表", onOpenTimetable)
                HomeToday.MissingTermStart -> QuietAction("去课表设置", onOpenTimetable)
                is HomeToday.Day -> if (today.courses.isEmpty()) {
                    QuietLine("今天没课")
                } else {
                    Column {
                        today.courses.forEachIndexed { index, course ->
                            TodayCourseRow(
                                course = course,
                                time = timetable?.timeSpanOf(course),
                                tint = tintAt(palette, slots[course] ?: 0),
                                onClick = onOpenTimetable
                            )
                            if (index < today.courses.lastIndex) HorizontalDivider(color = palette.border)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuietAction(title: String, onClick: () -> Unit) {
    CampusGhostButton(title = title, onClick = onClick, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun QuietLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalCampusPalette.current.textSecondary
    )
}

// Label columns are stated in sp rather than dp so they grow with the font scale instead of
// ellipsising a real value ("11-12", "上课时间") once the user turns the text size up. Every row of a
// list shares one width, so the column beside it stays aligned from row to row. The 今日 period
// column holds a whole label — "第3-4节" — so it is sized for the widest of them.
private val TodayPeriodColumnWidth = 56.sp

@Composable
private fun TodayCourseRow(course: TimetableCourse, time: String?, tint: CourseTint, onClick: () -> Unit) {
    val palette = LocalCampusPalette.current
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = CampusSpace.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The colour rule ties this row to the same course in the grid.
        Box(Modifier.width(3.dp).height(36.dp).background(tint.rule, CampusRadius.pillShape))
        Spacer(Modifier.width(CampusSpace.md))
        val periodWidth = with(LocalDensity.current) { TodayPeriodColumnWidth.toDp() }
        // One line, and the whole label off the course itself: a bare "3" with a "节" underneath
        // would cost the row a second line to say what 第3节 already says.
        Text(
            course.periodLabel,
            modifier = Modifier.width(periodWidth),
            style = MaterialTheme.typography.labelLarge,
            color = tint.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(CampusSpace.sm))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                course.name,
                style = MaterialTheme.typography.titleSmall,
                color = palette.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(course.detailLabel.ifBlank { "教师、教室未填写" }, time).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = palette.textTertiary
        )
    }
}

/** 首页用不到，但把「今天是哪一天」的取法收在一处，测试和界面读的是同一个入口。 */
@Composable
private fun rememberTodayEpochDay(): Long {
    // 首页停在后台跨过零点时，「今天」必须跟着变；回到前台重读一次就够了。
    var epochDay by remember { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { epochDay = LocalDate.now().toEpochDay() }
    return epochDay
}
