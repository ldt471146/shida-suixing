package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.gxnu.campus.core.TimetableCourse
import cn.gxnu.campus.ui.common.CampusPill
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette

// Label columns are stated in sp rather than dp so they grow with the font scale instead of
// ellipsising a real value ("11-12", "上课时间") once the user turns the text size up. Every row of a
// list shares one width, so the column beside it stays aligned from row to row.
private val DetailLabelColumnWidth = 72.sp

/** Course detail: everything the 课表 holds about one course, plus its place in the term. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseDetailSheet(
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
