package cn.gxnu.campus.core

import java.time.LocalDate
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
        endWeek: String? = "16",
        parity: String? = null
    ) = TimetableCourseDraft(name, teacher, room, weekday, startPeriod, endPeriod, startWeek, endWeek, parity)

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

    // ---- teaching weeks and 单双周 ---------------------------------------------------------------

    private fun weekFixture(): Timetable = TimetableValidator.build("2025-2026学年第一学期", listOf(
        draft(name = "每周课-fixture", weekday = "1", startPeriod = "1", endPeriod = "2", startWeek = "1", endWeek = "16"),
        draft(
            name = "单周课-fixture", weekday = "3", startPeriod = "1", endPeriod = "2",
            startWeek = "1", endWeek = "16", parity = "odd"
        ),
        draft(
            name = "双周课-fixture", weekday = "3", startPeriod = "1", endPeriod = "2",
            startWeek = "2", endWeek = "16", parity = "even"
        ),
        draft(name = "后半学期课-fixture", weekday = "5", startPeriod = "3", endPeriod = "4", startWeek = "9", endWeek = "16")
    ), 0L)

    @Test fun aCourseOnlyRunsInTheWeeksItCovers() {
        val timetable = weekFixture()
        val odd = timetable.courses.first { it.name == "单周课-fixture" }
        val even = timetable.courses.first { it.name == "双周课-fixture" }
        assertTrue(odd.runsInWeek(1))
        assertTrue(odd.runsInWeek(15))
        assertFalse(odd.runsInWeek(2))
        assertFalse(odd.runsInWeek(16))
        assertTrue(even.runsInWeek(2))
        assertTrue(even.runsInWeek(16))
        assertFalse(even.runsInWeek(1))
        val secondHalf = timetable.courses.first { it.name == "后半学期课-fixture" }
        assertFalse(secondHalf.runsInWeek(8))
        assertTrue(secondHalf.runsInWeek(9))
    }

    @Test fun theWeekCountFollowsTheLongestCourse() {
        assertEquals(16, weekFixture().weekCount)
        assertEquals(1, TimetableValidator.build(null, listOf(draft(startWeek = "1", endWeek = "1")), 0L).weekCount)
    }

    @Test fun eachWeekListsExactlyWhatIsTaught() {
        val timetable = weekFixture()
        val oddWeek = timetable.coursesInWeek(1).map { it.name }
        assertEquals(listOf("每周课-fixture", "单周课-fixture"), oddWeek)
        val evenWeek = timetable.coursesInWeek(4).map { it.name }
        assertEquals(listOf("每周课-fixture", "双周课-fixture"), evenWeek)
        assertTrue(timetable.coursesInWeek(9).map { it.name }.contains("后半学期课-fixture"))
    }

    @Test fun theGridForAWeekHidesWhatIsNotTaughtThen() {
        val timetable = weekFixture()
        // Week 1: Monday 1-2, Wednesday 1-2 (单周 only), Friday is still empty, so the grid stops at Friday.
        val oddWeek = TimetableGridLayout.build(timetable, 1)
        assertEquals(listOf(1, 2, 3, 4, 5), oddWeek.days.map { it.weekday })
        assertEquals(listOf(1, 2), oddWeek.periods)
        val wednesday = oddWeek.days.first { it.weekday == 3 }.blocks.single()
        assertEquals(TimetableBlock.Course(timetable.courses.first { it.name == "单周课-fixture" }), wednesday)

        // Week 9 is taller: the second-half course adds a 3-4 slot on Friday.
        val laterWeek = TimetableGridLayout.build(timetable, 9)
        assertEquals(listOf(1, 2, 3, 4), laterWeek.periods)
        assertTrue(laterWeek.days.first { it.weekday == 5 }.blocks.any { it is TimetableBlock.Course })
    }

    @Test fun aWeekWithNothingInItProducesAnEmptyGrid() {
        val timetable = TimetableValidator.build(null, listOf(
            draft(name = "早结课-fixture", weekday = "2", startWeek = "1", endWeek = "4")
        ), 0L)
        val grid = TimetableGridLayout.build(timetable, 9)
        assertTrue(grid.periods.isEmpty())
        assertTrue(grid.days.all { it.blocks.isEmpty() })
    }

    @Test fun aCourseThatAlternatesWeeksIsNotAConflict() {
        // 单周 语文 and 双周 数学 in the same slot is a normal timetable, not a misread.
        val timetable = TimetableValidator.build(null, listOf(
            draft(name = "单周课-fixture", weekday = "3", startPeriod = "1", endPeriod = "2", startWeek = "1", endWeek = "16", parity = "odd"),
            draft(name = "双周课-fixture", weekday = "3", startPeriod = "1", endPeriod = "2", startWeek = "2", endWeek = "16", parity = "even")
        ), 0L)
        assertEquals(2, timetable.courseCount)
        assertEquals(WeekParity.ODD, timetable.courses.first().parity)
        assertEquals(WeekParity.EVEN, timetable.courses.last().parity)
    }

    @Test fun theSameParityInOneSlotIsStillAConflict() {
        assertEquals(TimetableFailure.CONFLICT, failureOf(TimetableDraft(courses = listOf(
            draft(name = "课程A-fixture", weekday = "3", startPeriod = "1", endPeriod = "2", parity = "odd"),
            draft(name = "课程B-fixture", weekday = "3", startPeriod = "1", endPeriod = "2", parity = "odd")
        ))))
        // Ranges that only overlap on an even week still collide when both are 双周.
        assertEquals(TimetableFailure.CONFLICT, failureOf(TimetableDraft(courses = listOf(
            draft(name = "课程C-fixture", weekday = "4", startPeriod = "1", endPeriod = "2", startWeek = "2", endWeek = "4", parity = "even"),
            draft(name = "课程D-fixture", weekday = "4", startPeriod = "2", endPeriod = "3", startWeek = "4", endWeek = "8", parity = "even")
        ))))
    }

    @Test fun coursesCoveringDifferentWeeksAreNotAConflict() {
        assertEquals(2, TimetableValidator.build(null, listOf(
            draft(name = "上半学期-fixture", weekday = "4", startPeriod = "1", endPeriod = "2", startWeek = "1", endWeek = "8"),
            draft(name = "下半学期-fixture", weekday = "4", startPeriod = "1", endPeriod = "2", startWeek = "9", endWeek = "16")
        ), 0L).courseCount)
    }

    // ---- course colour slots -------------------------------------------------------------------

    @Test fun neighbouringCoursesInAColumnNeverShareATint() {
        val courses = (1..6).map { index ->
            draft(name = "课程$index-fixture", weekday = "2", startPeriod = "${index * 2 - 1}", endPeriod = "${index * 2}")
        }
        val timetable = TimetableValidator.build(null, courses, 0L)
        val slots = TimetableCourseSlots.assign(timetable.courses, 6)
        val byPeriod = timetable.courses.sortedBy { it.startPeriod }
        byPeriod.zipWithNext { above, below ->
            assertFalse(
                "${above.name} and ${below.name} must not share a tint",
                slots[above] == slots[below]
            )
        }
    }

    @Test fun theSameCourseKeepsItsTintAcrossTheGrid() {
        val timetable = TimetableValidator.build(null, listOf(
            draft(name = "课程A-fixture", weekday = "1", startPeriod = "1", endPeriod = "2"),
            draft(name = "课程A-fixture", weekday = "2", startPeriod = "1", endPeriod = "2"),
            draft(name = "课程B-fixture", weekday = "3", startPeriod = "1", endPeriod = "2")
        ), 0L)
        val slots = TimetableCourseSlots.assign(timetable.courses, 6)
        val sameName = timetable.courses.filter { it.name == "课程A-fixture" }.map { slots[it] }.distinct()
        assertEquals(1, sameName.size)
    }

    @Test fun everyCourseGetsASlotInsideTheLadder() {
        val timetable = weekFixture()
        val slots = TimetableCourseSlots.assign(timetable.courses, 6)
        assertEquals(timetable.courses.size, slots.size)
        assertTrue(slots.values.all { it in 0 until 6 })
        // A degenerate ladder never divides by zero or hands out an out-of-range slot.
        assertTrue(TimetableCourseSlots.assign(timetable.courses, 1).values.all { it == 0 })
        assertTrue(TimetableCourseSlots.assign(timetable.courses, 0).isEmpty())
        assertTrue(TimetableCourseSlots.assign(emptyList(), 6).isEmpty())
    }

    // ---- term start to current week ------------------------------------------------------------

    @Test fun theTermStartTurnsIntoACurrentWeek() {
        val monday = LocalDate.of(2026, 9, 21)
        assertEquals(1, TimetableCalendar.weekOf(monday, monday.toEpochDay()))
        assertEquals(1, TimetableCalendar.weekOf(monday.plusDays(6), monday.toEpochDay()))
        assertEquals(2, TimetableCalendar.weekOf(monday.plusDays(7), monday.toEpochDay()))
        assertEquals(3, TimetableCalendar.weekOf(LocalDate.of(2026, 10, 6), monday.toEpochDay()))
    }

    @Test fun datesBeforeTheTermStartReadAsWeekOne() {
        val monday = LocalDate.of(2026, 9, 21)
        assertEquals(1, TimetableCalendar.weekOf(monday.minusDays(1), monday.toEpochDay()))
        assertEquals(1, TimetableCalendar.weekOf(monday.minusDays(120), monday.toEpochDay()))
    }

    @Test fun weekDatesCoverMondayToSunday() {
        val monday = LocalDate.of(2026, 9, 21)
        val dates = TimetableCalendar.weekDates(monday.toEpochDay(), 1)
        assertEquals(7, dates.size)
        assertEquals(monday, dates.first())
        assertEquals(monday.plusDays(6), dates.last())
        assertEquals(LocalDate.of(2026, 10, 5), TimetableCalendar.weekDates(monday.toEpochDay(), 3).first())
    }

    @Test fun theWeekdayOfADateIsMondayFirst() {
        assertEquals(1, TimetableCalendar.weekdayOf(LocalDate.of(2026, 9, 21)))
        assertEquals(2, TimetableCalendar.weekdayOf(LocalDate.of(2026, 10, 6)))
        assertEquals(7, TimetableCalendar.weekdayOf(LocalDate.of(2026, 10, 11)))
        // 2026-10-06 is a Tuesday, so the Monday of its week is 2026-10-05.
        assertEquals(
            LocalDate.of(2026, 10, 5).toEpochDay(),
            TimetableCalendar.mondayOfWeek(LocalDate.of(2026, 10, 6))
        )
        // A Monday is its own week's start.
        assertEquals(
            LocalDate.of(2026, 9, 21).toEpochDay(),
            TimetableCalendar.mondayOfWeek(LocalDate.of(2026, 9, 21))
        )
    }
}
