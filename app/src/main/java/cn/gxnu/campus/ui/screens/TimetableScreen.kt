package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableCourse
import cn.gxnu.campus.core.TimetableCourseSlots
import cn.gxnu.campus.ui.TimetableActions
import cn.gxnu.campus.ui.TimetableUiState
import cn.gxnu.campus.ui.common.CampusPageHeader
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette

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
        DeleteTimetableDialog(
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                confirmingDelete = false
                actions.deleteTimetable()
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

