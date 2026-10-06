package cn.gxnu.campus.network

import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.core.NetworkSnapshot
import cn.gxnu.campus.core.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The embedded page must never be handed a target that could carry a login off the campus Wi-Fi
 * or reuse an account the user has not configured yet.
 */
class VisiblePortalTargetTest {
    private val campus = NetworkSnapshot(id = "wifi-1", ssid = "GXNU-YC", isWifi = true)
    private val home = NetworkSnapshot(id = "wifi-2", ssid = "Home-WiFi", isWifi = true)

    @Test
    fun campusNetworkIsAccepted() {
        assertTrue(campus.isCampus)
    }

    @Test
    fun otherNetworksAreRejected() {
        assertFalse(home.isCampus)
        assertFalse(campus.copy(isWifi = false).isCampus)
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
}
