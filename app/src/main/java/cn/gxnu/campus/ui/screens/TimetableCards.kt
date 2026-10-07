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
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.gxnu.campus.ui.TimetableUiState
import cn.gxnu.campus.ui.common.CampusCard
import cn.gxnu.campus.ui.common.CampusPill
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette

/** Week strip and the term start the whole calculation hangs off. Compact on purpose: it is the one
 * card the 课表 page keeps above the grid. */
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
                // 每个周次有自己的 key：滚动、跳周和重新取回课表时，条目不会被当成「换了一批」。
                items(count = state.weekCount, key = { index -> index + 1 }) { index ->
                    val week = index + 1
                    WeekChip(
                        week = week,
                        selected = week == state.selectedWeek,
                        current = state.termStartEpochDay != null && week == state.currentWeek,
                        onClick = { onSelectWeek(week) }
                    )
                }
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
