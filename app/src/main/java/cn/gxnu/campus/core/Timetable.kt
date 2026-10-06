package cn.gxnu.campus.core

import java.time.LocalDate

/** Weekday numbering used by the model contract and the grid: 1 = 周一 … 7 = 周日. */
val TIMETABLE_WEEKDAYS: List<String> = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

const val TIMETABLE_MAX_COURSES = 200
const val TIMETABLE_MAX_PERIODS = 20
const val TIMETABLE_MAX_WEEKS = 30
const val TIMETABLE_MAX_TEXT_LENGTH = 64

/** 单双周 is printed inside the week range on Chinese timetables, so it is kept with it. */
enum class WeekParity(val label: String) { ALL(""), ODD("（单周）"), EVEN("（双周）") }

/** One course occurrence: a weekday, a contiguous period span, and the weeks it runs in. */
data class TimetableCourse(
    val name: String,
    val teacher: String,
    val room: String,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val startWeek: Int,
    val endWeek: Int,
    val parity: WeekParity = WeekParity.ALL
) {
    val periodSpan: Int get() = endPeriod - startPeriod + 1
    val weekdayLabel: String get() = TIMETABLE_WEEKDAYS[weekday - 1]
    val periodLabel: String get() = if (startPeriod == endPeriod) "第${startPeriod}节" else "第${startPeriod}-${endPeriod}节"
    val weekLabel: String
        get() = if (startWeek == endWeek) "第${startWeek}周${parity.label}" else "$startWeek-$endWeek 周${parity.label}"
    val detailLabel: String get() = listOf(teacher, room).filter { it.isNotBlank() }.joinToString(" · ")

    /** Whether this course is actually taught in teaching week [week]; 单双周 is honoured here. */
    fun runsInWeek(week: Int): Boolean =
        week in startWeek..endWeek && when (parity) {
            WeekParity.ALL -> true
            WeekParity.ODD -> week % 2 == 1
            WeekParity.EVEN -> week % 2 == 0
        }
}

data class Timetable(
    val term: String,
    val courses: List<TimetableCourse>,
    val recognizedAtMillis: Long
) {
    val courseCount: Int get() = courses.size

    /** The last teaching week any course reaches, so the week switcher never offers an empty tail. */
    val weekCount: Int get() = courses.maxOfOrNull { it.endWeek }?.coerceIn(1, TIMETABLE_MAX_WEEKS) ?: 1

    /** The distinct courses taught in [week], in timetable order. */
    fun coursesInWeek(week: Int): List<TimetableCourse> =
        courses.filter { it.runsInWeek(week) }.sortedWith(compareBy({ it.weekday }, { it.startPeriod }))

    /** What is taught on [weekday] of [week]; the 今日课程 list is built from this. */
    fun coursesOn(weekday: Int, week: Int): List<TimetableCourse> =
        coursesInWeek(week).filter { it.weekday == weekday }
}

/** Untrusted values as decoded from the model's JSON: one nullable string per documented key. */
data class TimetableCourseDraft(
    val name: String? = null,
    val teacher: String? = null,
    val room: String? = null,
    val weekday: String? = null,
    val startPeriod: String? = null,
    val endPeriod: String? = null,
    val startWeek: String? = null,
    val endWeek: String? = null,
    /** 单双周 as stated by the source; the week range alone cannot carry it. */
    val parity: String? = null
)

data class TimetableDraft(val term: String? = null, val courses: List<TimetableCourseDraft> = emptyList())

enum class TimetableFailure { NOT_A_TIMETABLE, NO_COURSES, TOO_MANY_COURSES, INVALID_FIELD, CONFLICT }

class TimetableException(val failure: TimetableFailure, message: String) : Exception(message)

/**
 * Turns untrusted draft fields into a [Timetable] or refuses them. A model that mistranscribes a
 * cell still produces well-formed JSON, so every field is range-checked here rather than trusted.
 */
object TimetableValidator {

