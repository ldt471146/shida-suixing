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
 * the school's own form, so it may open only while the runtime is settled on the campus Wi-Fi and on
 * a saved account with a chosen provider. Every refusal is pinned here, because a page opened on
 * another Wi-Fi or with an account the runtime is not settled on carries that login off the network.
 */
class EmbeddedPortalAvailabilityTest {
    private val campus = NetworkSnapshot(id = "wifi-1", ssid = "GXNU-YC", isWifi = true)
    private val home = NetworkSnapshot(id = "wifi-2", ssid = "Home-WiFi", isWifi = true)
    private val account = Credentials("20230001", "secret")

    @Test
    fun aSettledCampusSessionWithAnAccountAndProviderOpensThePage() {
        val resolved = target()
        assertEquals(campus, resolved?.network)
        assertEquals(account, resolved?.credentials)
        assertEquals(Provider.CAMPUS, resolved?.provider)
    }

    @Test
    fun anotherWifiNeverOpensThePage() {
        assertNull(target(network = home))
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
