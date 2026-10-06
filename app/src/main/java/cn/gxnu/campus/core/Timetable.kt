package cn.gxnu.campus.core

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
}

data class Timetable(
    val term: String,
    val courses: List<TimetableCourse>,
    val recognizedAtMillis: Long
) {
    val courseCount: Int get() = courses.size
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
    val endWeek: String? = null
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
            parity = parityOf(draft.startWeek, draft.endWeek)
        )
    }

    /** Two different courses cannot occupy the same weekday period; that means the read is wrong. */
    private fun rejectConflicts(courses: List<TimetableCourse>) {
        for (index in courses.indices) {
            for (other in index + 1 until courses.size) {
                val left = courses[index]
                val right = courses[other]
                if (left.weekday != right.weekday || left.name == right.name) continue
                val periodsOverlap = left.startPeriod <= right.endPeriod && right.startPeriod <= left.endPeriod
                if (periodsOverlap) {
                    throw TimetableException(
                        TimetableFailure.CONFLICT,
                        "识别结果里第 ${left.weekday} 天的课程时间冲突，请换一张更清晰的课表照片。"
                    )
                }
            }
        }
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

    private fun parityOf(start: String?, end: String?): WeekParity {
        val text = start.orEmpty() + end.orEmpty()
        return when {
            text.any { it in ODD_MARKERS } -> WeekParity.ODD
            text.any { it in EVEN_MARKERS } -> WeekParity.EVEN
            else -> WeekParity.ALL
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
 * Overlaps are already refused by [TimetableValidator]; the first covering course wins here so the
 * grid stays total even for a hand-built instance.
 */
object TimetableGridLayout {
    fun build(timetable: Timetable): TimetableGrid {
        val periodCount = timetable.courses.maxOfOrNull { it.endPeriod }?.coerceIn(1, TIMETABLE_MAX_PERIODS) ?: 0
        val lastWeekday = if (timetable.courses.any { it.weekday >= 6 }) 7 else 5
        val days = (1..lastWeekday).map { weekday ->
            val courses = timetable.courses.filter { it.weekday == weekday }.sortedBy { it.startPeriod }
            val blocks = mutableListOf<TimetableBlock>()
            var period = 1
            while (period <= periodCount) {
                val course = courses.firstOrNull { it.startPeriod <= period && period <= it.endPeriod }
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
