package cn.gxnu.campus.ui.screens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.gxnu.campus.core.TimetableCourse
import cn.gxnu.campus.core.WeekParity
import cn.gxnu.campus.ui.theme.CampusSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表网格的列宽、行标签与课名行数：5 天档位必须和改动前逐像素一致，6/7 天档位换成
 * 「可读的列宽 + 横向滚动」，4 天以内保持原来的下限与上限。整张网格的宽度只由
 * [dayColumnWidth] 决定，所以这里按纯函数验证，不依赖 Compose 运行时。
 *
 * 课程编辑面板的字段解析（[CourseEditFields.toDraft]）同样在这里按纯函数验证：面板只判定
 * 「填了没有、是不是数字」，越界与冲突留给控制器。
 */
class TimetableGridFitTest {

    /** 360dp 手机上网格真正拿到的宽度：页面左右各 16dp，网格卡左右各 8dp。 */
    private val content360 = 360.dp - CampusSpace.lg * 2 - CampusSpace.sm * 2

    /** 411dp 手机，用来确认窄屏之外的分档也一样成立。 */
    private val content411 = 411.dp - CampusSpace.lg * 2 - CampusSpace.sm * 2

    /** 宽卡片：短周的上限、以及 7 天不再吃下限时的样子。 */
    private val contentWide = 900.dp

    private fun assertWidth(expected: Dp, actual: Dp, message: String) {
        assertEquals(message, expected.value, actual.value, 0.001f)
    }

    // -----------------------------------------------------------------------------------------
    // 5 天：绝大多数情况，零回归
    // -----------------------------------------------------------------------------------------

    @Test
    fun aFiveDayWeekTakesTheEqualShareOfTheCard() {
        // 节次栏加宽到 58dp（要放下「第13节」和它下面的时间）之后，5 天档位是
        // share = (312 - 58 - 4*6) / 5 = 46.0dp。这一档仍然没有上下限，取的就是均分。
        assertWidth(46.dp, dayColumnWidth(5, content360), "5 天列宽应是均分")
        assertWidth(308.dp, gridContentWidth(5, content360), "5 天网格总宽应随之变化")
    }

    @Test
    fun aFiveDayWeekStillFillsTheCardWithoutScrolling() {
        assertTrue(
            "5 天不得溢出：${gridContentWidth(5, content360)} > $content360",
            gridContentWidth(5, content360) <= content360
        )
        // 铺满：只余一个间隙的余量（那一个间隙是刻意留在右边缘的），不是被上限掐住的一半宽卡片。
        assertTrue(
            "5 天应铺满卡片，还空着 ${content360 - gridContentWidth(5, content360)}",
            content360 - gridContentWidth(5, content360) <= CampusSpace.xs
        )
        assertTrue(gridContentWidth(5, content411) <= content411)
    }

    // -----------------------------------------------------------------------------------------
    // 6/7 天：可读的列宽 + 横向滚动
    // -----------------------------------------------------------------------------------------

    @Test
    fun aSevenDayWeekStandsOnItsFloorAndScrolls() {
        assertWidth(56.dp, dayColumnWidth(7, content360), "7 天列宽应停在下限")
        assertTrue(
            "7 天网格必须宽于卡片才需要横向滚动：${gridContentWidth(7, content360)} vs $content360",
            gridContentWidth(7, content360) > content360
        )
        // 411dp 手机上同样停在下限、同样需要滚动。
        assertWidth(56.dp, dayColumnWidth(7, content411), "7 天在 411dp 上也应停在下限")
        assertTrue(gridContentWidth(7, content411) > content411)
    }

    @Test
    fun aSixDayWeekStandsOnTheSameFloorAndScrolls() {
        assertWidth(56.dp, dayColumnWidth(6, content360), "6 天列宽应停在下限")
        assertTrue(
            "6 天网格必须宽于卡片才需要横向滚动：${gridContentWidth(6, content360)} vs $content360",
            gridContentWidth(6, content360) > content360
        )
    }

