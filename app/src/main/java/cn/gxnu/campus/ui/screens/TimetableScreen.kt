package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
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
import cn.gxnu.campus.ui.common.CampusIconButton
import cn.gxnu.campus.ui.common.CampusPageHeader
import cn.gxnu.campus.ui.common.EntranceGate
import cn.gxnu.campus.ui.common.entrance
import cn.gxnu.campus.ui.common.rememberPageEntrance
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette

/**
 * 课表：一周的课，和切换教学周的那一条。
 *
 * 页面就是周次切换 + 网格两件事；「课表管理」那一整张卡片已经收进右上角的图标里，今日课程也
 * 已经归首页 —— 两个页面各答一个问题，谁也不替谁说半句。
 */
@Composable
fun TimetableScreen(
    state: TimetableUiState,
    actions: TimetableActions,
    gate: EntranceGate,
    onBack: () -> Unit,
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
    var managing by remember { mutableStateOf(false) }
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
    val entrance = rememberPageEntrance(gate, "timetable", itemCount = 3)

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
        item(key = "header") {
            Box(Modifier.entrance(entrance, 0)) {
                CampusPageHeader(
                    title = "课表",
                    subtitle = when {
                        timetable == null -> ""
                        else -> "共 ${timetable.courseCount} 门课程 · 更新于 ${formatDate(timetable.recognizedAtMillis)}"
                    },
                    leading = {
                        CampusIconButton(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回功能",
                            onClick = onBack
                        )
                    },
                    trailing = if (timetable == null) null else {
                        {
                            CampusIconButton(
                                Icons.Outlined.MoreVert,
                                contentDescription = "课表管理",
                                onClick = { managing = true }
                            )
                        }
                    }
                )
            }
        }
        if (notice != null) {
            item(key = "notice") {
                NoticeRow(notice) { actions.clearMessage(state.messageId) }
            }
        }
        item(key = "body") {
            Box(Modifier.entrance(entrance, 1)) {
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
                        onSelectWeek = actions::selectWeek,
                        onCurrentWeek = actions::showCurrentWeek,
                        onSetTermStart = { settingTermStart = true },
                        onCourse = { detail = it }
                    )
                    else -> NoTimetableCard(signingIn = state.signingIn, onRefresh = actions::refresh)
                }
            }
        }
    }

    if (managing && timetable != null) {
        TimetableManageSheet(
            account = state.account,
            signingIn = state.signingIn,
            onRefresh = {
                managing = false
                actions.refresh()
            },
            onSignIn = {
                managing = false
                loginRequested = true
            },
            onSignOut = actions::signOut,
            onDelete = {
                managing = false
                confirmingDelete = true
            },
            onAddCourse = {
                managing = false
                editor = CourseEditorRequest(index = null, course = null)
            },
            onSetTermStart = {
                managing = false
                settingTermStart = true
            },
            onDismiss = { managing = false }
        )
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