    fun build(term: String?, courses: List<TimetableCourseDraft>, recognizedAtMillis: Long): Timetable {
        if (courses.isEmpty()) throw TimetableException(TimetableFailure.NO_COURSES, "没有从图片里识别到课程，请换一张更清晰的课表照片。")
        if (courses.size > TIMETABLE_MAX_COURSES) throw TimetableException(TimetableFailure.TOO_MANY_COURSES, "识别到的课程过多（超过 $TIMETABLE_MAX_COURSES 门），请换一张课表照片。")
        val parsed = courses.mapIndexed { index, draft -> parseCourse(draft, index + 1) }
        // A merged cell is often transcribed as one row per period; identical rows are one course.
        val distinct = parsed.distinct()
        rejectConflicts(distinct)
        return Timetable(normalizeText(term, TIMETABLE_MAX_TEXT_LENGTH), distinct, recognizedAtMillis)
    }

    private fun parseCourse(draft: TimetableCourseDraft, position: Int): TimetableCourse {
        val name = normalizeText(draft.name, TIMETABLE_MAX_TEXT_LENGTH)
        if (name.isBlank()) invalid("第 $position 门课程缺少课程名")
        val weekday = normalizeWeekday(draft.weekday)
            ?: invalid("第 $position 门课程的星期无法识别")
        val periods = normalizeSpan(draft.startPeriod, draft.endPeriod, TIMETABLE_MAX_PERIODS)
            ?: invalid("第 $position 门课程的节次无法识别")
        val weeks = normalizeSpan(draft.startWeek, draft.endWeek, TIMETABLE_MAX_WEEKS)
            ?: invalid("第 $position 门课程的周次无法识别")
        return TimetableCourse(
            name = name,
            teacher = normalizeText(draft.teacher, TIMETABLE_MAX_TEXT_LENGTH),
            room = normalizeText(draft.room, TIMETABLE_MAX_TEXT_LENGTH),
            weekday = weekday,
            startPeriod = periods.first,
            endPeriod = periods.last,
            startWeek = weeks.first,
            endWeek = weeks.last,
            parity = parityOf(draft.parity, draft.startWeek, draft.endWeek)
        )
    }

    /**
     * Two different courses cannot occupy the same weekday period in the same week; that means the
     * read is wrong. Two courses that never share a week — a 单周 subject and a 双周 subject sharing
     * one slot, which is common on Chinese timetables — are not a conflict.
     */
    private fun rejectConflicts(courses: List<TimetableCourse>) {
        for (index in courses.indices) {
            for (other in index + 1 until courses.size) {
                val left = courses[index]
                val right = courses[other]
                if (left.weekday != right.weekday || left.name == right.name) continue
                val periodsOverlap = left.startPeriod <= right.endPeriod && right.startPeriod <= left.endPeriod
                if (periodsOverlap && shareATeachingWeek(left, right)) {
                    throw TimetableException(
                        TimetableFailure.CONFLICT,
                        "识别结果里第 ${left.weekday} 天的课程时间冲突，请换一张更清晰的课表照片。"
                    )
                }
            }
        }
    }

    private fun shareATeachingWeek(left: TimetableCourse, right: TimetableCourse): Boolean {
        val first = maxOf(left.startWeek, right.startWeek)
        val last = minOf(left.endWeek, right.endWeek)
        return first <= last && (first..last).any { week -> left.runsInWeek(week) && right.runsInWeek(week) }
    }

    private fun normalizeText(value: String?, maxLength: Int): String {
        val trimmed = value?.trim().orEmpty().replace(Regex("\\s+"), " ")
        if (trimmed.length > maxLength) invalid("识别结果中的文字过长")
        return trimmed
    }

