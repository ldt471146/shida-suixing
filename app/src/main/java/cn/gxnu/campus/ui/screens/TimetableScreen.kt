package cn.gxnu.campus.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.gxnu.campus.core.TIMETABLE_PERIOD_NONE
import cn.gxnu.campus.core.TIMETABLE_WEEKDAYS
import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableBlock
import cn.gxnu.campus.core.TimetableCalendar
import cn.gxnu.campus.core.TimetableCourse
import cn.gxnu.campus.core.TimetableCourseSlots
import cn.gxnu.campus.core.TimetableDay
import cn.gxnu.campus.core.TimetableGridLayout
import cn.gxnu.campus.core.WeekParity
import cn.gxnu.campus.network.TimetableVisionFailure
import cn.gxnu.campus.ui.TimetableActions
import cn.gxnu.campus.ui.TimetableUiState
import cn.gxnu.campus.ui.common.CampusCard
import cn.gxnu.campus.ui.common.CampusIconPlate
import cn.gxnu.campus.ui.common.CampusPageHeader
import cn.gxnu.campus.ui.common.CampusPill
import cn.gxnu.campus.ui.common.CampusPrimaryButton
import cn.gxnu.campus.ui.common.SectionLabel
import cn.gxnu.campus.ui.theme.CampusPalette
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val GridCellHeight = 66.dp
private val GridCellGap = 3.dp
private val GridPeriodWidth = 30.dp
private val GridHeaderHeight = 42.dp
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
// The 无节次 row: a course the recognition placed in no 节次 at all still needs a row to be drawn in.
private const val GridNoPeriodLabel = "无节次"
// A cell is a fixed 66dp tall, so a name past ~1.3x would grow out of its row and overlap the next
// one. Only the grid caps its own text scale; the rest of the screen keeps the system setting.
private const val GridMaxFontScale = 1.3f
// The preview keeps the photo's own shape and only caps its height, so a tall frame cannot push the
// rest of the page away while a wide one is still shown whole.
private val PreviewImageMaxHeight = 320.dp
// Label columns are stated in sp rather than dp so they grow with the font scale instead of
// ellipsising a real value ("11-12", "上课时间") once the user turns the text size up. Every row of a
// list shares one width, so the column beside it stays aligned from row to row. The 今日 period
// column holds a whole label now — "第3-4节", "无节次" — so it is sized for the widest of them.
private val TodayPeriodColumnWidth = 56.sp
private val DetailLabelColumnWidth = 72.sp

/**
 * 课表: pick or shoot a timetable photo, recognise it through the vision service, then read it back
 * as a teaching-week grid. Stateless apart from the picker plumbing and the transient sheets, so the
 * host owns all the state that matters.
 */
