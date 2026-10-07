package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.gxnu.campus.core.TIMETABLE_WEEKDAYS
import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableCourse
import cn.gxnu.campus.ui.TimetableUiState
import cn.gxnu.campus.ui.common.CampusCard
import cn.gxnu.campus.ui.common.CampusPill
import cn.gxnu.campus.ui.common.CampusPrimaryButton
import cn.gxnu.campus.ui.common.SectionLabel
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette

/** Week strip, 当前周 affordance and the term start the whole calculation hangs off. */
@Composable
internal fun WeekSwitcherCard(
    state: TimetableUiState,
    showingCurrentWeek: Boolean,
    onSelectWeek: (Int) -> Unit,
    onCurrentWeek: () -> Unit,
    onSetTermStart: () -> Unit
) {
    val palette = LocalCampusPalette.current
    val strip = rememberLazyListState()
    // Opening on 第 9 周 would otherwise show a strip starting at 第 1 周, hiding the selected chip.
    LaunchedEffect(state.selectedWeek, state.weekCount) {
        val index = state.selectedWeek - 1
        if (index in 0 until state.weekCount && strip.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            strip.animateScrollToItem(index)
        }
    }
    CampusCard {
        Column(Modifier.padding(CampusSpace.lg), verticalArrangement = Arrangement.spacedBy(CampusSpace.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "第 ${state.selectedWeek} 周",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        weekSubtitle(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary
                    )
                }
                when {
                    state.termStartEpochDay == null -> InlineAction("设置开学日期", Icons.Outlined.CalendarMonth, onSetTermStart)
                    showingCurrentWeek -> CampusPill("当前周", palette.onAccentWash, palette.accentWash)
                    else -> InlineAction("回到当前周", Icons.Outlined.Today, onCurrentWeek)
                }
            }
            LazyRow(
                Modifier.fillMaxWidth().selectableGroup(),
                state = strip,
                horizontalArrangement = Arrangement.spacedBy(CampusSpace.xs)
            ) {
                items(count = state.weekCount) { index ->
                    val week = index + 1
                    WeekChip(
                        week = week,
                        selected = week == state.selectedWeek,
                        current = state.termStartEpochDay != null && week == state.currentWeek,
                        onClick = { onSelectWeek(week) }
                    )
                }
            }
            if (state.termStartEpochDay == null) {
                Text(
                    "设置开学日期后，本机可以算出当前是第几周，单双周和上课周次也能对上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textTertiary
                )
            }
        }
    }
}

@Composable
private fun WeekChip(week: Int, selected: Boolean, current: Boolean, onClick: () -> Unit) {
    val palette = LocalCampusPalette.current
    val background = if (selected) palette.selected else palette.muted
    val ink = if (selected) palette.onAccentWash else palette.textSecondary
    val description = buildString {
        append("第 $week 周")
        if (current) append("，当前周")
        if (selected) append("，已选中")
    }
    Surface(
        color = background,
        contentColor = ink,
        shape = CampusRadius.mdShape,
        border = if (selected) BorderStroke(1.dp, palette.accentBorder) else null,
        modifier = Modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
    ) {
        Column(
            Modifier.widthIn(min = 42.dp).padding(horizontal = CampusSpace.sm, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                "$week",
                style = MaterialTheme.typography.labelLarge,
                color = if (current && !selected) palette.onAccentWash else ink
            )
            // A same-height marker row keeps every chip aligned whether or not it is 当前周.
            if (current) {
                Box(Modifier.size(4.dp).background(palette.accent, CircleShape))
            } else {
                Spacer(Modifier.size(4.dp))
            }
        }
    }
}

