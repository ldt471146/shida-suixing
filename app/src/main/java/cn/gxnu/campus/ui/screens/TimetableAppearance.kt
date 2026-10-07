package cn.gxnu.campus.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableCalendar
import cn.gxnu.campus.core.TimetableCourse
import cn.gxnu.campus.core.TimetableCourseSlots
import cn.gxnu.campus.core.WeekParity
import cn.gxnu.campus.ui.TimetableUiState
import cn.gxnu.campus.ui.theme.CampusPalette
import java.time.ZoneOffset

/** One calm course colour: a soft fill, its ink, and a stronger rule for the leading edge. */
internal class CourseTint(val fill: Color, val ink: Color, val rule: Color)

internal const val COURSE_TINT_COUNT = 6

/**
 * Six course colours built only from palette tokens, so both themes stay on-language: four are the
 * washes the palette already owns, and the other two mix neighbouring washes so adjacent cells still
 * never look alike while the accent stays the only saturated colour. The ink is the hue softened
 * toward the theme's own text colour — that is what stops six course tints from reading as six
 * status colours — and the rule is the fill-to-ink midpoint, so no tint needs a third hex value.
 */
internal fun tintAt(palette: CampusPalette, slot: Int): CourseTint {
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

internal fun tintFor(
    palette: CampusPalette,
    course: TimetableCourse,
    timetable: Timetable?
): CourseTint {
    val slots = timetable?.let { TimetableCourseSlots.assign(it.courses, COURSE_TINT_COUNT) }
    return tintAt(palette, slots?.get(course) ?: 0)
}

internal fun parityLabel(parity: WeekParity): String = when (parity) {
    WeekParity.ALL -> "每周"
    WeekParity.ODD -> "单周"
    WeekParity.EVEN -> "双周"
}

internal fun weekSubtitle(state: TimetableUiState): String {
    val start = state.termStartEpochDay ?: return "未设置开学日期"
    val dates = TimetableCalendar.weekDates(start, state.selectedWeek)
    val first = dates.first()
    val last = dates.last()
    return "${first.monthValue}月${first.dayOfMonth}日 - ${last.monthValue}月${last.dayOfMonth}日"
}

internal fun formatDate(millis: Long): String =
    android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", millis).toString()

internal fun utcMillisToEpochDay(millis: Long): Long =
    java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