    @Test
    fun theScrollingWeekTradesTheFitForAWiderColumn() {
        // 滚动档位刻意比 5 天宽：这不是回归，是用横向滚动换来的可读宽度。
        assertTrue(dayColumnWidth(7, content360) > dayColumnWidth(5, content360))
        assertTrue(dayColumnWidth(6, content360) > dayColumnWidth(5, content360))
        // 卡片本身够宽时，7 天不再吃下限，也就不需要滚动了。
        assertTrue(dayColumnWidth(7, contentWide) > 56.dp)
        assertTrue(gridContentWidth(7, contentWide) <= contentWide)
    }

    // -----------------------------------------------------------------------------------------
    // 4 天以内：下限与上限
    // -----------------------------------------------------------------------------------------

    @Test
    fun aShortWeekKeepsItsHardFloorEvenThoughItCostsTwoDp() {
        // 4 天在 360dp 上均分只有 66.75dp，低于 68dp 下限；下限是硬的，所以这张网格比卡片宽 2dp
        // （314 > 312）。这是刻意保留的：宁可多出这 2dp，也不把四字课名挤成一字一行。
        assertWidth(68.dp, dayColumnWidth(4, content360), "4 天应停在下限")
        assertTrue("4 天的均分宽度本就低于下限", dayColumnsWidth(4, content360) / 4 < 68.dp)
        assertTrue("4 天按设计溢出 2dp", gridContentWidth(4, content360) > content360)
    }

    @Test
    fun aShortWeekCeilingFollowsTheAvailableWidth() {
        // 上限随可用宽度收缩：均分不到 112dp 时，列宽就是均分宽度，而不是被上限撑住。
        assertWidth(
            dayColumnsWidth(2, 240.dp) / 2,
            dayColumnWidth(2, 240.dp),
            "2 天的列宽应等于均分宽度"
        )
        assertTrue(dayColumnWidth(2, 240.dp) <= 112.dp)
        // 卡片够宽时才停在上限。
        assertTrue(dayColumnWidth(1, contentWide) <= 112.dp)
        assertWidth(112.dp, dayColumnWidth(3, contentWide), "3 天在宽卡片上应停在上限")
    }

    // -----------------------------------------------------------------------------------------
    // 课名行数与节次文案
    // -----------------------------------------------------------------------------------------

    @Test
    fun aCompactWeekSpendsTwoLinesOnAName() {
        // 6/7 天是滚动档位：列宽只有 56dp，排三行会把课名拆成一个字一行。
        assertEquals(2, dayNameMaxLines(7, periodSpan = 1))
        assertEquals(2, dayNameMaxLines(7, periodSpan = 3))
        assertEquals(2, dayNameMaxLines(6, periodSpan = 1))
        // 5 天不受影响：跨节的格子还是多一行。
        assertEquals(3, dayNameMaxLines(5, periodSpan = 1))
        assertEquals(4, dayNameMaxLines(5, periodSpan = 2))
        assertEquals(4, dayNameMaxLines(4, periodSpan = 2))
    }

    @Test
    fun thePeriodGutterShowsThePeriodNumberItself() {
        // 节次栏回到只显示 1..13：第一行与最后一行都是自己的号码，没有别的文案。
        assertEquals("第1节", gutterPeriodLabel(1))
        assertEquals("第13节", gutterPeriodLabel(13))
    }

    // -----------------------------------------------------------------------------------------
    // 课程编辑面板：字符串字段 → TimetableCourseDraft
    // -----------------------------------------------------------------------------------------

    @Test
    fun aTypedInCourseBecomesTheDraftTheControllerExpects() {
        val draft = CourseEditFields(
            name = "  高等数学  ",
            teacher = " 张三 ",
            room = " 文二楼 302 ",
            weekday = "3",
            startPeriod = "2",
            endPeriod = "4",
            startWeek = "1",
            endWeek = "16",
            parity = WeekParity.ODD
        ).toDraft()

        assertEquals("高等数学", draft?.name)
        assertEquals("张三", draft?.teacher)
        assertEquals("文二楼 302", draft?.room)
        assertEquals("3", draft?.weekday)
        assertEquals("2", draft?.startPeriod)
        assertEquals("4", draft?.endPeriod)
        assertEquals("1", draft?.startWeek)
        assertEquals("16", draft?.endWeek)
        // 单双周按面板显示的三个词送出，模型契约读的就是 单 / 双 / 每。
        assertEquals("单周", draft?.parity)
    }

