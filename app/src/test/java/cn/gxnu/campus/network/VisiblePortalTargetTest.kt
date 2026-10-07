package cn.gxnu.campus.network

import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.core.NetworkSnapshot
import cn.gxnu.campus.core.Provider
import cn.gxnu.campus.embeddedPortalTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The embedded page must never be handed a target that could carry a login off the campus Wi-Fi
 * or reuse an account the user has not configured yet.
 */
class VisiblePortalTargetTest {
    private val campus = NetworkSnapshot(id = "wifi-1", ssid = "GXNU-YC", isWifi = true)
    private val home = NetworkSnapshot(id = "wifi-2", ssid = "Home-WiFi", isWifi = true)
    private val unnamed = NetworkSnapshot(id = "wifi-3", ssid = "未识别 Wi-Fi", isWifi = true)

    @Test
    fun campusNetworkIsAccepted() {
        assertTrue(campus.isNamedCampus)
        assertFalse(campus.hasUnknownName)
    }

    /**
     * 名字读不出来（没给定位权限，或学校换了 SSID）时它**不是**「别的 Wi-Fi」：Android 会把
     * 没法核实名字的网络报成同一个占位符，把它当外网就会把明明连在校园网上的用户挡在外面。
     */
    @Test
    fun aWifiWithoutAReadableNameIsNotTreatedAsAnotherNetwork() {
        assertFalse(unnamed.isNamedCampus)
        assertTrue(unnamed.hasUnknownName)
        assertFalse("别的 Wi-Fi 仍然是别的 Wi-Fi", home.hasUnknownName)
    }

    @Test
    fun otherNetworksAreRejected() {
        assertFalse("有名字、且不是校园网，才算外网", home.isNamedCampus)
        assertFalse(campus.copy(isWifi = false).isNamedCampus)
    }

    @Test
    fun targetCarriesExactlyTheConfiguredCredentials() {
        val target = VisiblePortalTarget(campus, Credentials("20230001", "secret"), Provider.CAMPUS)
        assertEquals("20230001", target.credentials.account)
        assertEquals("secret", target.credentials.password)
        assertEquals(Provider.CAMPUS, target.provider)
    }

    @Test
    fun credentialsNeverRenderSecrets() {
        val rendered = Credentials("20230001", "secret").toString()
        assertFalse(rendered.contains("20230001"))
        assertFalse(rendered.contains("secret"))
    }

    @Test
    fun providerSuffixesMatchTheSchoolSelectValues() {
        assertEquals("", Provider.CAMPUS.suffix)
        assertEquals("@ctc", Provider.TELECOM.suffix)
        assertEquals("@cuc", Provider.UNICOM.suffix)
        assertEquals("@cmc", Provider.MOBILE.suffix)
        assertEquals("@gd", Provider.BROADCAST.suffix)
    }

    @Test
    fun thePageOpensForACampusWifiWhoseLoginHasNotHappenedYet() {
        // The page is needed exactly while the Wi-Fi is still behind the portal, so being captive
        // and unvalidated must not refuse it. What it carries is that same snapshot - the same
        // network handle the session pins its requests and its success probe to - so a page opened
        // before a login and one opened after it can never confirm against some other Wi-Fi.
        val account = Credentials("20230001", "secret")
        val captive = campus.copy(isValidated = false, isCaptivePortal = true)
        val target = embeddedPortalTarget(
            initializing = false, configurationChanges = 0,
            network = captive, credentials = account, provider = Provider.CAMPUS
        )
        assertNotNull(target)
        assertEquals(captive, target?.network)

        val online = campus.copy(isValidated = true, isCaptivePortal = false)
        assertEquals(
            online,
            embeddedPortalTarget(false, 0, online, account, Provider.CAMPUS)?.network
        )
    }
}
