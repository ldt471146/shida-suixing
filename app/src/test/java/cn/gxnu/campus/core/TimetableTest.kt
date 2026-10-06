package cn.gxnu.campus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fictional fixtures only; no real course, teacher or room data. */
class TimetableTest {

    private fun draft(
        name: String? = "高等数学-fixture",
        teacher: String? = "教师甲",
        room: String? = "文理楼201",
        weekday: String? = "1",
        startPeriod: String? = "1",
        endPeriod: String? = "2",
        startWeek: String? = "1",
        endWeek: String? = "16"
    ) = TimetableCourseDraft(name, teacher, room, weekday, startPeriod, endPeriod, startWeek, endWeek)

    private fun failureOf(draft: TimetableDraft): TimetableFailure = try {
        TimetableValidator.build(draft.term, draft.courses, 0L)
        throw AssertionError("expected the draft to be refused")
    } catch (failure: TimetableException) {
        failure.failure
    }

    @Test fun aCompleteDraftBecomesOneCourse() {
        val timetable = TimetableValidator.build("2025-2026学年第一学期", listOf(draft()), 1_700_000_000_000L)
        assertEquals("2025-2026学年第一学期", timetable.term)
        assertEquals(1, timetable.courseCount)
        assertEquals(1_700_000_000_000L, timetable.recognizedAtMillis)
        val course = timetable.courses.single()
        assertEquals(1, course.weekday)
        assertEquals(1, course.startPeriod)
        assertEquals(2, course.endPeriod)
        assertEquals(16, course.endWeek)
        assertEquals(2, course.periodSpan)
    }

    @Test fun chineseFieldFormsAreAccepted() {
        val timetable = TimetableValidator.build(null, listOf(
            draft(weekday = "星期三", startPeriod = "第3-4节", startWeek = "1", endWeek = "16", endPeriod = null)
        ), 0L)
        val course = timetable.courses.single()
        assertEquals(3, course.weekday)
        assertEquals(3, course.startPeriod)
        assertEquals(4, course.endPeriod)
        assertEquals(1, course.startWeek)
        assertEquals(16, course.endWeek)
    }

    @Test fun weekdayAliasesAndSundayNumberingAreUnderstood() {
        assertEquals(7, TimetableValidator.build(null, listOf(draft(weekday = "周日")), 0L).courses.single().weekday)
        assertEquals(7, TimetableValidator.build(null, listOf(draft(weekday = "sunday")), 0L).courses.single().weekday)
        // A model that counts from Sunday sends 0 for 周日.
        assertEquals(7, TimetableValidator.build(null, listOf(draft(weekday = "0")), 0L).courses.single().weekday)
        assertEquals(5, TimetableValidator.build(null, listOf(draft(weekday = "fri")), 0L).courses.single().weekday)
    }

    @Test fun oddAndEvenWeeksAreKeptWithTheRange() {
        val odd = TimetableValidator.build(null, listOf(draft(startWeek = "1-16周(单)", endWeek = null)), 0L)
        assertEquals(WeekParity.ODD, odd.courses.single().parity)
        assertEquals("1-16 周（单周）", odd.courses.single().weekLabel)
        val even = TimetableValidator.build(null, listOf(draft(startWeek = "2", endWeek = "16双周")), 0L)
        assertEquals(WeekParity.EVEN, even.courses.single().parity)
        assertEquals(WeekParity.ALL, TimetableValidator.build(null, listOf(draft()), 0L).courses.single().parity)
    }

    @Test fun invertedRangesArePutInOrder() {
        val course = TimetableValidator.build(null, listOf(draft(startPeriod = "4", endPeriod = "2")), 0L).courses.single()
        assertEquals(2, course.startPeriod)
        assertEquals(4, course.endPeriod)
    }

    @Test fun labelsReadBackForTheGrid() {
        val course = TimetableValidator.build(null, listOf(draft(startPeriod = "1", endPeriod = "1")), 0L).courses.single()
        assertEquals("第1节", course.periodLabel)
        assertEquals("1-16 周", course.weekLabel)
        assertEquals("周一", course.weekdayLabel)
        assertEquals("教师甲 · 文理楼201", course.detailLabel)
        val singleWeek = TimetableValidator.build(null, listOf(draft(startWeek = "3", endWeek = "3")), 0L).courses.single()
        assertEquals("第3周", singleWeek.weekLabel)
        val blankTeacher = TimetableValidator.build(null, listOf(draft(teacher = "  ")), 0L).courses.single()
        assertEquals("文理楼201", blankTeacher.detailLabel)
    }