    @Test
    fun theDefaultsTheBlankFormOpensWithAreAlreadyUsable() {
        // 新增课程打开时是 星期 1、第 1-2 节、第 1-16 周、每周：填上课程名就能直接保存，不用先
        // 改任何数字。课程名本身是必填，所以空白表单先给出 null（见 aCourseWithoutANameIsNotADraftYet）。
        val draft = filledForm().toDraft()
        assertEquals("手填课", draft?.name)
        assertEquals("1", draft?.weekday)
        assertEquals("1", draft?.startPeriod)
        assertEquals("2", draft?.endPeriod)
        assertEquals("1", draft?.startWeek)
        assertEquals("16", draft?.endWeek)
        assertEquals("每周", draft?.parity)
    }

    @Test
    fun anExistingCourseOpensWithItsOwnValuesAndSurvivesARoundTrip() {
        val course = TimetableCourse(
            name = "大学英语",
            teacher = "李四",
            room = "文二楼 302",
            weekday = 3,
            startPeriod = 5,
            endPeriod = 6,
            startWeek = 2,
            endWeek = 18,
            parity = WeekParity.EVEN
        )
        val fields = courseEditFields(course)
        assertEquals("大学英语", fields.name)
        assertEquals("3", fields.weekday)
        assertEquals("5", fields.startPeriod)
        assertEquals("6", fields.endPeriod)
        assertEquals("2", fields.startWeek)
        assertEquals("18", fields.endWeek)
        assertEquals(WeekParity.EVEN, fields.parity)

        // 打开再保存不改动任何字段：这是「手动修一处、其余照旧」的前提。
        val draft = fields.toDraft()
        assertEquals("大学英语", draft?.name)
        assertEquals("3", draft?.weekday)
        assertEquals("5", draft?.startPeriod)
        assertEquals("6", draft?.endPeriod)
        assertEquals("2", draft?.startWeek)
        assertEquals("18", draft?.endWeek)
        assertEquals("双周", draft?.parity)
    }

    @Test
    fun anEmptyPeriodIsNotADraftYet() {
        assertNull("空节次不能当 0 提交", filledForm().copy(startPeriod = "").toDraft())
        assertNull(filledForm().copy(endPeriod = "   ").toDraft())
        assertNull(filledForm().copy(weekday = "").toDraft())
        assertNull(filledForm().copy(startWeek = "").toDraft())
        assertNull(filledForm().copy(endWeek = "").toDraft())
    }

    @Test
    fun aCourseWithoutANameIsNotADraftYet() {
        assertNull(newCourseFields().toDraft())
        assertNull(filledForm().copy(name = "   ").toDraft())
    }

    @Test
    fun aFieldThatIsNotANumberIsNotADraftYet() {
        assertNull(filledForm().copy(startPeriod = "第3节").toDraft())
        assertNull(filledForm().copy(endPeriod = "四").toDraft())
        assertNull(filledForm().copy(weekday = "周一").toDraft())
        assertNull(filledForm().copy(startWeek = "1-16").toDraft())
    }

    /**
     * 一份已填好课程名、其余全是「新增课程」默认值的表单。课程名本身是必填，所以下面每个用例
     * 都能确定 null 出自它自己改动的那一个字段，而不是又被空的课程名挡住了。
     */
    private fun filledForm() = newCourseFields().copy(name = "手填课")

    @Test
    fun thePanelDoesNotPullAnOutOfRangeNumberBackIntoRange() {
        // 越界只要求「能解析成数字」：判定越界、冲突并发布中文原因是控制器的事，面板不抢这一票，
        // 也不把 99 悄悄夹到 13 让用户以为改好了。
        val draft = filledForm()
            .copy(weekday = "9", startPeriod = "99", endPeriod = "99", startWeek = "31", endWeek = "99")
            .toDraft()
        assertEquals("9", draft?.weekday)
        assertEquals("99", draft?.startPeriod)
        assertEquals("99", draft?.endPeriod)
        assertEquals("31", draft?.startWeek)
        assertEquals("99", draft?.endWeek)
    }
}
