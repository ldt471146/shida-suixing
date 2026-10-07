package cn.gxnu.campus.ui.screens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.gxnu.campus.core.TIMETABLE_PERIOD_NONE
import cn.gxnu.campus.ui.theme.CampusSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表网格的列宽、行标签与课文行数：5 天档位必须和改动前逐像素一致，6/7 天档位换成
 * 「可读的列宽 + 横向滚动」，4 天以内保持原来的下限与上限。整张网格的宽度只由
 * [dayColumnWidth] 决定，所以这里按纯函数验证，不依赖 Compose 运行时。
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
    fun aFiveDayWeekIsPixelForPixelWhatItWasBefore() {
        // 改动前的 5 天档位是 share = (312 - 30 - 3*6) / 5 = 52.8dp，本次改动把它原样搬进
        // dayColumnWidth 的第二个分支，没有加任何上下限，所以两边的结果逐像素相同。
        assertWidth(52.8.dp, dayColumnWidth(5, content360), "5 天列宽必须与改动前一致")
        assertWidth(309.dp, gridContentWidth(5, content360), "5 天网格总宽必须与改动前一致")
    }

    @Test
    fun aFiveDayWeekStillFillsTheCardWithoutScrolling() {
        assertTrue(
            "5 天不得溢出：${gridContentWidth(5, content360)} > $content360",
            gridContentWidth(5, content360) <= content360
        )
        // 铺满：只余一个间隙的余量，不是被上限掐住的一半宽卡片。
        assertTrue(
            "5 天应铺满卡片，还空着 ${content360 - gridContentWidth(5, content360)}",
            content360 - gridContentWidth(5, content360) < CampusSpace.xs
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
    fun theNoPeriodRowHasItsOwnLabel() {
        assertEquals("无节次", gutterPeriodLabel(TIMETABLE_PERIOD_NONE))
        assertEquals("1", gutterPeriodLabel(1))
        assertEquals("13", gutterPeriodLabel(13))
    }
}