    private fun normalizeWeekday(value: String?): Int? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        WEEKDAY_ALIASES.forEachIndexed { index, aliases ->
            if (aliases.any { text.contains(it, ignoreCase = true) }) return index + 1
        }
        val number = firstInteger(text) ?: return null
        // 0-based values appear when a model counts from Sunday; 1-7 is the documented contract.
        return when (number) {
            in 1..7 -> number
            0 -> 7
            else -> null
        }
    }

    /** Accepts "3", "第3-4节", "1-16周", or a separate end field, and never returns an inverted span. */
    private fun normalizeSpan(start: String?, end: String?, max: Int): IntRange? {
        val startText = start?.trim().orEmpty()
        val endText = end?.trim().orEmpty()
        val startValue = firstInteger(startText) ?: return null
        val endValue = firstInteger(endText) ?: rangeEnd(startText) ?: startValue
        if (startValue !in 1..max || endValue !in 1..max) return null
        return minOf(startValue, endValue)..maxOf(startValue, endValue)
    }

    /**
     * The explicit 单双周 field wins when the model sent one. Otherwise the markers are looked for in
     * the week range itself, because 单周 / 双周 is usually printed inside it on a Chinese timetable.
     */
    private fun parityOf(explicit: String?, start: String?, end: String?): WeekParity {
        declaredParity(explicit)?.let { return it }
        val text = start.orEmpty() + end.orEmpty()
        return when {
            text.any { it in ODD_MARKERS } -> WeekParity.ODD
            text.any { it in EVEN_MARKERS } -> WeekParity.EVEN
            else -> WeekParity.ALL
        }
    }

    private fun declaredParity(value: String?): WeekParity? {
        val text = value?.trim()?.lowercase().orEmpty()
        if (text.isEmpty()) return null
        return when {
            text.contains("odd") || text.contains("single") || text.contains("单") || text.contains("奇") -> WeekParity.ODD
            text.contains("even") || text.contains("double") || text.contains("双") || text.contains("偶") -> WeekParity.EVEN
            text.contains("all") || text.contains("every") || text.contains("each") ||
                text.contains("每") || text.contains("全") || text.contains("无") -> WeekParity.ALL
            else -> null
        }
    }

    private fun rangeEnd(text: String): Int? =
        Regex("\\d{1,3}\\s*[-~/~至到]\\s*(\\d{1,3})").find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun firstInteger(text: String): Int? =
        Regex("\\d{1,3}").find(text)?.value?.toIntOrNull()

    private fun invalid(detail: String): Nothing =
        throw TimetableException(TimetableFailure.INVALID_FIELD, "$detail，请换一张更清晰的课表照片。")

    private val WEEKDAY_ALIASES = listOf(
        listOf("周一", "星期一", "礼拜一", "周1", "mon"),
        listOf("周二", "星期二", "礼拜二", "周2", "tue"),
        listOf("周三", "星期三", "礼拜三", "周3", "wed"),
        listOf("周四", "星期四", "礼拜四", "周4", "thu"),
        listOf("周五", "星期五", "礼拜五", "周5", "fri"),
        listOf("周六", "星期六", "礼拜六", "周6", "sat"),
        listOf("周日", "周天", "星期日", "星期天", "礼拜日", "礼拜天", "周7", "sun")
    )
    private val ODD_MARKERS = "单奇".toCharArray()
    private val EVEN_MARKERS = "双偶".toCharArray()
}

/**
 * Teaching-week arithmetic. The term's first Monday is set once by the user, and every "which week
 * is it" question is answered from it — a guessed week number would silently show the wrong courses.
 */
object TimetableCalendar {

    /** Monday-first weekday number (1 = 周一 … 7 = 周日) of [date]. */
    fun weekdayOf(date: LocalDate): Int = date.dayOfWeek.value

    /** The Monday of the week [date] falls in, as an epoch day — the shape the term start is stored in. */
    fun mondayOfWeek(date: LocalDate): Long = date.minusDays((date.dayOfWeek.value - 1).toLong()).toEpochDay()

