package cn.gxnu.campus.ui.screens

import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableCalendar
import cn.gxnu.campus.core.TimetableCourse
import java.time.LocalDate

/**
 * 首页「今日课程」的状态。它是一个密封类型而不是几个布尔量，因为「现在该给用户看什么」这件事
 * 只有四种答案，而且互斥：还在读、没有课表、没有开学日期、以及今天。
 */
internal sealed interface HomeToday {
    /** 本机课表还在读，此刻不下任何判断。 */
    data object Loading : HomeToday

    /** 本机没有课表：给一个去取课表的入口。 */
    data object MissingTimetable : HomeToday

    /** 有课表，但没有开学日期，算不出今天是第几周。 */
    data object MissingTermStart : HomeToday

    /** 今天是第 [week] 教学周的星期 [weekday]；[courses] 是当天的课，可能是空的（今天没课）。 */
    data class Day(val week: Int, val weekday: Int, val courses: List<TimetableCourse>) : HomeToday
}

/**
 * 首页今天上什么课。抽成纯函数是因为它同时依赖四件会各自变化的东西（课表、开学日期、今天是哪
 * 一天、今天是星期几），内联在 composable 里既测不了、也容易在某一项还没准备好时说出错话。
 *
 * 选的是**今天所在的教学周**，不是课表页正在浏览的那一周：首页没有周次切换器，它回答的是
 * 「现在」，不是「我正在看的那一周」。
 */
internal fun homeToday(
    timetable: Timetable?,
    restoring: Boolean,
    todayEpochDay: Long,
    todayWeekday: Int,
    termStartEpochDay: Long?
): HomeToday {
    if (restoring) return HomeToday.Loading
    if (timetable == null) return HomeToday.MissingTimetable
    if (termStartEpochDay == null) return HomeToday.MissingTermStart
    val week = TimetableCalendar.weekOf(LocalDate.ofEpochDay(todayEpochDay), termStartEpochDay)
    return HomeToday.Day(
        week = week,
        weekday = todayWeekday.coerceIn(1, 7),
        courses = timetable.coursesOn(todayWeekday.coerceIn(1, 7), week)
    )
}
