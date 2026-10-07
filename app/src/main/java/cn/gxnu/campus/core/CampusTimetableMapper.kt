package cn.gxnu.campus.core

/**
 * 研究生系统的课表 → 本应用的 [Timetable]。
 *
 * 每一行都回到 [TimetableValidator]，与手工编辑走同一条校验：范围检查、去重、冲突拒绝。
 * 导入的课表因此和别的来源产出的课表在模型层完全等价。
 */
object CampusTimetableMapper {

    fun build(courses: List<CampusTimetableApi.CampusCourse>, term: String, recognizedAtMillis: Long): Timetable {
        val drafts = courses.map { course ->
            val span = weekSpan(course.weeks)
            TimetableCourseDraft(
                // 班级名比课程名更具体（「（电子1班）」这类区分就在里面），系统也是把它显示在格子里的。
                name = course.name,
                teacher = course.teacher,
                room = course.room,
                weekday = course.weekday.toString(),
                startPeriod = course.startPeriod.toString(),
                endPeriod = course.endPeriod.toString(),
                startWeek = span.first.toString(),
                endWeek = span.last.toString(),
                parity = parityOf(course.weeks).name
            )
        }
        return TimetableValidator.build(term, drafts, recognizedAtMillis, periodTimes(courses))
    }

    /**
     * 节次栏的时间。系统给的是一行课的头尾（`kssj` / `jssj`），但网格左侧要的是**每一节**的
     * 时间，而任何一门课都未必覆盖到全部 13 节，所以以全校作息表为底，再用这学期实际出现的
     * 课把首尾两节校正一遍 —— 两者不一致时以系统返回的为准。
     */
    private fun periodTimes(courses: List<CampusTimetableApi.CampusCourse>): List<String> {
        val clocks = CampusTimetableApi.PERIOD_CLOCKS.toMutableList()
        courses.forEach { course ->
            val first = course.startPeriod - 1
            val last = course.endPeriod - 1
            clockAt(course.startClock)?.let { clock ->
                clocks.getOrNull(first)?.let { existing ->
                    clocks[first] = clock + "-" + existing.substringAfter('-', existing)
                }
            }
            clockAt(course.endClock)?.let { clock ->
                clocks.getOrNull(last)?.let { existing ->
                    clocks[last] = existing.substringBefore('-', existing) + "-" + clock
                }
            }
        }
        return clocks
    }

    private fun clockAt(value: String): String? =
        value.trim().takeIf { CLOCK.matches(it) }

    /** `3-11周[连续周]` / `3-17周[单周]` / `2-18周[双周]`。 */
    private fun weekSpan(weeks: String): IntRange {
        val numbers = NUMBER.findAll(weeks).map { it.value.toInt() }.toList()
        val first = numbers.getOrNull(0)?.coerceIn(1, TIMETABLE_MAX_WEEKS) ?: 1
        val last = numbers.getOrNull(1)?.coerceIn(1, TIMETABLE_MAX_WEEKS) ?: first
        return minOf(first, last)..maxOf(first, last)
    }

    private fun parityOf(weeks: String): WeekParity = when {
        weeks.contains("单") -> WeekParity.ODD
        weeks.contains("双") -> WeekParity.EVEN
        else -> WeekParity.ALL
    }

    private val NUMBER = Regex("\\d{1,3}")
    private val CLOCK = Regex("\\d{1,2}:\\d{2}")
}
