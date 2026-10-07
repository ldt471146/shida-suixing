package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import cn.gxnu.campus.core.TimetableDay
import cn.gxnu.campus.core.TimetableGridLayout
import cn.gxnu.campus.ui.common.CampusCard
import cn.gxnu.campus.ui.common.SectionLabel
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette

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

/** The weekday × period grid for one teaching week. */
@Composable
internal fun WeekGridCard(
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
