package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.gxnu.campus.core.TIMETABLE_WEEKDAYS
import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableBlock
import cn.gxnu.campus.core.TimetableCalendar
import cn.gxnu.campus.core.TimetableCourse
import cn.gxnu.campus.core.TimetableCourseDraft
import cn.gxnu.campus.core.TimetableCourseSlots
import cn.gxnu.campus.core.TimetableDay
import cn.gxnu.campus.core.TimetableGridLayout
import cn.gxnu.campus.core.WeekParity
import cn.gxnu.campus.ui.TimetableActions
import cn.gxnu.campus.ui.TimetableUiState
import cn.gxnu.campus.ui.common.CampusCard
import cn.gxnu.campus.ui.common.CampusIconPlate
import cn.gxnu.campus.ui.common.CampusPageHeader
import cn.gxnu.campus.ui.common.CampusPill
import cn.gxnu.campus.ui.common.CampusPrimaryButton
import cn.gxnu.campus.ui.common.CampusSwitch
import cn.gxnu.campus.ui.common.SectionLabel
import cn.gxnu.campus.ui.theme.CampusPalette
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette
import java.time.LocalDate
import java.time.ZoneOffset

private val GridCellHeight = 74.dp
private val GridCellGap = 4.dp
// The 节次 gutter states two things per row — 第 N 节 and the clock it starts at — so it is sized for
// "第13节" rather than for a bare digit. It is still the narrowest column on the grid.
private val GridPeriodWidth = 58.dp
private val GridHeaderHeight = 52.dp
// Every cell and card on the grid shares one corner: the reference look is rounded, and a single
// value is what keeps a spanning course and a single-period one from looking like different things.
private val GridCellCorner = 10.dp
// Five day columns take an exact equal share of the card and never scroll: the ordinary week has to
// stay exactly as it was. A shorter week keeps a floor and a ceiling instead, so it neither breaks a
// four-character course name one character per line nor stretches across the whole card.
private val GridDayMinWidth = 68.dp
private val GridDayMaxWidth = 112.dp
// Six and seven column weeks give up the equal share for a readable column and scroll sideways
// instead: seven columns divided evenly on a 360dp phone come to about 37dp each, narrow enough that
// a twelve-character course name shows two characters of itself. A column that scrolls beats a column
// that fits and says nothing.
private const val GridCompactDayCount = 6
private val GridDayCompactMinWidth = 56.dp
// The column count from which the columns take the plain equal share of the card.
private const val GridSharedDayCount = 5
// A compact week saves lines, not width: two lines and an ellipsis instead of four, so a name never
// stacks one character per line.
private const val GridCompactNameMaxLines = 2
private const val GridSpanningNameMaxLines = 4
private const val GridSingleNameMaxLines = 3
// The width of the right-edge fade that says the grid keeps going past the card border.
private val GridScrollFadeWidth = 20.dp
// A cell is a fixed 66dp tall, so a name past ~1.3x would grow out of its row and overlap the next
// one. Only the grid caps its own text scale; the rest of the screen keeps the system setting.
private const val GridMaxFontScale = 1.3f
// Label columns are stated in sp rather than dp so they grow with the font scale instead of
// ellipsising a real value ("11-12", "上课时间") once the user turns the text size up. Every row of a
// list shares one width, so the column beside it stays aligned from row to row. The 今日 period
// column holds a whole label — "第3-4节" — so it is sized for the widest of them.
private val TodayPeriodColumnWidth = 56.sp
private val DetailLabelColumnWidth = 72.sp

/**
 * 课表: 登录研究生系统，把课表取回本机，再按教学周读它。 Stateless apart from the login form and the
 * transient sheets, so the host owns all the state that matters.
 */