    /**
     * The 1-based teaching week [date] falls in. Dates before the term start are reported as week 1
     * rather than a negative week, so a wrong term start can never produce a nonsensical label.
     */
    fun weekOf(date: LocalDate, termStartEpochDay: Long): Int {
        val elapsedDays = date.toEpochDay() - termStartEpochDay
        return (Math.floorDiv(elapsedDays, DAYS_PER_WEEK).toInt() + 1).coerceAtLeast(1)
    }

    /** The seven dates of teaching week [week], so the header can name the range it is showing. */
    fun weekDates(termStartEpochDay: Long, week: Int): List<LocalDate> {
        val monday = LocalDate.ofEpochDay(termStartEpochDay + (week - 1).toLong() * DAYS_PER_WEEK)
        return (0 until DAYS_PER_WEEK.toInt()).map { monday.plusDays(it.toLong()) }
    }

    private const val DAYS_PER_WEEK = 7L
}

sealed interface TimetableBlock {
    val span: Int

    data class Course(val course: TimetableCourse) : TimetableBlock {
        override val span: Int get() = course.periodSpan
    }

    data class Free(val period: Int) : TimetableBlock {
        override val span: Int get() = 1
    }
}

data class TimetableDay(val weekday: Int, val blocks: List<TimetableBlock>)

data class TimetableGrid(val days: List<TimetableDay>, val periods: List<Int>)

/**
 * Splits each weekday into consecutive blocks so a 第1-2节 course is drawn once, spanning two rows.
 * Overlaps in the same week are already refused by [TimetableValidator]; the first covering course
 * wins here so the grid stays total even for a hand-built instance.
 *
 * [week] limits the grid to what is actually taught in that teaching week — a 单周 course is absent
 * from an even week, and the grid is only as tall as that week needs. A null [week] shows every
 * course at once, which is what the plain "整个学期" reading wants.
 */
object TimetableGridLayout {
    fun build(timetable: Timetable, week: Int? = null): TimetableGrid {
        val courses = if (week == null) timetable.courses else timetable.courses.filter { it.runsInWeek(week) }
        val periodCount = courses.maxOfOrNull { it.endPeriod }?.coerceIn(1, TIMETABLE_MAX_PERIODS) ?: 0
        val lastWeekday = if (courses.any { it.weekday >= 6 }) 7 else 5
        val days = (1..lastWeekday).map { weekday ->
            val coursesOfDay = courses.filter { it.weekday == weekday }.sortedBy { it.startPeriod }
            val blocks = mutableListOf<TimetableBlock>()
            var period = 1
            while (period <= periodCount) {
                val course = coursesOfDay.firstOrNull { it.startPeriod <= period && period <= it.endPeriod }
                if (course == null) {
                    blocks += TimetableBlock.Free(period)
                    period++
                } else {
                    blocks += TimetableBlock.Course(course)
                    period = course.endPeriod + 1
                }
            }
            TimetableDay(weekday, blocks)
        }
        return TimetableGrid(days, (1..periodCount).toList())
    }
}

/**
 * Picks a slot in the course-colour ladder for every course. A course keeps one slot everywhere it
 * appears, and a course that would touch the course directly above it in the same column takes the
 * next slot instead, so two neighbouring cells are never painted the same tint.
 */
object TimetableCourseSlots {
    fun assign(courses: List<TimetableCourse>, slotCount: Int): Map<TimetableCourse, Int> {
        if (slotCount <= 0 || courses.isEmpty()) return emptyMap()
        // Sorting the names first keeps the ladder stable across recognitions of the same timetable.
        val preferred = courses.map { it.name }.distinct().sorted()
            .withIndex().associate { (index, name) -> name to index % slotCount }
        val slots = LinkedHashMap<TimetableCourse, Int>()
        courses.groupBy { it.weekday }.toSortedMap().forEach { (_, ofDay) ->
            var previous = -1
            ofDay.sortedBy { it.startPeriod }.forEach { course ->
                val base = preferred[course.name] ?: 0
                val slot = if (base == previous) (base + 1) % slotCount else base
                slots[course] = slot
                previous = slot
            }
        }
        return slots
    }
}
