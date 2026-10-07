package cn.gxnu.campus.ui

import cn.gxnu.campus.core.CampusRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航模型：底栏是哪三个 tab、往哪边切、二级页回哪儿、哪个 tab 亮着。
 *
 * 这些判断原来散在 CampusApp 的 when 分支里，改一处很容易让另一处跟着错；抽成纯函数之后，
 * 「最右边是『我的』」和「从校园网进账号页要回校园网」都是可断言的。
 */
class CampusNavigationTest {

    @Test
    fun theBottomBarIsHomeServicesProfileInThatOrder() {
        assertEquals(
            listOf(Destination.HOME, Destination.SERVICES, Destination.PROFILE),
            TOP_LEVEL_TABS
        )
        // 用户明确要求过：最右边是「我的」。
        assertEquals(Destination.PROFILE, TOP_LEVEL_TABS.last())
    }

    @Test
    fun onlyTheThreeTabsAreTopLevel() {
        assertTrue(Destination.HOME.isTab)
        assertTrue(Destination.SERVICES.isTab)
        assertTrue(Destination.PROFILE.isTab)
        listOf(Destination.CAMPUS_NETWORK, Destination.TIMETABLE, Destination.SETTINGS, Destination.ACCOUNT)
            .forEach { assertTrue("$it 不该出现在底栏", !it.isTab) }
    }

    @Test
    fun switchingRightIsPositiveAndSwitchingLeftIsNegative() {
        assertEquals(1, tabTravel(Destination.HOME, Destination.SERVICES))
        assertEquals(1, tabTravel(Destination.SERVICES, Destination.PROFILE))
        assertEquals(-1, tabTravel(Destination.PROFILE, Destination.HOME))
        assertEquals(0, tabTravel(Destination.SERVICES, Destination.SERVICES))
    }

    @Test
    fun aSecondaryPageHasNoLateralDirection() {
        assertEquals(0, tabTravel(Destination.SERVICES, Destination.CAMPUS_NETWORK))
        assertEquals(0, tabTravel(Destination.CAMPUS_NETWORK, Destination.SERVICES))
        assertEquals(0, tabTravel(Destination.SETTINGS, Destination.PROFILE))
    }

    @Test
    fun everySecondaryPageKnowsItsParent() {
        assertEquals(Destination.SERVICES, destinationParent(Destination.CAMPUS_NETWORK, Destination.HOME))
        assertEquals(Destination.SERVICES, destinationParent(Destination.TIMETABLE, Destination.HOME))
        assertEquals(Destination.PROFILE, destinationParent(Destination.SETTINGS, Destination.HOME))
        // 账号页回到它进来时的那一层，而不是永远回首页。
        assertEquals(Destination.CAMPUS_NETWORK, destinationParent(Destination.ACCOUNT, Destination.CAMPUS_NETWORK))
        assertEquals(Destination.PROFILE, destinationParent(Destination.ACCOUNT, Destination.PROFILE))
        // 记下的来源就是自己时不能原地打转，也不能递归到栈溢出。
        assertEquals(Destination.HOME, destinationParent(Destination.ACCOUNT, Destination.ACCOUNT))
    }

    @Test
    fun backFromATabFallsToHome() {
        assertEquals(Destination.HOME, destinationParent(Destination.SERVICES, Destination.HOME))
        assertEquals(Destination.HOME, destinationParent(Destination.PROFILE, Destination.HOME))
        assertEquals(Destination.HOME, destinationParent(Destination.HOME, Destination.HOME))
    }

    @Test
    fun theLitTabFollowsThePageBelowIt() {
        assertEquals(Destination.HOME, litTab(Destination.HOME, Destination.HOME))
        assertEquals(Destination.SERVICES, litTab(Destination.SERVICES, Destination.HOME))
        assertEquals(Destination.PROFILE, litTab(Destination.PROFILE, Destination.HOME))
        // 二级页保持父 tab 亮着，否则整个底栏会一句话都不说。
        assertEquals(Destination.SERVICES, litTab(Destination.CAMPUS_NETWORK, Destination.HOME))
        assertEquals(Destination.SERVICES, litTab(Destination.TIMETABLE, Destination.HOME))
        assertEquals(Destination.PROFILE, litTab(Destination.SETTINGS, Destination.HOME))
    }

    @Test
    fun theAccountPageLightsTheTabItCameFrom() {
        assertEquals(Destination.SERVICES, litTab(Destination.ACCOUNT, Destination.CAMPUS_NETWORK))
        assertEquals(Destination.PROFILE, litTab(Destination.ACCOUNT, Destination.PROFILE))
        assertEquals(Destination.HOME, litTab(Destination.ACCOUNT, Destination.HOME))
        // 损坏的来源值不该让底栏没有选中项。
        assertEquals(Destination.HOME, litTab(Destination.ACCOUNT, Destination.ACCOUNT))
    }

    @Test
    fun depthSeparatesTabsFromSecondaryPages() {
        TOP_LEVEL_TABS.forEach { assertEquals(0, destinationDepth(it)) }
        listOf(Destination.CAMPUS_NETWORK, Destination.TIMETABLE, Destination.SETTINGS, Destination.ACCOUNT)
            .forEach { assertEquals(1, destinationDepth(it)) }
    }

    @Test
    fun aModuleRoutesToItsOwnPage() {
        assertEquals(Destination.CAMPUS_NETWORK, destinationOf(CampusRoute.CAMPUS_NETWORK))
        assertEquals(Destination.TIMETABLE, destinationOf(CampusRoute.TIMETABLE))
    }
}
