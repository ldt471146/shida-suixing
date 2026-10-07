package cn.gxnu.campus.ui.screens

import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableCourse
import cn.gxnu.campus.core.WeekParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页「今日课程」的选取。
 *
 * 这一页要回答的是「现在」，而它同时依赖课表、开学日期、今天是哪一天和今天是星期几 —— 四种
 * 输入任意一种还没准备好，说的都必须是实话，所以每一种都单独钉一条。
 */
class HomeTodayTest {

    /** 开学日就是第一周的周一。 */
    private val termStart = 20_000L

    private fun monday(weeksAfterStart: Long): Long = termStart + weeksAfterStart * 7

    private val math = TimetableCourse(
        name = "高等数学",
        teacher = "张三",
        room = "文二楼 302",
        weekday = 1,
        startPeriod = 1,
        endPeriod = 2,
        startWeek = 1,
        endWeek = 16
    )

    private val oddOnlyLecture = TimetableCourse(
        name = "形势与政策",
        teacher = "李四",
        room = "理二楼 101",
        weekday = 1,
        startPeriod = 5,
        endPeriod = 6,
        startWeek = 1,
        endWeek = 16,
        parity = WeekParity.ODD
    )

    private fun timetable(vararg courses: TimetableCourse) =
        Timetable(term = "", courses = courses.toList(), recognizedAtMillis = 0L)

    @Test
    fun whileTheLocalTimetableIsStillBeingReadNothingIsClaimed() {
        val today = homeToday(
            timetable = timetable(math),
            restoring = true,
            todayEpochDay = monday(2),
            todayWeekday = 1,
            termStartEpochDay = termStart
        )
        assertEquals(HomeToday.Loading, today)
    }

    @Test
    fun noTimetableOnThisDeviceAsksForOneInsteadOfGuessing() {
        val today = homeToday(
            timetable = null,
            restoring = false,
            todayEpochDay = monday(2),
            todayWeekday = 1,
            termStartEpochDay = termStart
        )
        assertEquals(HomeToday.MissingTimetable, today)
    }

    @Test
    fun aTimetableWithoutATermStartCannotClaimATeachingWeek() {
        val today = homeToday(
            timetable = timetable(math),
            restoring = false,
            todayEpochDay = monday(2),
            todayWeekday = 1,
            termStartEpochDay = null
        )
        assertEquals(HomeToday.MissingTermStart, today)
    }

    @Test
    fun theThirdWeekAfterTheTermStartIsTeachingWeekThree() {
        val today = homeToday(
            timetable = timetable(math),
            restoring = false,
            todayEpochDay = monday(2),
            todayWeekday = 1,
            termStartEpochDay = termStart
        )
        assertEquals(HomeToday.Day(week = 3, weekday = 1, courses = listOf(math)), today)
    }

    @Test
    fun aDayWithNothingScheduledIsAReadyDayWithNoCourses() {
        val today = homeToday(
            timetable = timetable(math),
            restoring = false,
            // 同一周的周六：课表上只有周一有课。
            todayEpochDay = monday(2) + 5,
            todayWeekday = 6,
            termStartEpochDay = termStart
        )
        assertEquals(HomeToday.Day(week = 3, weekday = 6, courses = emptyList()), today)
    }

    @Test
    fun aSingleWeekCourseIsAbsentFromAnEvenWeek() {
        val oddWeek = homeToday(
            timetable = timetable(oddOnlyLecture),
            restoring = false,
            // 第 1 周是单周。
            todayEpochDay = monday(0),
            todayWeekday = 1,
            termStartEpochDay = termStart
        )
        val evenWeek = homeToday(
            timetable = timetable(oddOnlyLecture),
            restoring = false,
            // 第 2 周是双周：这门单周课不该出现在今天。
            todayEpochDay = monday(1),
            todayWeekday = 1,
            termStartEpochDay = termStart
        )
        assertEquals(listOf(oddOnlyLecture), (oddWeek as HomeToday.Day).courses)
        assertEquals(emptyList<TimetableCourse>(), (evenWeek as HomeToday.Day).courses)
    }

    @Test
    fun datesBeforeTheTermStartReadAsWeekOneRatherThanANegativeWeek() {
        val today = homeToday(
            timetable = timetable(math),
            restoring = false,
            todayEpochDay = termStart - 5,
            todayWeekday = 1,
            termStartEpochDay = termStart
        )
        assertEquals(HomeToday.Day(week = 1, weekday = 1, courses = listOf(math)), today)
    }

    @Test
    fun todayPicksTheCourseOfTheWeekItIsInNotOfTheBrowsedWeek() {
        // 课表页可能正翻到第 9 周，首页仍然只回答「今天是第几周、今天有什么」。
        val today = homeToday(
            timetable = timetable(math),
            restoring = false,
            todayEpochDay = monday(1),
            todayWeekday = 1,
            termStartEpochDay = termStart
        )
        assertEquals(HomeToday.Day(week = 2, weekday = 1, courses = listOf(math)), today)
    }

    @Test
    fun aSundayIsWeekdaySeven() {
        val today = homeToday(
            timetable = timetable(math),
            restoring = false,
            todayEpochDay = monday(0) + 6,
            todayWeekday = 7,
            termStartEpochDay = termStart
        )
        assertEquals(7, (today as HomeToday.Day).weekday)
    }

    @Test
    fun anImpossibleWeekdayIsClampedInsteadOfCrashing() {
        val today = homeToday(
            timetable = timetable(math),
            restoring = false,
            todayEpochDay = monday(0),
            todayWeekday = 0,
            termStartEpochDay = termStart
        )
        assertTrue("星期只可能是 1..7", (today as HomeToday.Day).weekday in 1..7)
    }
}