@Composable
fun TimetableScreen(
    state: TimetableUiState,
    actions: TimetableActions,
    modifier: Modifier = Modifier
) {
    // The password stays out of saved state on purpose: it is a secret, not form data to restore, and
    // the controller never hands it back — this is only what was typed in this composition.
    var passwordDraft by remember { mutableStateOf("") }
    // Null until the user types: the field shows the account this device remembers until then.
    var accountDraft by remember { mutableStateOf<String?>(null) }
    var rememberAccount by remember { mutableStateOf(true) }
    var accountMissing by remember { mutableStateOf(false) }
    var passwordMissing by remember { mutableStateOf(false) }
    // 退出登录 之后总要留一条回到登录卡的路，否则换一个学号就只能靠重开应用。
    var loginRequested by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var settingTermStart by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<TimetableCourse?>(null) }
    var editor by remember { mutableStateOf<CourseEditorRequest?>(null) }

    val account = accountDraft ?: state.rememberedAccount
    val timetable = state.timetable
    val notice = state.message?.takeIf { it.isNotBlank() }
    // 今日 is only claimed when the grid is genuinely showing the week today falls in.
    val showingCurrentWeek = state.termStartEpochDay != null && state.selectedWeek == state.currentWeek
    // A signed-out session keeps its 课表 on screen, so the login card only takes the slot over when
    // there is nothing to show or the user asked for it from 课表管理.
    val showLogin = state.account.isBlank() && (timetable == null || loginRequested)

    fun signIn() {
        val userId = account.trim()
        accountMissing = userId.isEmpty()
        passwordMissing = passwordDraft.isEmpty()
        if (accountMissing || passwordMissing) return
        loginRequested = false
        actions.signIn(userId, passwordDraft, rememberAccount)
    }

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
        item {
            CampusPageHeader(
                title = "课表",
                subtitle = if (timetable == null) "登录研究生系统，把课表取回本机"
                else "共 ${timetable.courseCount} 门课程 · 更新于 ${formatDate(timetable.recognizedAtMillis)}"
            )
        }
        if (notice != null) {
            item {
                NoticeRow(notice) { actions.clearMessage(state.messageId) }
            }
        }
        item {
            when {
                state.restoring -> LoadingCard()
                showLogin -> LoginCard(
                    account = account,
                    password = passwordDraft,
                    onAccountChange = {
                        accountDraft = it
                        accountMissing = false
                    },
                    onPasswordChange = {
                        passwordDraft = it
                        passwordMissing = false
                    },
                    remember = rememberAccount,
                    onRememberChange = { rememberAccount = it },
                    accountMissing = accountMissing,
                    passwordMissing = passwordMissing,
                    signingIn = state.signingIn,
                    onSubmit = { signIn() }
                )
                timetable != null -> TimetableSection(
                    state = state,
                    timetable = timetable,
                    showingCurrentWeek = showingCurrentWeek,
                    busy = state.signingIn,
                    onSelectWeek = actions::selectWeek,
                    onCurrentWeek = actions::showCurrentWeek,
                    onSetTermStart = { settingTermStart = true },
                    onCourse = { detail = it }
                )
                else -> NoTimetableCard(signingIn = state.signingIn, onRefresh = actions::refresh)
            }
        }
        // 课表管理 only earns its place once a timetable is on screen: before that the login card
        // owns the page and already carries the one action that matters.
        if (timetable != null) {
            item {
                ManageCard(
                    account = state.account,
                    signingIn = state.signingIn,
                    onRefresh = actions::refresh,
                    onSignIn = { loginRequested = true },
                    onSignOut = actions::signOut,
                    onDelete = { confirmingDelete = true },
                    onAddCourse = { editor = CourseEditorRequest(index = null, course = null) }
                )
            }
        }
    }

    if (confirmingDelete) {
        val palette = LocalCampusPalette.current
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            containerColor = palette.surface,
            shape = CampusRadius.lgShape,
            title = { Text("删除本机课表？", style = MaterialTheme.typography.titleLarge, color = palette.textPrimary) },
            text = {
                Text(
                    "只会删除这台手机上保存的课表，开学日期会保留；研究生系统里的课表不受影响。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    actions.deleteTimetable()
                }) { Text("删除", color = palette.danger, style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmingDelete = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.accent)
                ) {
                    Text("取消", style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }

    if (settingTermStart) {
        TermStartDialog(
            initialEpochDay = state.termStartEpochDay,
            onConfirm = {
                settingTermStart = false
                actions.setTermStart(it)
            },
            onClear = {
                settingTermStart = false
                actions.clearTermStart()
            },
            onDismiss = { settingTermStart = false }
        )
    }

    detail?.let { course ->
        CourseDetailSheet(
            course = course,
            week = state.selectedWeek,
            timeSpan = timetable?.timeSpanOf(course),
            tint = tintFor(LocalCampusPalette.current, course, timetable),
            onDismiss = { detail = null },
            onEdit = {
                // Two bottom sheets must never stack, so the detail gives way to the editor. The
                // index is the course's own place in the timetable, which is what the controller
                // replaces; an untraceable course edits as a new one rather than the wrong one.
                detail = null
                editor = CourseEditorRequest(index = courseIndexIn(timetable, course), course = course)
            }
        )
    }

    editor?.let { request ->
        val palette = LocalCampusPalette.current
        CourseEditorSheet(
            index = request.index,
            course = request.course,
            tint = request.course?.let { tintFor(palette, it, timetable) } ?: tintAt(palette, 0),
            onSave = { draft ->
                val index = request.index
                editor = null
                actions.saveCourse(index, draft)
            },
            onDelete = request.index?.let { index ->
                {
                    editor = null
                    actions.removeCourse(index)
                }
            },
            onDismiss = { editor = null }
        )
    }
}

/** Which course the editor is open on: a null index is 添加, a null course is the blank form. */
private data class CourseEditorRequest(val index: Int?, val course: TimetableCourse?)

/** The course's own position in the timetable, or null when it is not in there at all. */
private fun courseIndexIn(timetable: Timetable?, course: TimetableCourse): Int? =
    timetable?.courses?.indexOf(course)?.takeIf { it >= 0 }

// ---------------------------------------------------------------------------------------------
// Timetable sections
// ---------------------------------------------------------------------------------------------

@Composable
private fun TimetableSection(
    state: TimetableUiState,
    timetable: Timetable,
    showingCurrentWeek: Boolean,
    busy: Boolean,
    onSelectWeek: (Int) -> Unit,
    onCurrentWeek: () -> Unit,
    onSetTermStart: () -> Unit,
    onCourse: (TimetableCourse) -> Unit
) {
    val slots = remember(timetable) {
        TimetableCourseSlots.assign(timetable.courses, COURSE_TINT_COUNT)
    }
    Column(verticalArrangement = Arrangement.spacedBy(CampusSpace.lg)) {
        WeekSwitcherCard(
            state = state,
            showingCurrentWeek = showingCurrentWeek,
            onSelectWeek = onSelectWeek,
            onCurrentWeek = onCurrentWeek,
            onSetTermStart = onSetTermStart
        )
        TodayCard(
            state = state,
            timetable = timetable,
            showingCurrentWeek = showingCurrentWeek,
            slots = slots,
            busy = busy,
            onCourse = onCourse
        )
        WeekGridCard(
            timetable = timetable,
            week = state.selectedWeek,
            termStartEpochDay = state.termStartEpochDay,
            todayWeekday = state.todayWeekday,
            showingCurrentWeek = showingCurrentWeek,
            slots = slots,
            onCourse = onCourse
        )
    }
}

/** Week strip, 当前周 affordance and the term start the whole calculation hangs off. */
@Composable
private fun WeekSwitcherCard(
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
private fun TodayCard(
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

/** The weekday × period grid for one teaching week. */
@Composable
private fun WeekGridCard(
    timetable: Timetable,
    week: Int,
    /** Epoch day of the term's first Monday; null until the user sets it, and then no dates show. */
    termStartEpochDay: Long?,
    todayWeekday: Int,
    showingCurrentWeek: Boolean,
    slots: Map<TimetableCourse, Int>,
    onCourse: (TimetableCourse) -> Unit
) {
    val palette = LocalCampusPalette.current
    val grid = remember(timetable, week) { TimetableGridLayout.build(timetable, week) }
    val horizontal = rememberScrollState()
    // The seven dates of the week being shown, so a header can say 周三 · 10/8 rather than just 周三.
    val dates = remember(termStartEpochDay, week) {
        termStartEpochDay?.let { TimetableCalendar.weekDates(it, week) }
    }
    CampusCard {
        Column(
            // The grid is the one card that gives its horizontal padding back to the columns: at a
            // five-day week every 4dp is another character the course names get to keep.
            Modifier.padding(horizontal = CampusSpace.sm, vertical = CampusSpace.lg),
            verticalArrangement = Arrangement.spacedBy(CampusSpace.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("周课表")
                Spacer(Modifier.weight(1f))
                Text(
                    // 这一周没有课时 grid.periods 是空的，直接说「第 0 节」是错的 —— 课表从第 1 节开始。
                    if (grid.periods.isEmpty()) "${grid.days.size} 天 · 没有课"
                    else "${grid.days.size} 天 · 第 ${grid.periods.size} 节",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textTertiary
                )
            }
            if (grid.periods.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(CampusSpace.xs)) {
                    Text("这一周没有课", style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
                    Text(
                        "第 $week 周没有任何课程安排，可以换一个周次查看。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary
                    )
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val dayCount = grid.days.size.coerceAtLeast(1)
                    val dayWidth = dayColumnWidth(dayCount, maxWidth)
                    val density = LocalDensity.current
                    val gridDensity = remember(density) {
                        if (density.fontScale > GridMaxFontScale) Density(density.density, GridMaxFontScale)
                        else density
                    }
                    // The cap is scoped to the grid rows alone: the columns keep the real density, so
                    // widths do not move, and only the text stops growing out of its fixed cell.
                    CompositionLocalProvider(LocalDensity provides gridDensity) {
                        // A 6 or 7 day week is wider than the card on purpose, so the grid — and only
                        // the grid — scrolls sideways inside it. The page keeps scrolling vertically,
                        // and both rows share one scroll state so the headers stay over their columns.
                        Box(
                            Modifier.fillMaxWidth().drawWithContent {
                                drawContent()
                                // The fade on the right edge is what says the grid continues past the
                                // border; it is the card's own fill, so it needs no new colour.
                                if (horizontal.canScrollForward) {
                                    val fade = GridScrollFadeWidth.toPx()
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(palette.surface.copy(alpha = 0f), palette.surface),
                                            startX = size.width - fade,
                                            endX = size.width
                                        ),
                                        topLeft = Offset(size.width - fade, 0f),
                                        size = Size(fade, size.height)
                                    )
                                }
                            }
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(GridCellGap)) {
                                Row(Modifier.horizontalScroll(horizontal), verticalAlignment = Alignment.CenterVertically) {
                                    // The gutter has its own header so the left column reads as the
                                    // 节次 axis rather than as a nameless strip of numbers.
                                    Box(
                                        Modifier.width(GridPeriodWidth).height(GridHeaderHeight),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "节次",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = palette.textTertiary,
                                            maxLines = 1
                                        )
                                    }
                                    grid.days.forEach { day ->
                                        Spacer(Modifier.width(GridCellGap))
                                        DayHeader(
                                            weekday = day.weekday,
                                            isToday = showingCurrentWeek && day.weekday == todayWeekday,
                                            dateLabel = dates?.getOrNull(day.weekday - 1)
                                                ?.let { "${it.monthValue}/${it.dayOfMonth}" },
                                            width = dayWidth
                                        )
                                    }
                                }
                                Row(Modifier.horizontalScroll(horizontal)) {
                                    Column(Modifier.width(GridPeriodWidth), verticalArrangement = Arrangement.spacedBy(GridCellGap)) {
                                        grid.periods.forEach { period ->
                                            Box(
                                                Modifier.fillMaxWidth().height(GridCellHeight),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                PeriodGutterLabel(
                                                    period,
                                                    timetable.periodTime(period)?.substringBefore('-')
                                                )
                                            }
                                        }
                                    }
                                    grid.days.forEach { day ->
                                        Spacer(Modifier.width(GridCellGap))
                                        DayColumn(
                                            day = day,
                                            width = dayWidth,
                                            dayCount = dayCount,
                                            isToday = showingCurrentWeek && day.weekday == todayWeekday,
                                            slots = slots,
                                            onCourse = onCourse
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The 节次 gutter label: the row's number, and under it the clock that row starts at when the source
 * stated one. A printed 课表 states both in this column, so the fetch carries the clock; a timetable
 * that states no clocks leaves the gutter with just the number.
 */
@Composable
private fun PeriodGutterLabel(period: Int, startClock: String?) {
    val palette = LocalCampusPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            gutterPeriodLabel(period),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 14.sp),
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (startClock != null) {
            Text(
                startClock,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = palette.textTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * One weekday header: 周X over its date, with today as a filled pill. The date only exists once the
 * term start is known — a guessed date would be worse than none, so the line simply stays empty.
 */
@Composable
private fun DayHeader(weekday: Int, isToday: Boolean, dateLabel: String?, width: Dp) {
    val palette = LocalCampusPalette.current
    Surface(
        Modifier.width(width).height(GridHeaderHeight),
        color = if (isToday) palette.accent else palette.muted,
        shape = RoundedCornerShape(GridCellCorner)
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                TIMETABLE_WEEKDAYS[weekday - 1],
                style = MaterialTheme.typography.labelMedium,
                color = if (isToday) palette.onAccent else palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // The second line is always present so every header keeps the same height.
            Text(
                dateLabel ?: if (isToday) "今天" else " ",
                style = MaterialTheme.typography.labelSmall,
                color = if (isToday) palette.onAccent.copy(alpha = 0.85f) else palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DayColumn(
    day: TimetableDay,
    width: Dp,
    dayCount: Int,
    isToday: Boolean,
    slots: Map<TimetableCourse, Int>,
    onCourse: (TimetableCourse) -> Unit
) {
    val palette = LocalCampusPalette.current
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(GridCellGap)) {
        day.blocks.forEach { block ->
            when (block) {
                is TimetableBlock.Course -> CourseCell(
                    course = block.course,
                    tint = tintAt(palette, slots[block.course] ?: 0),
                    dayCount = dayCount,
                    onCourse = onCourse,
                    modifier = Modifier.fillMaxWidth().height(blockHeight(block.span))
                )
                // An empty slot is a dash rather than a filled block: the grid then reads as the
                // courses it holds, and a free period stops competing with them for attention.
                is TimetableBlock.Free -> Box(
                    Modifier.fillMaxWidth().height(GridCellHeight)
                        .then(
                            if (isToday) Modifier.background(palette.accentWash.copy(alpha = 0.35f), RoundedCornerShape(GridCellCorner))
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "—",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textTertiary.copy(alpha = 0.5f),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** A course spanning n periods occupies exactly n rows plus the gaps it covers. */
private fun blockHeight(span: Int): Dp = GridCellHeight * span + GridCellGap * (span - 1)

/**
 * How wide one weekday column is once [dayCount] columns share [availableWidth] of card content.
 *
 * Five columns take an exact equal share, which is what makes the ordinary week fill the card with no
 * sideways scroll at all. Six and seven columns instead stand on [GridDayCompactMinWidth] and scroll
 * the grid: dividing 360dp evenly between seven columns leaves ~37dp each, and a course name that
 * narrow reads two characters at a time, so this grid trades the fit for the name. Four columns and
 * fewer keep their floor and ceiling, so a short week neither breaks a four-character name one
 * character per line nor stretches across the whole card; the ceiling follows the available width, so
 * it is never the cap that pushes the grid past the card.
 */
internal fun dayColumnWidth(dayCount: Int, availableWidth: Dp): Dp {
    val days = dayCount.coerceAtLeast(1)
    val share = dayColumnsWidth(days, availableWidth) / days
    return when {
        days >= GridCompactDayCount -> share.coerceAtLeast(GridDayCompactMinWidth)
        days >= GridSharedDayCount -> share
        // The floor is deliberately hard: on a 360dp phone a four-day week divides to 66.75dp, which
        // is below it, so that grid stays 2dp wider than its card rather than squeezing the name.
        else -> share.coerceAtMost(GridDayMaxWidth).coerceAtLeast(GridDayMinWidth)
    }
}

/** The width every weekday column shares between them: the card minus the 节次 gutter and the gaps. */
internal fun dayColumnsWidth(dayCount: Int, availableWidth: Dp): Dp {
    val days = dayCount.coerceAtLeast(1)
    // The extra gap is deliberate slack on the right edge: it keeps the grid from ending flush
    // against the card border and absorbs the sub-pixel rounding of banking the columns on integers.
    return (availableWidth - GridPeriodWidth - GridCellGap * (days + 1)).coerceAtLeast(0.dp)
}

/** The whole grid including its 节次 gutter, so a caller can check it still fits [availableWidth]. */
internal fun gridContentWidth(dayCount: Int, availableWidth: Dp): Dp {
    val days = dayCount.coerceAtLeast(1)
    return GridPeriodWidth + dayColumnWidth(days, availableWidth) * days + GridCellGap * days
}

/**
 * The 节次 gutter label for one row: the period's own number, written the way
 * [TimetableCourse.periodLabel] writes it for a course that starts here.
 */
internal fun gutterPeriodLabel(period: Int): String = "第${period}节"

/**
 * How many lines a course name gets in a week of this many columns.
 *
 * A compact week (six or seven columns) is the one that scrolls, and its cell is the one that has to
 * give something up: three lines there break a name into one character per line, so it takes two and
 * ellipsises the rest. The line count cannot follow the column width any more — a 56dp scrolling
 * column is *wider* than a 52.8dp five-column one, so no width threshold would separate them.
 * Anywhere else a cell spanning two periods earns the extra line its height already pays for.
 */
internal fun dayNameMaxLines(dayCount: Int, periodSpan: Int): Int = when {
    dayCount >= GridCompactDayCount -> GridCompactNameMaxLines
    periodSpan >= 2 -> GridSpanningNameMaxLines
    else -> GridSingleNameMaxLines
}

@Composable
private fun CourseCell(
    course: TimetableCourse,
    tint: CourseTint,
    dayCount: Int,
    onCourse: (TimetableCourse) -> Unit,
    modifier: Modifier
) {
    Column(
        modifier
            .background(tint.fill, RoundedCornerShape(GridCellCorner))
            .clickable(role = Role.Button, onClick = { onCourse(course) })
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            course.name,
            // One step below body text: a five-day week leaves about 16 characters of width per
            // cell, which is three per line at this size rather than one. The line count follows
            // the column width, so a seven-column cell stops at two lines instead of stacking
            // four and losing the name to the ellipsis.
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            color = tint.ink,
            fontWeight = FontWeight.SemiBold,
            maxLines = dayNameMaxLines(dayCount, course.periodSpan),
            overflow = TextOverflow.Ellipsis
        )
        // Where and who, in that order — the two things a student looks a course up for. A blank
        // field loses its line entirely rather than leaving an icon with nothing after it.
        CourseCellLine(Icons.Outlined.Place, course.room, tint, dayCount)
        CourseCellLine(Icons.Outlined.Person, course.teacher, tint, dayCount)
    }
}

/** One icon + value line inside a course cell; draws nothing when the value is blank. */
@Composable
private fun CourseCellLine(icon: ImageVector, value: String, tint: CourseTint, dayCount: Int) {
    if (value.isBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(10.dp),
            tint = tint.ink.copy(alpha = 0.7f)
        )
        Spacer(Modifier.width(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
            color = tint.ink.copy(alpha = 0.8f),
            maxLines = if (dayCount >= GridCompactDayCount) 1 else 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Login and state cards
// ---------------------------------------------------------------------------------------------

/** 登录研究生系统: 学号 + 密码换一枚 token，课表从研究生系统直接取回本机。 */
@Composable
private fun LoginCard(
    account: String,
    password: String,
    onAccountChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    remember: Boolean,
    onRememberChange: (Boolean) -> Unit,
    accountMissing: Boolean,
    passwordMissing: Boolean,
    signingIn: Boolean,
    onSubmit: () -> Unit
) {
    val palette = LocalCampusPalette.current
    // The password field's own state, so toggling it never restarts the form around it.
    var passwordVisible by remember { mutableStateOf(false) }
    val passwordTransformation = remember { PasswordVisualTransformation() }
    val accountError: (@Composable () -> Unit)? = if (accountMissing) ({
        Text("请输入学号。", style = MaterialTheme.typography.bodySmall)
    }) else null
    val passwordError: (@Composable () -> Unit)? = if (passwordMissing) ({
        Text("请输入密码。", style = MaterialTheme.typography.bodySmall)
    }) else null
    CampusCard {
        Column(Modifier.padding(CampusSpace.lg), verticalArrangement = Arrangement.spacedBy(CampusSpace.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CampusIconPlate(Icons.Outlined.Schedule, plate = palette.accentWash, tint = palette.onAccentWash)
                Spacer(Modifier.width(CampusSpace.md))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "登录研究生系统",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        "课表从研究生系统直接取回，需要连上校园网。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary
                    )
                }
            }
            OutlinedTextField(
                value = account,
                onValueChange = onAccountChange,
                enabled = !signingIn,
                label = { Text("学号") },
                placeholder = { Text("输入研究生系统学号") },
                supportingText = accountError,
                isError = accountMissing,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                colors = courseFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                enabled = !signingIn,
                label = { Text("密码") },
                placeholder = { Text("输入研究生系统密码") },
                supportingText = passwordError,
                isError = passwordMissing,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                visualTransformation = if (passwordVisible) VisualTransformation.None else passwordTransformation,
                trailingIcon = {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        enabled = !signingIn,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            modifier = Modifier.size(22.dp),
                            tint = palette.textSecondary
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                colors = courseFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("记住本机", style = MaterialTheme.typography.bodyMedium, color = palette.textPrimary)
                    Text(
                        if (remember) "学号加密保存在这台手机上，下次打开自动续上登录。"
                        else "只在本次使用，下次打开需要重新登录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary
                    )
                }
                Spacer(Modifier.width(CampusSpace.sm))
                CampusSwitch(remember, onRememberChange, label = "记住本机", enabled = !signingIn)
            }
            CampusPrimaryButton(
                title = if (signingIn) "正在登录…" else "登录并获取课表",
                onClick = onSubmit,
                busy = signingIn,
                showProgress = false
            )
        }
    }
}

/** 已经登录，但这一次没有取回课表: 说清楚现在的状态，再给一个重新取回的按钮。 */
@Composable
private fun NoTimetableCard(signingIn: Boolean, onRefresh: () -> Unit) {
    val palette = LocalCampusPalette.current
    CampusCard {
        Column(Modifier.padding(CampusSpace.lg), verticalArrangement = Arrangement.spacedBy(CampusSpace.md)) {
            Text(
                "还没有取到课表",
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                "本机没有保存课表。确认已经连上校园网，再更新一次。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textTertiary
            )
            CampusPrimaryButton(
                title = if (signingIn) "正在更新…" else "更新课表",
                onClick = onRefresh,
                busy = signingIn,
                showProgress = false
            )
        }
    }
}

@Composable
private fun LoadingCard() {
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
private fun ManageCard(
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
private fun NoticeRow(text: String, onDismiss: () -> Unit) {
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
// Course editing
// ---------------------------------------------------------------------------------------------

/**
 * One course as the editor holds it: a string per visible field, exactly as typed. The panel keeps
 * this and nothing else, so [toDraft] is the single place a form becomes something the controller
 * can be handed.
 */
internal data class CourseEditFields(
    val name: String = "",
    val teacher: String = "",
    val room: String = "",
    val weekday: String = "",
    val startPeriod: String = "",
    val endPeriod: String = "",
    val startWeek: String = "",
    val endWeek: String = "",
    val parity: WeekParity = WeekParity.ALL
)

/** A course nobody has filled in yet: 周一 第 1-2 节，第 1-16 周，每周. */
internal fun newCourseFields(): CourseEditFields = CourseEditFields(
    weekday = "1",
    startPeriod = "1",
    endPeriod = "2",
    startWeek = "1",
    endWeek = "16",
    parity = WeekParity.ALL
)

/** The same fields, filled from a course the fetch — or an earlier edit — already produced. */
internal fun courseEditFields(course: TimetableCourse): CourseEditFields = CourseEditFields(
    name = course.name,
    teacher = course.teacher,
    room = course.room,
    weekday = course.weekday.toString(),
    startPeriod = course.startPeriod.toString(),
    endPeriod = course.endPeriod.toString(),
    startWeek = course.startWeek.toString(),
    endWeek = course.endWeek.toString(),
    parity = course.parity
)

/**
 * The form as a draft, or null while something required is still empty or is not a number at all.
 *
 * Only 「填了没有」 is decided here. Whether a value is inside the range the timetable prints, and
 * whether the slot it claims is already taken, is the controller's call: it owns the whole
 * timetable and is the only one that can say why a course was refused. A number is passed on
 * exactly as typed, so 99 reaches the controller as 99 rather than being quietly pulled back into
 * range behind the user's back.
 */
internal fun CourseEditFields.toDraft(): TimetableCourseDraft? {
    val courseName = name.trim()
    if (courseName.isEmpty()) return null
    val courseWeekday = numberOrNull(weekday) ?: return null
    val firstPeriod = numberOrNull(startPeriod) ?: return null
    val lastPeriod = numberOrNull(endPeriod) ?: return null
    val firstWeek = numberOrNull(startWeek) ?: return null
    val lastWeek = numberOrNull(endWeek) ?: return null
    return TimetableCourseDraft(
        name = courseName,
        teacher = teacher.trim(),
        room = room.trim(),
        weekday = courseWeekday.toString(),
        startPeriod = firstPeriod.toString(),
        endPeriod = lastPeriod.toString(),
        startWeek = firstWeek.toString(),
        endWeek = lastWeek.toString(),
        // 单双周 is read as 单 / 双 / 每 by the model contract, so the panel sends the same words it
        // shows on its three choices.
        parity = parityLabel(parity)
    )
}

private fun numberOrNull(text: String): Int? = text.trim().toIntOrNull()

/** The 星期 hint: the range, plus which day the number currently names. */
private fun weekdayHint(text: String): String {
    val day = TIMETABLE_WEEKDAYS.getOrNull((numberOrNull(text) ?: 0) - 1)
    return if (day == null) "1-7，1 表示周一" else "1-7，现在是$day"
}

/**
 * 手动增改一门课. A 课表 taken from the 研究生系统 is allowed to be incomplete, so every value it
 * produced has to be correctable by hand, 上课地点 above all: the server's own list often leaves it
 * empty.
 *
 * Nothing is written until 保存: [onSave] hands the draft to the controller, which owns the range
 * and conflict checks and reports a refusal through the page's own message line. 取消, back and a
 * swipe down all just close the sheet, so a half-finished form never touches the timetable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseEditorSheet(
    index: Int?,
    course: TimetableCourse?,
    tint: CourseTint,
    onSave: (TimetableCourseDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val palette = LocalCampusPalette.current
    var fields by remember(course) {
        mutableStateOf(course?.let { courseEditFields(it) } ?: newCourseFields())
    }
    // A blank form is not a mistake yet, so the marks only appear once 保存 has been pressed.
    var submitted by remember(course) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CampusRadius.lgShape,
        containerColor = palette.surface,
        contentColor = palette.textPrimary
    ) {
        Column(
            // A landscape phone, a 2x font scale or an open keyboard can each make this form taller
            // than the window. The IME padding sits outside the scroll container, so the keyboard
            // takes the bottom of the sheet and the field being edited still scrolls above it.
            Modifier.fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = CampusSpace.xl, end = CampusSpace.xl, bottom = CampusSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(CampusSpace.lg)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 4.dp, height = 40.dp).background(tint.rule, CampusRadius.pillShape))
                Spacer(Modifier.width(CampusSpace.md))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (index == null) "添加课程" else "编辑课程",
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.textPrimary,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        if (index == null) "课表上没有的课可以在这里补上"
                        else "课表上不对的地方可以在这里改，改完保存",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary
                    )
                }
            }
            CourseField(
                value = fields.name,
                onValueChange = { fields = fields.copy(name = it) },
                label = "课程名",
                placeholder = "例如：高等数学",
                supporting = "课表上怎么写就怎么写",
                error = if (submitted && fields.name.isBlank()) "请填写课程名" else null
            )
            // 上课地点 is the field the server's list most often leaves empty, so it sits directly
            // under the name rather than at the bottom of a list of details.
            CourseField(
                value = fields.room,
                onValueChange = { fields = fields.copy(room = it) },
                label = "上课地点",
                placeholder = "例如：文二楼 302",
                supporting = "没有可以先留空，之后随时补上",
                leadingIcon = Icons.Outlined.Place
            )
            CourseField(
                value = fields.teacher,
                onValueChange = { fields = fields.copy(teacher = it) },
                label = "任课教师",
                placeholder = "例如：张三",
                supporting = "选填"
            )
            CourseField(
                value = fields.weekday,
                onValueChange = { fields = fields.copy(weekday = it) },
                label = "星期",
                supporting = weekdayHint(fields.weekday),
                error = if (submitted && numberOrNull(fields.weekday) == null) "请输入数字" else null,
                keyboardType = KeyboardType.Number
            )
            Row(horizontalArrangement = Arrangement.spacedBy(CampusSpace.md)) {
                CourseField(
                    value = fields.startPeriod,
                    onValueChange = { fields = fields.copy(startPeriod = it) },
                    label = "开始节次",
                    supporting = "1-13",
                    error = if (submitted && numberOrNull(fields.startPeriod) == null) "请输入数字" else null,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f)
                )
                CourseField(
                    value = fields.endPeriod,
                    onValueChange = { fields = fields.copy(endPeriod = it) },
                    label = "结束节次",
                    supporting = "1-13",
                    error = if (submitted && numberOrNull(fields.endPeriod) == null) "请输入数字" else null,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CampusSpace.md)) {
                CourseField(
                    value = fields.startWeek,
                    onValueChange = { fields = fields.copy(startWeek = it) },
                    label = "开始周",
                    supporting = "1-30",
                    error = if (submitted && numberOrNull(fields.startWeek) == null) "请输入数字" else null,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f)
                )
                CourseField(
                    value = fields.endWeek,
                    onValueChange = { fields = fields.copy(endWeek = it) },
                    label = "结束周",
                    supporting = "1-30",
                    error = if (submitted && numberOrNull(fields.endWeek) == null) "请输入数字" else null,
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                    modifier = Modifier.weight(1f)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(CampusSpace.sm)) {
                SectionLabel("单双周")
                ParityChoiceRow(selected = fields.parity, onSelect = { fields = fields.copy(parity = it) })
            }
            Text(
                "保存只检查必填项；节次、周次超出范围或与别的课冲突时，课表会说明原因，回来改一下即可。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textTertiary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(CampusSpace.sm)) {
                CampusPrimaryButton(
                    title = "保存",
                    onClick = {
                        val ready = fields.toDraft()
                        if (ready == null) submitted = true else onSave(ready)
                    },
                    showProgress = false,
                    modifier = Modifier.weight(1f)
                )
                OutlinedActionButton("取消", Icons.Outlined.Close, onDismiss, Modifier.weight(1f))
            }
            if (onDelete != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 44.dp)) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = palette.danger
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("删除这门课", color = palette.danger, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/**
 * One line of the course form. Every colour the stock Material field paints is named here rather
 * than inherited, and the range hint is part of the field at all times, so a validation mark never
 * moves the rest of the form under the user's finger.
 */
@Composable
private fun CourseField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supporting: String? = null,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    leadingIcon: ImageVector? = null
) {
    val palette = LocalCampusPalette.current
    val placeholderContent: (@Composable () -> Unit)? = if (placeholder != null) ({
        Text(placeholder, style = MaterialTheme.typography.bodyMedium)
    }) else null
    val leadingContent: (@Composable () -> Unit)? = if (leadingIcon != null) ({
        Icon(
            leadingIcon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = palette.textTertiary
        )
    }) else null
    val hint = error ?: supporting
    val supportingContent: (@Composable () -> Unit)? = if (hint != null) ({
        Text(hint, style = MaterialTheme.typography.bodySmall)
    }) else null
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholderContent,
        leadingIcon = leadingContent,
        supportingText = supportingContent,
        isError = error != null,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        colors = courseFieldColors(),
        modifier = modifier.fillMaxWidth()
    )
}

/** 每周 / 单周 / 双周 as one choice, the same single-select language the week strip uses. */
@Composable
private fun ParityChoiceRow(selected: WeekParity, onSelect: (WeekParity) -> Unit) {
    val palette = LocalCampusPalette.current
    Row(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(CampusSpace.sm)
    ) {
        WeekParity.entries.forEach { parity ->
            val isSelected = parity == selected
            Surface(
                color = if (isSelected) palette.selected else palette.muted,
                contentColor = if (isSelected) palette.onAccentWash else palette.textSecondary,
                shape = CampusRadius.mdShape,
                border = if (isSelected) BorderStroke(1.dp, palette.accentBorder) else null,
                modifier = Modifier
                    .weight(1f)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(parity) })
            ) {
                Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                    Text(parityLabel(parity), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun courseFieldColors(): TextFieldColors {
    val palette = LocalCampusPalette.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = palette.textPrimary,
        unfocusedTextColor = palette.textPrimary,
        cursorColor = palette.accent,
        selectionColors = TextSelectionColors(
            handleColor = palette.accent,
            backgroundColor = palette.accent.copy(alpha = 0.30f)
        ),
        focusedContainerColor = palette.surface,
        unfocusedContainerColor = palette.surface,
        errorContainerColor = palette.surface,
        focusedBorderColor = palette.accent,
        unfocusedBorderColor = palette.borderStrong,
        errorBorderColor = palette.danger,
        focusedLabelColor = palette.onAccentWash,
        unfocusedLabelColor = palette.textSecondary,
        errorLabelColor = palette.danger,
        focusedPlaceholderColor = palette.textTertiary,
        unfocusedPlaceholderColor = palette.textTertiary,
        focusedSupportingTextColor = palette.textTertiary,
        unfocusedSupportingTextColor = palette.textTertiary,
        errorSupportingTextColor = palette.danger,
        focusedLeadingIconColor = palette.textSecondary,
        unfocusedLeadingIconColor = palette.textTertiary
    )
}

/** Course detail: everything the 课表 holds about one course, plus its place in the term. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseDetailSheet(
    course: TimetableCourse,
    week: Int,
    /** `14:00-16:15`, or null when the source stated no clocks for this course. */
    timeSpan: String?,
    tint: CourseTint,
    onDismiss: () -> Unit,
    onEdit: () -> Unit
) {
    val palette = LocalCampusPalette.current
    val runsNow = course.runsInWeek(week)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = CampusRadius.lgShape,
        containerColor = palette.surface,
        contentColor = palette.textPrimary
    ) {
        Column(
            // A landscape phone or a 2x font scale can make this sheet taller than the window, so
            // the sheet scrolls instead of hiding its last detail row below the edge.
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = CampusSpace.xl, end = CampusSpace.xl, bottom = CampusSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(CampusSpace.lg)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 4.dp, height = 40.dp).background(tint.rule, CampusRadius.pillShape))
                Spacer(Modifier.width(CampusSpace.md))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        course.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.textPrimary,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        "${course.weekdayLabel} · ${course.periodLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary
                    )
                }
                CampusPill(
                    text = if (runsNow) "第 $week 周有课" else "第 $week 周无课",
                    ink = if (runsNow) palette.success else palette.textSecondary,
                    background = if (runsNow) palette.successWash else palette.muted
                )
            }
            Column {
                DetailRow(
                    "上课时间",
                    listOfNotNull("${course.weekdayLabel} ${course.periodLabel}", timeSpan).joinToString(" · ")
                )
                DetailRow("上课周次", course.weekLabel.trim())
                DetailRow("单双周", parityLabel(course.parity))
                DetailRow("任课教师", course.teacher.ifBlank { "未填写" })
                DetailRow("上课地点", course.room.ifBlank { "未填写" })
            }
            // A fetched course is allowed to be incomplete, so this sheet is never the end of the
            // story: every field above can be corrected by hand.
            OutlinedActionButton("编辑这门课", Icons.Outlined.Edit, onEdit, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val palette = LocalCampusPalette.current
    val labelWidth = with(LocalDensity.current) { DetailLabelColumnWidth.toDp() }
    Row(Modifier.fillMaxWidth().padding(vertical = CampusSpace.sm), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textTertiary,
            modifier = Modifier.width(labelWidth)
        )
        // Without this gap a four-character label runs straight into its value.
        Spacer(Modifier.width(CampusSpace.md))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textPrimary,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TermStartDialog(
    initialEpochDay: Long?,
    onConfirm: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalCampusPalette.current
    // The suggestion is this week's Monday, so "the term started a few weeks ago" is one tap away.
    val suggestion = initialEpochDay ?: TimetableCalendar.mondayOfWeek(LocalDate.now())
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = LocalDate.ofEpochDay(suggestion)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = DatePickerDefaults.colors(containerColor = palette.surface),
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { onConfirm(utcMillisToEpochDay(it)) }
                },
                enabled = pickerState.selectedDateMillis != null,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = palette.accent,
                    disabledContentColor = palette.textTertiary
                )
            ) { Text("保存", style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            Row {
                if (initialEpochDay != null) {
                    TextButton(onClick = onClear) {
                        Text("清除", color = palette.danger, style = MaterialTheme.typography.labelLarge)
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.accent)
                ) { Text("取消", style = MaterialTheme.typography.labelLarge) }
            }
        }
    ) {
        DatePicker(
            state = pickerState,
            showModeToggle = false,
            title = {
                Text(
                    "选择开学日期",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    modifier = Modifier.padding(start = CampusSpace.xl, top = CampusSpace.lg)
                )
            },
            headline = {
                Text(
                    "选第一周的周一，用来算当前周",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(start = CampusSpace.xl, bottom = CampusSpace.md)
                )
            },
            colors = DatePickerDefaults.colors(
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                headlineContentColor = palette.textSecondary,
                weekdayContentColor = palette.textTertiary,
                subheadContentColor = palette.textSecondary,
                navigationContentColor = palette.textSecondary,
                yearContentColor = palette.textPrimary,
                currentYearContentColor = palette.onAccentWash,
                selectedYearContentColor = palette.onAccent,
                disabledYearContentColor = palette.textTertiary,
                selectedYearContainerColor = palette.accent,
                dayContentColor = palette.textPrimary,
                disabledDayContentColor = palette.textTertiary,
                selectedDayContentColor = palette.onAccent,
                selectedDayContainerColor = palette.accent,
                todayContentColor = palette.onAccentWash,
                todayDateBorderColor = palette.accentBorder,
                dayInSelectionRangeContentColor = palette.onAccentWash,
                dayInSelectionRangeContainerColor = palette.accentWash,
                dividerColor = palette.border
            )
        )
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
private fun OutlinedActionButton(
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

/** One calm course colour: a soft fill, its ink, and a stronger rule for the leading edge. */
private class CourseTint(val fill: Color, val ink: Color, val rule: Color)

private const val COURSE_TINT_COUNT = 6

/**
 * Six course colours built only from palette tokens, so both themes stay on-language: four are the
 * washes the palette already owns, and the other two mix neighbouring washes so adjacent cells still
 * never look alike while the accent stays the only saturated colour. The ink is the hue softened
 * toward the theme's own text colour — that is what stops six course tints from reading as six
 * status colours — and the rule is the fill-to-ink midpoint, so no tint needs a third hex value.
 */
private fun tintAt(palette: CampusPalette, slot: Int): CourseTint {
    val index = ((slot % COURSE_TINT_COUNT) + COURSE_TINT_COUNT) % COURSE_TINT_COUNT
    return when (index) {
        0 -> courseTint(palette, palette.accentWash, palette.onAccentWash)
        1 -> courseTint(palette, palette.successWash, palette.success)
        2 -> courseTint(palette, palette.warningWash, palette.warning)
        3 -> courseTint(
            palette,
            lerp(palette.accentWash, palette.dangerWash, 0.5f),
            lerp(palette.onAccentWash, palette.danger, 0.5f)
        )
        4 -> courseTint(palette, palette.dangerWash, palette.danger)
        else -> courseTint(
            palette,
            lerp(palette.successWash, palette.accentWash, 0.5f),
            lerp(palette.success, palette.onAccentWash, 0.5f)
        )
    }
}

private fun courseTint(palette: CampusPalette, fill: Color, hue: Color): CourseTint {
    val ink = lerp(hue, palette.textPrimary, 0.35f)
    return CourseTint(fill, ink, lerp(fill, ink, 0.45f))
}

private fun tintFor(
    palette: CampusPalette,
    course: TimetableCourse,
    timetable: Timetable?
): CourseTint {
    val slots = timetable?.let { TimetableCourseSlots.assign(it.courses, COURSE_TINT_COUNT) }
    return tintAt(palette, slots?.get(course) ?: 0)
}

private fun parityLabel(parity: WeekParity): String = when (parity) {
    WeekParity.ALL -> "每周"
    WeekParity.ODD -> "单周"
    WeekParity.EVEN -> "双周"
}

private fun weekSubtitle(state: TimetableUiState): String {
    val start = state.termStartEpochDay ?: return "未设置开学日期"
    val dates = TimetableCalendar.weekDates(start, state.selectedWeek)
    val first = dates.first()
    val last = dates.last()
    return "${first.monthValue}月${first.dayOfMonth}日 - ${last.monthValue}月${last.dayOfMonth}日"
}

private fun formatDate(millis: Long): String =
    android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", millis).toString()

private fun utcMillisToEpochDay(millis: Long): Long =
    java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