/** What is taught on today's weekday of the week being shown. */
@Composable
internal fun TodayCard(
    state: TimetableUiState,
    timetable: Timetable,
    showingCurrentWeek: Boolean,
    slots: Map<TimetableCourse, Int>,
    busy: Boolean,
    onCourse: (TimetableCourse) -> Unit
) {
    val palette = LocalCampusPalette.current
    val weekday = state.todayWeekday
    val weekdayLabel = TIMETABLE_WEEKDAYS[weekday - 1]
    val courses = remember(timetable, weekday, state.selectedWeek) {
        timetable.coursesOn(weekday, state.selectedWeek)
    }
    CampusCard {
        Column(Modifier.padding(CampusSpace.lg), verticalArrangement = Arrangement.spacedBy(CampusSpace.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (showingCurrentWeek) "今日课程" else "第 ${state.selectedWeek} 周 · $weekdayLabel",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        when {
                            showingCurrentWeek -> "$weekdayLabel · 第 ${state.selectedWeek} 周"
                            state.termStartEpochDay == null -> "未设置开学日期，先按第 ${state.selectedWeek} 周显示"
                            else -> "正在查看第 ${state.selectedWeek} 周"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary
                    )
                }
                if (courses.isNotEmpty()) {
                    CampusPill("${courses.size} 节", palette.textSecondary, palette.muted)
                }
            }
            when {
                busy && courses.isEmpty() -> Unit
                courses.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(CampusSpace.xs)) {
                    Text(
                        if (showingCurrentWeek) "今天没课" else "这一天没有课",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary
                    )
                    Text(
                        if (showingCurrentWeek) "可以休息，或者看看这周的其他安排。"
                        else "换一个周次，或者回到当前周。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary
                    )
                }
                else -> Column {
                    courses.forEachIndexed { index, course ->
                        TodayCourseRow(
                            course = course,
                            time = timetable.timeSpanOf(course),
                            tint = tintAt(palette, slots[course] ?: 0),
                            onClick = { onCourse(course) }
                        )
                        if (index < courses.lastIndex) {
                            HorizontalDivider(color = palette.border)
                        }
                    }
                }
            }
        }
    }
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
        // The colour rule ties this row to the same course in the grid below.
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

@Composable
internal fun LoadingCard() {
    val palette = LocalCampusPalette.current
    CampusCard {
        Row(
            Modifier.padding(CampusSpace.lg).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = palette.accent)
            Spacer(Modifier.width(CampusSpace.md))
            Text(
                "正在读取本机保存的课表…",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary
            )
        }
    }
}

/** 课表管理: 更新课表、手动补课，或者删掉本机保存的这一份。 */
@Composable
internal fun ManageCard(
    account: String,
    signingIn: Boolean,
    onRefresh: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDelete: () -> Unit,
    onAddCourse: () -> Unit
) {
    val palette = LocalCampusPalette.current
    val signedIn = account.isNotBlank()
    CampusCard {
        Column(Modifier.padding(CampusSpace.lg), verticalArrangement = Arrangement.spacedBy(CampusSpace.md)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionLabel("课表管理")
                Text(
                    // 退出登录 之后课表还在，所以这一行说清楚现在这份课表是谁的、还能不能更新。
                    if (signedIn) "已登录 $account" else "未登录 · 正在用本机保存的课表",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textTertiary
                )
            }
            // 更新课表 is the one action that keeps this page in step with the 研究生系统, so it leads.
            CampusPrimaryButton(
                title = when {
                    signingIn -> "正在更新…"
                    signedIn -> "更新课表"
                    else -> "登录研究生系统"
                },
                onClick = if (signedIn) onRefresh else onSignIn,
                busy = signingIn,
                showProgress = false
            )
            // A fetched timetable is allowed to be wrong, so the manual route comes before everything
            // else: typing a missing 上课地点 must never require another round trip to the server.
            OutlinedActionButton("添加课程", Icons.Outlined.Add, onAddCourse, Modifier.fillMaxWidth(), enabled = !signingIn)
            Text(
                "取回来的课表有出入的地方都可以手动补正，包括上课地点；不会影响其他课程。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textTertiary
            )
            OutlinedActionButton(
                "退出登录",
                Icons.AutoMirrored.Outlined.Logout,
                onSignOut,
                Modifier.fillMaxWidth(),
                enabled = signedIn && !signingIn
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete, enabled = !signingIn, modifier = Modifier.heightIn(min = 44.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = palette.danger
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("删除课表", color = palette.danger, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
internal fun NoticeRow(text: String, onDismiss: () -> Unit) {
    val palette = LocalCampusPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = palette.textTertiary
        )
        Spacer(Modifier.width(CampusSpace.sm))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "关闭提示",
                modifier = Modifier.size(16.dp),
                tint = palette.textTertiary
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Small shared pieces
// ---------------------------------------------------------------------------------------------

/** Compact bordered action for a header row, where a full-width button would shout. */
@Composable
private fun InlineAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    val palette = LocalCampusPalette.current
    Surface(
        color = palette.surface,
        contentColor = palette.textSecondary,
        shape = CampusRadius.mdShape,
        border = BorderStroke(1.dp, palette.border),
        modifier = Modifier.heightIn(min = 40.dp)
    ) {
        Row(
            Modifier.clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 40.dp)
                .padding(horizontal = CampusSpace.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** Secondary action: surface fill plus a 1px border, matching the app's ghost buttons. */
@Composable
internal fun OutlinedActionButton(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalCampusPalette.current
    val ink = if (enabled) palette.textSecondary else palette.textTertiary
    Surface(
        color = palette.surface,
        contentColor = ink,
        shape = CampusRadius.mdShape,
        border = BorderStroke(1.dp, palette.border),
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        Row(
            Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = CampusSpace.sm),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = ink)
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