    @Test fun aCourseWithoutANameIsRefused() {
        assertEquals(TimetableFailure.INVALID_FIELD, failureOf(TimetableDraft(courses = listOf(draft(name = "   ")))))
    }

    @Test fun anUnreadableNumberIsRefused() {
        assertEquals(TimetableFailure.INVALID_FIELD, failureOf(TimetableDraft(courses = listOf(draft(weekday = "下周")))))
        assertEquals(TimetableFailure.INVALID_FIELD, failureOf(TimetableDraft(courses = listOf(draft(startPeriod = "待定")))))
    }

    @Test fun outOfRangeFieldsAreRefused() {
        assertEquals(TimetableFailure.INVALID_FIELD, failureOf(TimetableDraft(courses = listOf(draft(weekday = "9")))))
        assertEquals(TimetableFailure.INVALID_FIELD, failureOf(TimetableDraft(courses = listOf(draft(startPeriod = "25")))))
        assertEquals(TimetableFailure.INVALID_FIELD, failureOf(TimetableDraft(courses = listOf(draft(endWeek = "40")))))
    }

    @Test fun overlongTextIsRefused() {
        assertEquals(TimetableFailure.INVALID_FIELD, failureOf(TimetableDraft(courses = listOf(draft(name = "课".repeat(200))))))
    }

    @Test fun noCourseAtAllIsReportedAsNothingRecognised() {
        assertEquals(TimetableFailure.NO_COURSES, failureOf(TimetableDraft(term = "2025-2026学年", courses = emptyList())))
    }

    @Test fun anAbsurdCourseCountIsRefused() {
        val many = List(TIMETABLE_MAX_COURSES + 1) { draft() }
        assertEquals(TimetableFailure.TOO_MANY_COURSES, failureOf(TimetableDraft(courses = many)))
    }

    @Test fun twoDifferentCoursesInTheSamePeriodAreRefused() {
        val conflicting = listOf(
            draft(name = "课程A", startPeriod = "1", endPeriod = "2"),
            draft(name = "课程B", startPeriod = "2", endPeriod = "3")
        )
        assertEquals(TimetableFailure.CONFLICT, failureOf(TimetableDraft(courses = conflicting)))
    }

    @Test fun repeatedIdenticalRowsAreCollapsed() {
        val timetable = TimetableValidator.build(null, listOf(draft(), draft()), 0L)
        assertEquals(1, timetable.courseCount)
    }

    @Test fun gridMergesConsecutivePeriodsAndFillsTheGaps() {
        val timetable = TimetableValidator.build(null, listOf(
            draft(name = "课程A", weekday = "3", startPeriod = "1", endPeriod = "2"),
            draft(name = "课程B", weekday = "3", startPeriod = "5", endPeriod = "6", room = "综合楼101")
        ), 0L)
        val grid = TimetableGridLayout.build(timetable)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), grid.periods)
        // Weekday 6 is unused, so the grid stops at Friday.
        assertEquals(listOf(1, 2, 3, 4, 5), grid.days.map { it.weekday })
        val wednesday = grid.days.first { it.weekday == 3 }.blocks
        assertEquals(4, wednesday.size)
        assertEquals(TimetableBlock.Course(timetable.courses[0]), wednesday[0])
        assertEquals(2, wednesday[0].span)
        assertEquals(TimetableBlock.Free(3), wednesday[1])
        assertEquals(TimetableBlock.Free(4), wednesday[2])
        assertEquals(2, wednesday[3].span)
        assertTrue(grid.days.first { it.weekday == 1 }.blocks.all { it is TimetableBlock.Free })
    }

    @Test fun gridShowsTheWeekendAsSoonAsOneCourseNeedsIt() {
        val timetable = TimetableValidator.build(null, listOf(draft(weekday = "7")), 0L)
        val grid = TimetableGridLayout.build(timetable)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), grid.days.map { it.weekday })
        assertTrue(grid.days.last().blocks.single() is TimetableBlock.Course)
        assertFalse(grid.days.first().blocks.any { it is TimetableBlock.Course })
    }
}
