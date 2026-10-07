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

    /** 名字**读得出来**、又不是校园网 —— 这时候名字才是证据，认证不该发到这张网上。 */
    @Test
    fun aNamedNetworkThatIsNotTheCampusOneIsRefused() {
        assertEquals(CampusNetworkPolicy.Verdict.OtherNetwork, CampusNetworkPolicy.check(home))
        assertFalse(CampusNetworkPolicy.allows(home))
        assertEquals("请先连接校园 Wi-Fi。", CampusNetworkPolicy.refusal(home))
    }

    /** 大小写不相等就是别的网络：学校那张网叫 `GXNU-YC`，`gxnu-yc` 不是它。 */
    @Test
    fun aLookalikeNameIsStillAnotherNetwork() {
        listOf("gxnu-yc", "GXNU-YC-5G", "GXNU-YC ", "GXNU_YC").forEach { lookalike ->
            assertEquals(
                "「$lookalike」不是校园网",
                CampusNetworkPolicy.Verdict.OtherNetwork,
                CampusNetworkPolicy.check(NetworkSnapshot("wifi-x", lookalike, isWifi = true))
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
