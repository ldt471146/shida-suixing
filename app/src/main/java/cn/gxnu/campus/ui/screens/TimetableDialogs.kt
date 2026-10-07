package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.gxnu.campus.core.TimetableCalendar
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TermStartDialog(
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

/** 删除本机课表: the confirmation, kept beside the date dialog as the page's other modal. */
@Composable
internal fun DeleteTimetableDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val palette = LocalCampusPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
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
            TextButton(onClick = onConfirm) { Text("删除", color = palette.danger, style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = palette.accent)
            ) {
                Text("取消", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}