@Composable
fun TimetableScreen(
    state: TimetableUiState,
    actions: TimetableActions,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The key draft stays out of saved state on purpose: it is a secret, not form data to restore.
    var keyDraft by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var settingTermStart by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<TimetableCourse?>(null) }
    var captureTarget by remember { mutableStateOf<Uri?>(null) }

    fun load(uri: Uri, discardAfterwards: Boolean) {
        if (preparing) return
        preparing = true
        notice = null
        scope.launch {
            when (val result = loadPreparedImage(context, uri, discardAfterwards)) {
                is ImageLoad.Ready -> {
                    preview = result.prepared.preview
                    actions.useImage(result.prepared.image)
                }
                is ImageLoad.Failed -> notice = result.message
            }
            preparing = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) load(uri, discardAfterwards = false) else notice = "没有选择图片。"
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val target = captureTarget
        captureTarget = null
        if (captured && target != null) load(target, discardAfterwards = true)
        else {
            target?.let { TimetableImageLoader.discardCaptureTarget(context, it) }
            notice = "已取消拍照。"
        }
    }

    val timetable = state.timetable
    val message = state.message ?: notice
    val busy = preparing || state.recognizing
    val captureSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    // 今日 is only claimed when the grid is genuinely showing the week today falls in.
    val showingCurrentWeek = state.termStartEpochDay != null && state.selectedWeek == state.currentWeek

    fun shoot() {
        val target = TimetableImageLoader.createCaptureTarget(context)
        if (target == null) {
            notice = "无法创建拍照任务，请改用相册里的照片。"
        } else {
            captureTarget = target
            try {
                camera.launch(target)
            } catch (_: Exception) {
                captureTarget = null
                TimetableImageLoader.discardCaptureTarget(context, target)
                notice = "这台设备没有可用的相机应用。"
            }
        }
    }

    fun pickImage() {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
                subtitle = if (timetable == null) "拍照识别，生成能按周查看的课程表"
                else "共 ${timetable.courseCount} 门课程 · 识别于 ${formatDate(timetable.recognizedAtMillis)}"
            )
        }
        state.failure?.let { failure ->
            item {
                FailureCard(
                    failure = failure,
                    message = state.message,
                    canRetry = state.canRetry,
                    busy = busy,
                    onRetry = actions::retry,
                    onPickAnother = ::pickImage
                )
            }
        }
        // A failure is already spelled out by its own card; repeating it here would double it up.
        if (state.failure == null && !message.isNullOrBlank()) {
            item {
                NoticeRow(message) {
                    notice = null
                    actions.clearMessage(state.messageId)
                }
            }
        }
        // Once a timetable is on screen the photo has done its job, so it stops taking up the top of
        // the page. It stays visible while a recognition runs, before the first success, and after a
        // failure — the three moments when seeing which image was sent actually matters. A running
        // recognition keeps the card even when this composition holds no decoded bitmap (a rotation
        // drops the one above): the card is where 取消 lives, and a page that is waiting on a
        // 40-90 second request must never be left with no way out of it.
        val showPreview = state.recognizing ||
            (preview != null && (preparing || timetable == null || state.failure != null))
        if (showPreview) {
            item {
                PreviewCard(
                    preview = preview,
                    preparing = preparing,
                    recognizing = state.recognizing,
                    failed = state.failure != null,
                    onCancel = actions::cancelRecognition
                )
            }
        }
        if (!state.builtInKey) {
            item {
                ApiKeyCard(
                    keyConfigured = state.keyConfigured,
                    keyHint = state.keyHint,
                    draft = keyDraft,
                    onDraftChange = { keyDraft = it },
                    visible = keyVisible,
                    onVisibleChange = { keyVisible = it },
                    onSave = {
                        actions.saveApiKey(keyDraft)
                        keyDraft = ""
                    },
                    onClear = actions::clearApiKey
                )
            }
        }
        item {
            when {
                state.restoring -> LoadingCard()
                timetable == null -> EmptyTimetableCard(
                    onPick = ::pickImage,
                    onShoot = if (captureSupported) ::shoot else null,
                    busy = busy
                )
                else -> TimetableSection(
                    state = state,
                    timetable = timetable,
                    showingCurrentWeek = showingCurrentWeek,
                    busy = busy,
                    onSelectWeek = actions::selectWeek,
                    onCurrentWeek = actions::showCurrentWeek,
                    onSetTermStart = { settingTermStart = true },
                    onCourse = { detail = it }
                )
            }
        }
        // The empty state owns its own import actions, so this card only appears once a timetable
        // exists — otherwise the same two buttons would be offered twice on one screen.
        if (timetable != null) {
            item {
                ManageCard(
                    hasPreview = state.canRetry,
                    busy = busy,
                    captureSupported = captureSupported,
                    onPick = ::pickImage,
                    onShoot = ::shoot,
                    onRetry = actions::retry,
                    onDelete = { confirmingDelete = true }
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
                    "只会删除这台手机上保存的课表，开学日期会保留。图片不会上传到别处。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    preview = null
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
            tint = tintFor(LocalCampusPalette.current, course, timetable),
            onDismiss = { detail = null }
        )
    }
}

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
private fun TodayCourseRow(course: TimetableCourse, tint: CourseTint, onClick: () -> Unit) {
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
        // One line, and the whole label off the course itself: splitting it into a bare "3" plus a
        // "节" underneath would leave a 无节次 course showing a stray 节 under a number it has not got.
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
                course.detailLabel.ifBlank { "教师、教室未识别" },
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
    todayWeekday: Int,
    showingCurrentWeek: Boolean,
    slots: Map<TimetableCourse, Int>,
    onCourse: (TimetableCourse) -> Unit
) {
    val palette = LocalCampusPalette.current
    val grid = remember(timetable, week) { TimetableGridLayout.build(timetable, week) }
    val horizontal = rememberScrollState()
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
                    // The 无节次 row is a row, not a 节次, so it never counts towards the period total.
                    "${grid.days.size} 天 · 第 ${grid.periods.count { it != TIMETABLE_PERIOD_NONE }} 节",
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
                                Row(Modifier.horizontalScroll(horizontal)) {
                                    Spacer(Modifier.width(GridPeriodWidth))
                                    grid.days.forEach { day ->
                                        Spacer(Modifier.width(GridCellGap))
                                        DayHeader(
                                            weekday = day.weekday,
                                            isToday = showingCurrentWeek && day.weekday == todayWeekday,
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
                                                PeriodGutterLabel(period)
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

/** The 节次 gutter label: a number, or 无节次 wrapped onto two lines in a gutter this narrow. */
@Composable
private fun PeriodGutterLabel(period: Int) {
    val palette = LocalCampusPalette.current
    val noPeriod = period == TIMETABLE_PERIOD_NONE
    Text(
        gutterPeriodLabel(period),
        // The gutter is 30dp wide, so 无节次 is set a step smaller than the digits beside it and is
        // allowed to wrap onto two lines rather than ellipsise away its last character once the
        // user turns the font scale up.
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = if (noPeriod) 8.sp else 11.sp,
            lineHeight = if (noPeriod) 10.sp else 14.sp
        ),
        color = palette.textTertiary,
        textAlign = TextAlign.Center,
        maxLines = if (noPeriod) 2 else 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun DayHeader(weekday: Int, isToday: Boolean, width: Dp) {
    val palette = LocalCampusPalette.current
    Surface(
        Modifier.width(width).height(GridHeaderHeight),
        color = if (isToday) palette.accentWash else palette.muted,
        shape = CampusRadius.smShape,
        border = if (isToday) BorderStroke(1.dp, palette.accentBorder) else null
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                TIMETABLE_WEEKDAYS[weekday - 1],
                style = MaterialTheme.typography.labelMedium,
                color = if (isToday) palette.onAccentWash else palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // The second line is always present so every header keeps the same height.
            Text(
                if (isToday) "今天" else " ",
                style = MaterialTheme.typography.labelSmall,
                color = palette.onAccentWash,
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
                is TimetableBlock.Free -> Box(
                    Modifier.fillMaxWidth().height(GridCellHeight)
                        .background(
                            if (isToday) palette.accentWash.copy(alpha = 0.45f) else palette.muted.copy(alpha = 0.55f),
                            CampusRadius.smShape
                        )
                )
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
 * The 节次 gutter label for one row. [TIMETABLE_PERIOD_NONE] is the 无节次 row — a course the
 * recognition placed in no 节次 — and every other row shows its own number. The wording matches
 * [TimetableCourse.periodLabel], which is the same label for a whole course.
 */
internal fun gutterPeriodLabel(period: Int): String =
    if (period == TIMETABLE_PERIOD_NONE) GridNoPeriodLabel else "$period"

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
    Row(
        modifier
            .background(tint.fill, CampusRadius.smShape)
            .clickable(role = Role.Button, onClick = { onCourse(course) })
            .padding(vertical = 5.dp)
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(tint.rule, CampusRadius.pillShape))
        Column(
            Modifier.weight(1f).padding(start = 4.dp, end = 3.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                course.name,
                // One step below body text: a five-day week leaves about 16 characters of width per
                // cell, which is three per line at this size rather than one. The line count follows
                // the column width, so a seven-column cell stops at two lines instead of stacking
                // four and losing the name to the ellipsis.
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = tint.ink,
                fontWeight = FontWeight.Medium,
                maxLines = dayNameMaxLines(dayCount, course.periodSpan),
                overflow = TextOverflow.Ellipsis
            )
            if (course.room.isNotBlank()) {
                Text(
                    course.room,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 10.sp),
                    color = tint.ink.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Import, key and state cards
// ---------------------------------------------------------------------------------------------

@Composable
private fun PreviewCard(
    preview: Bitmap?,
    preparing: Boolean,
    recognizing: Boolean,
    failed: Boolean,
    onCancel: () -> Unit
) {
    val palette = LocalCampusPalette.current
    CampusCard {
        Column(Modifier.padding(CampusSpace.lg), verticalArrangement = Arrangement.spacedBy(CampusSpace.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("待识别的图片")
                Spacer(Modifier.weight(1f))
                when {
                    preparing -> CampusPill("正在压缩", palette.textSecondary, palette.muted)
                    recognizing -> CampusPill("识别中", palette.onAccentWash, palette.accentWash)
                    // The pill follows the last attempt, so a failed read never claims to have worked.
                    failed -> CampusPill("这张没有识别成功", palette.danger, palette.dangerWash)
                    else -> CampusPill("已识别", palette.success, palette.successWash)
                }
            }
            preview?.let { bitmap ->
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                // A timetable photo is usually wider than tall, so cropping it into a 160dp strip
                // hid the very thing this card exists to show: which image was actually sent. The
                // box now takes the photo's own shape, capped so a tall frame cannot push the rest
                // of the page down, and Fit keeps the whole frame visible either way.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val height = if (bitmap.width > 0 && bitmap.height > 0) {
                        (maxWidth / (bitmap.width.toFloat() / bitmap.height)).coerceAtMost(PreviewImageMaxHeight)
                    } else {
                        PreviewImageMaxHeight
                    }
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(height)
                            .background(palette.muted, CampusRadius.mdShape)
                    )
                }
            }
            if (preparing || recognizing) {
                Column(verticalArrangement = Arrangement.spacedBy(CampusSpace.sm)) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = palette.accent,
                        trackColor = palette.muted
                    )
                    Text(
                        if (preparing) "正在压缩图片…" else "正在识别课表，通常需要几秒到十几秒。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                }
            }
            // A recognition can run for a minute or more. The cancel action sits with the progress it
            // stops, not in 课表管理, because the wait is what the user is looking at.
            if (recognizing) {
                OutlinedActionButton("取消", Icons.Outlined.Close, onCancel, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun EmptyTimetableCard(onPick: () -> Unit, onShoot: (() -> Unit)?, busy: Boolean) {
    val palette = LocalCampusPalette.current
    CampusCard {
        Column(Modifier.padding(CampusSpace.lg), verticalArrangement = Arrangement.spacedBy(CampusSpace.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CampusIconPlate(Icons.Outlined.Schedule, plate = palette.accentWash, tint = palette.onAccentWash)
                Spacer(Modifier.width(CampusSpace.md))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "还没有课表",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        "拍一张课程表照片，AI 识别后生成周课表",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(CampusSpace.sm)) {
                HintLine("照片越清晰、越正对，识别越准确；看不清的内容不会凭空补全。")
                HintLine("识别结果只保存在这台手机上，可以随时重新识别或删除。")
                HintLine("识别后可以按周查看，并自动定位到当前周。")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CampusSpace.sm)) {
                CampusPrimaryButton(
                    title = "选择图片",
                    onClick = onPick,
                    busy = busy,
                    showProgress = false,
                    modifier = Modifier.weight(1f)
                )
                if (onShoot != null) {
                    OutlinedActionButton("拍照", Icons.Outlined.PhotoCamera, onShoot, Modifier.weight(1f), enabled = !busy)
                }
            }
        }
    }
}

@Composable
private fun HintLine(text: String) {
    val palette = LocalCampusPalette.current
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 6.dp).size(4.dp).background(palette.borderStrong, CircleShape)
        )
        Spacer(Modifier.width(CampusSpace.sm))
        Text(text, style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
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

/** 课表管理: replace the photo, recognise it again, or take the timetable off this device. */
@Composable
private fun ManageCard(
    hasPreview: Boolean,
    busy: Boolean,
    captureSupported: Boolean,
    onPick: () -> Unit,
    onShoot: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = LocalCampusPalette.current
    CampusCard {
        Column(Modifier.padding(CampusSpace.lg), verticalArrangement = Arrangement.spacedBy(CampusSpace.md)) {
            SectionLabel("课表管理")
            Row(horizontalArrangement = Arrangement.spacedBy(CampusSpace.sm)) {
                OutlinedActionButton("换一张图片", Icons.Outlined.PhotoLibrary, onPick, Modifier.weight(1f), enabled = !busy)
                if (captureSupported) {
                    OutlinedActionButton("拍照", Icons.Outlined.PhotoCamera, onShoot, Modifier.weight(1f), enabled = !busy)
                }
            }
            CampusPrimaryButton(
                title = "重新识别",
                onClick = onRetry,
                enabled = hasPreview,
                busy = busy,
                showProgress = false
            )
            if (!hasPreview) {
                Text(
                    "重新识别会用最近一次选择的图片再跑一遍；先换一张图片也可以。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textTertiary
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.heightIn(min = 44.dp)) {
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
private fun ApiKeyCard(
    keyConfigured: Boolean,
    keyHint: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    val palette = LocalCampusPalette.current
    CampusCard {
        Column(Modifier.padding(CampusSpace.lg), verticalArrangement = Arrangement.spacedBy(CampusSpace.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CampusIconPlate(Icons.Outlined.Key)
                Spacer(Modifier.width(CampusSpace.md))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "识别服务",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary
                    )
                    Text(
                        if (keyConfigured) "已保存 $keyHint" else "这台设备的安装包没有内置识别服务",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary
                    )
                }
            }
            Text(
                "API Key 使用 Android Keystore 加密保存在本机，不会写进源码或安装包；识别时只有课表图片会发送到识别服务。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                singleLine = true,
                label = { Text(if (keyConfigured) "更换 API Key" else "API Key") },
                placeholder = { Text("sk-…") },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { onVisibleChange(!visible) }) {
                        Icon(
                            if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (visible) "隐藏 API Key" else "显示 API Key",
                            modifier = Modifier.size(18.dp),
                            tint = palette.textSecondary
                        )
                    }
                },
                // The field is a stock Material component, so every colour it paints is named here
                // rather than inherited: a key field is never the place to discover a dark-mode gap.
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary,
                    cursorColor = palette.accent,
                    selectionColors = TextSelectionColors(
                        handleColor = palette.accent,
                        backgroundColor = palette.accent.copy(alpha = 0.30f)
                    ),
                    focusedBorderColor = palette.accent,
                    unfocusedBorderColor = palette.borderStrong,
                    focusedLabelColor = palette.onAccentWash,
                    unfocusedLabelColor = palette.textSecondary,
                    focusedPlaceholderColor = palette.textTertiary,
                    unfocusedPlaceholderColor = palette.textTertiary,
                    focusedTrailingIconColor = palette.textSecondary,
                    unfocusedTrailingIconColor = palette.textTertiary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(CampusSpace.sm)) {
                CampusPrimaryButton(
                    title = if (keyConfigured) "保存新的 API Key" else "保存到本机",
                    onClick = onSave,
                    enabled = draft.isNotBlank(),
                    showProgress = false,
                    modifier = Modifier.weight(1f)
                )
                if (keyConfigured) {
                    OutlinedActionButton("删除 Key", Icons.Outlined.Delete, onClear, Modifier.weight(1f))
                }
            }
        }
    }
}

/** Concrete reason, concrete next step — never a bare "识别失败". */
@Composable
private fun FailureCard(
    failure: TimetableVisionFailure,
    message: String?,
    canRetry: Boolean,
    busy: Boolean,
    onRetry: () -> Unit,
    onPickAnother: () -> Unit
) {
    val palette = LocalCampusPalette.current
    val notATimetable = failure == TimetableVisionFailure.NOT_A_TIMETABLE
    val ink = if (notATimetable) palette.warning else palette.danger
    val background = if (notATimetable) palette.warningWash else palette.dangerWash
    Surface(
        color = background,
        shape = CampusRadius.lgShape,
        border = BorderStroke(1.dp, ink.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(CampusSpace.lg), verticalArrangement = Arrangement.spacedBy(CampusSpace.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (notATimetable) Icons.Outlined.Image else Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = ink
                )
                Spacer(Modifier.width(CampusSpace.sm))
                Text(
                    failureTitle(failure),
                    style = MaterialTheme.typography.titleSmall,
                    color = ink,
                    modifier = Modifier.semantics { heading() }
                )
            }
            Text(
                message ?: "识别失败，请重试。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
            if (notATimetable) {
                Text(
                    "把整张课表拍进画面，保持水平、光线均匀，再试一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textTertiary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CampusSpace.sm)) {
                if (canRetry) {
                    OutlinedActionButton("重新识别", Icons.Outlined.Refresh, onRetry, Modifier.weight(1f), enabled = !busy)
                }
                OutlinedActionButton("换一张图片", Icons.Outlined.PhotoLibrary, onPickAnother, Modifier.weight(1f), enabled = !busy)
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

/** Course detail: everything the recognition read out of the photo, plus its place in the term. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseDetailSheet(
    course: TimetableCourse,
    week: Int,
    tint: CourseTint,
    onDismiss: () -> Unit
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
                DetailRow("上课时间", "${course.weekdayLabel} ${course.periodLabel}")
                DetailRow("上课周次", course.weekLabel.trim())
                DetailRow("单双周", parityLabel(course.parity))
                DetailRow("任课教师", course.teacher.ifBlank { "未识别" })
                DetailRow("上课地点", course.room.ifBlank { "未识别" })
            }
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

private fun failureTitle(failure: TimetableVisionFailure): String = when (failure) {
    TimetableVisionFailure.NOT_A_TIMETABLE -> "没识别到课表"
    TimetableVisionFailure.MISSING_KEY -> "还没有可用的识别服务"
    TimetableVisionFailure.INVALID_REQUEST -> "这次请求被拒绝了"
    TimetableVisionFailure.UNAUTHORIZED -> "识别服务拒绝了当前密钥"
    TimetableVisionFailure.QUOTA -> "识别额度不足"
    TimetableVisionFailure.RATE_LIMITED -> "请求太频繁了"
    TimetableVisionFailure.TIMEOUT -> "识别超时"
    TimetableVisionFailure.NO_NETWORK -> "网络不可用"
    TimetableVisionFailure.TOO_LARGE -> "图片太大"
    TimetableVisionFailure.SERVER -> "识别服务暂时不可用"
    TimetableVisionFailure.EMPTY_RESPONSE -> "AI 没有返回内容"
    TimetableVisionFailure.MALFORMED_RESPONSE -> "识别结果无法使用"
}

private fun formatDate(millis: Long): String =
    android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", millis).toString()

private fun utcMillisToEpochDay(millis: Long): Long =
    java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()

private sealed interface ImageLoad {
    data class Ready(val prepared: PreparedTimetableImage) : ImageLoad
    data class Failed(val message: String) : ImageLoad
}

/** Decoding is blocking and memory hungry, so it runs off the main thread and never throws out. */
private suspend fun loadPreparedImage(context: Context, uri: Uri, discardAfterwards: Boolean): ImageLoad =
    withContext(Dispatchers.IO) {
        try {
            ImageLoad.Ready(TimetableImageLoader.prepare(context.contentResolver, uri))
        } catch (_: OutOfMemoryError) {
            ImageLoad.Failed("这张图片太大，手机无法处理，请换一张分辨率低一些的照片。")
        } catch (failure: ImagePreparationException) {
            ImageLoad.Failed(failure.message ?: "这张图片无法读取，请换一张试试。")
        } catch (_: Exception) {
            ImageLoad.Failed("这张图片无法读取，请换一张试试。")
        } finally {
            if (discardAfterwards) TimetableImageLoader.discardCaptureTarget(context, uri)
        }
    }
