package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.gxnu.campus.core.TIMETABLE_WEEKDAYS
import cn.gxnu.campus.core.TimetableCourse
import cn.gxnu.campus.core.TimetableCourseDraft
import cn.gxnu.campus.core.WeekParity
import cn.gxnu.campus.ui.common.CampusPrimaryButton
import cn.gxnu.campus.ui.common.SectionLabel
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette

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
internal fun CourseEditorSheet(
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
internal fun courseFieldColors(): TextFieldColors {
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
