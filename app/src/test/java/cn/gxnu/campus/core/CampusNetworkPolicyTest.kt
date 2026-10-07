package cn.gxnu.campus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校园网身份判定的唯一真值。这条规则原来散在七个文件里，0.8.1 修「连上校园网却显示未连接」时
 * 要改六处 —— 现在只在这里，所以它值得被钉得比别处更死。
 */
class CampusNetworkPolicyTest {

    private val campus = NetworkSnapshot("wifi-1", "GXNU-YC", isWifi = true)
    private val home = NetworkSnapshot("wifi-2", "Home-WiFi", isWifi = true)
    private val unnamed = NetworkSnapshot("wifi-3", "未识别 Wi-Fi", isWifi = true)
    private val blank = NetworkSnapshot("wifi-4", "", isWifi = true)
    private val cellular = NetworkSnapshot("cell-1", "", isWifi = false)

    @Test
    fun theCampusWifiItselfIsAlwaysAllowed() {
        assertEquals(CampusNetworkPolicy.Verdict.Allowed, CampusNetworkPolicy.check(campus))
        assertTrue(CampusNetworkPolicy.allows(campus))
        assertNull(CampusNetworkPolicy.refusal(campus))
    }

    /**
     * 这条就是用户报的故障。Android 没给定位权限、或系统定位开关关着时，任何 Wi-Fi 的名字都会变成
     * 占位符；学校换 SSID 也一样。名字读不出来只说明我们**不知道**，不说明**不是** —— 放行去问
     * 认证页，由它定胜负。
     */
    @Test
    fun aWifiWhoseNameCannotBeReadIsAllowedToAskThePortal() {
        assertEquals("占位符", CampusNetworkPolicy.Verdict.Allowed, CampusNetworkPolicy.check(unnamed))
        assertEquals("空名字", CampusNetworkPolicy.Verdict.Allowed, CampusNetworkPolicy.check(blank))
        assertTrue(CampusNetworkPolicy.allows(unnamed))
        assertNull(CampusNetworkPolicy.refusal(unnamed))
    }

    /** 名字**读得出来**、又不像校园网 —— 这时候名字才是证据，认证不该发到这张网上。 */
    @Test
    fun aNamedNetworkThatIsNotTheCampusOneIsRefused() {
        assertEquals(CampusNetworkPolicy.Verdict.OtherNetwork, CampusNetworkPolicy.check(home))
        assertFalse(CampusNetworkPolicy.allows(home))
        val refusal = CampusNetworkPolicy.refusal(home)
        assertEquals("当前 Wi-Fi「Home-WiFi」不是校园网，请切换到校园网 Wi-Fi。", refusal)
        assertTrue("拒绝的理由就是名字，那就得把名字说出来", refusal!!.contains("Home-WiFi"))
    }

    /**
     * 用户报的故障，0.8.3 修的就是它。他那台 AP 报出来的名字是 `GXNU.YC`（**点号**，截图放大
     * 到 4 倍能看到它落在基线上），而代码里钉的是 `GXNU-YC`（横线）。一个字符之差，精确比较判成
     * 「别的网络」，于是手机明明连在校园网上、应用却让用户「请先连接校园 Wi-Fi」—— 而用户永远
     * 做不到，因为那张网的名字本来就不长这样。
     *
     * 点、横线、下划线、空格、大小写、`-5G` 后缀只是同一个 SSID 的不同写法，都是同一张网。
     */
    @Test
    fun everySpellingOfTheCampusNameIsTheCampusNetwork() {
        listOf(
            "GXNU-YC", "GXNU.YC", "gxnu-yc", "GXNU_YC", " GXNU-YC ", "GXNU.YC-5G",
            "GXNU-YC-5G", "GXNUYC", "GXNU.YC.Student", "广西师范大学"
        ).forEach { spelling ->
            val network = NetworkSnapshot("wifi-x", spelling, isWifi = true)
            assertEquals("「$spelling」是校园网", CampusNetworkPolicy.Verdict.Allowed, CampusNetworkPolicy.check(network))
            assertNull(CampusNetworkPolicy.refusal(network))
        }
    }

    /**
     * 归一化之后不带学校记号的名字仍然是别的网络 —— 放宽拼法不等于什么都放行。
     *
     * 判据是「名字里有没有学校记号」，所以把 AP 起名叫 `GXNU-什么` 一样会被当成校园网。这是
     * 有意的：精确相等的判据挡不住这件事（把 AP 起名叫 `GXNU-YC` 就行），而它挡得住用户
     * 自己那张真实的校园网 —— 后者才是实际发生过的故障。
     */
    @Test
    fun aNameWithoutTheSchoolMarkIsStillAnotherNetwork() {
        listOf("Home-WiFi", "Guest", "CMCC-5G", "TP-LINK_5G", "宿舍路由").forEach { other ->
            assertEquals(
                "「$other」不是校园网",
                CampusNetworkPolicy.Verdict.OtherNetwork,
                CampusNetworkPolicy.check(NetworkSnapshot("wifi-x", other, isWifi = true))
            )
        }
    }

    @Test
    fun aNetworkThatIsNotWifiHasNoLinkToAuthenticateOn() {
        assertEquals(CampusNetworkPolicy.Verdict.NotWifi, CampusNetworkPolicy.check(cellular))
        assertEquals(CampusNetworkPolicy.Verdict.NotWifi, CampusNetworkPolicy.check(null))
        assertEquals(CampusNetworkPolicy.Verdict.NotWifi, CampusNetworkPolicy.check(campus.copy(isWifi = false)))
        assertNotNull(CampusNetworkPolicy.refusal(cellular))
    }

    /**
     * 「没有 Wi-Fi」和「是别的 Wi-Fi」要分开说：用户要做的事不一样 —— 一个是去连 Wi-Fi，
     * 一个是换一张网。混成一句话会让人不知道自己该干什么。
     */
    @Test
    fun theTwoRefusalsTellTheUserDifferentThings() {
        assertNotEquals(
            CampusNetworkPolicy.refusal(cellular),
            CampusNetworkPolicy.refusal(home)
        )
    }

    private fun assertNotEquals(left: String?, right: String?) =
        assertFalse("两条拒绝理由不该是同一句话：$left", left == right)
}
