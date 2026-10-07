package cn.gxnu.campus

import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.core.NetworkSnapshot
import cn.gxnu.campus.core.Provider
import cn.gxnu.campus.network.VisiblePortalTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The embedded page is the only part of the app that types the user's real school credentials into
 * the school's own form, so it may open only while the runtime is settled on a Wi-Fi and on a saved
 * account with a chosen provider. Every refusal is pinned here, because a page opened on a network
 * the runtime is not settled on carries that login off it.
 *
 * The Wi-Fi's **name** is deliberately not one of the gates. See [aWifiWithNoReadableNameStillOpensThePage].
 */
class EmbeddedPortalAvailabilityTest {
    private val campus = NetworkSnapshot(id = "wifi-1", ssid = "GXNU-YC", isWifi = true)
    private val home = NetworkSnapshot(id = "wifi-2", ssid = "Home-WiFi", isWifi = true)
    private val unnamed = NetworkSnapshot(id = "wifi-3", ssid = "未识别 Wi-Fi", isWifi = true)
    private val account = Credentials("20230001", "secret")

    @Test
    fun aSettledCampusSessionWithAnAccountAndProviderOpensThePage() {
        val resolved = target()
        assertEquals(campus, resolved?.network)
        assertEquals(account, resolved?.credentials)
        assertEquals(Provider.CAMPUS, resolved?.provider)
    }

    /**
     * 用户报的故障就在这里：手机连在校园网上、账号也配好了，但名字没读出来（Android 缺定位权限
     * 时会把名字抹成占位符），页面于是打不开、认证也发不出去。名字读不出来只说明「不知道」。
     */
    @Test
    fun aWifiWithNoReadableNameStillOpensThePage() {
        assertEquals(unnamed, target(network = unnamed)?.network)
    }

    /** 这不是校园网：手机网络、以太网、以及名字读得出但不是校园网的 Wi-Fi。 */
    @Test
    fun aNetworkThatIsNotWifiNeverOpensThePage() {
        assertNull(target(network = home.copy(isWifi = false)))
        assertNull(target(network = campus.copy(ssid = "", isWifi = false)))
    }

    @Test
    fun aCampusNamedSnapshotThatIsNotWifiNeverOpensThePage() {
        assertNull(target(network = campus.copy(isWifi = false)))
    }

    @Test
    fun aMissingNetworkNeverOpensThePage() {
        assertNull(target(network = null))
    }

    @Test
    fun anAccountThatIsNotConfiguredNeverOpensThePage() {
        assertNull(target(credentials = null))
    }

    @Test
    fun aMissingProviderNeverOpensThePage() {
        assertNull(target(provider = null))
    }

    @Test
    fun anUnsettledRuntimeNeverOpensThePageEvenWhenTheRestIsReady() {
        assertNull(target(initializing = true))
        assertNull(target(configurationChanges = 1))
    }

    private fun target(
        initializing: Boolean = false,
        configurationChanges: Int = 0,
        network: NetworkSnapshot? = campus,
        credentials: Credentials? = account,
        provider: Provider? = Provider.CAMPUS
    ): VisiblePortalTarget? =
        embeddedPortalTarget(initializing, configurationChanges, network, credentials, provider)
}
