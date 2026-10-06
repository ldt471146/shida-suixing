package cn.gxnu.campus.network

import cn.gxnu.campus.core.NetworkSnapshot
import org.junit.Assert.*
import org.junit.Test

class WifiIdentityFallbackTest {
    // Catches redacted capabilities on the same live handle forgetting its known campus identity.
    @Test fun redactedCallbackRetainsOnlyTheSamePermittedNetworkIdentity() {
        val campus = NetworkSnapshot("wifi-42", "GXNU-YC", true)
        assertEquals("GXNU-YC", resolveWifiSsid("wifi-42", "<unknown ssid>", campus, true))
    }

    // Catches confusing an unknown SSID with denial of the actual platform permissions.
    @Test fun unknownWifiIdentityDoesNotRevokeGrantedPermissions() {
        val unidentified = NetworkSnapshot("wifi-42", "未识别 Wi-Fi", true)
        val state = wifiEnvironmentState(unidentified, true)
        assertTrue(state.permissionGranted)
        assertEquals(unidentified, state.network)
    }

    @Test fun retainedIdentityCannotCrossHandlesOrActualPermissionLoss() {
        val campus = NetworkSnapshot("wifi-42", "GXNU-YC", true)
        assertEquals("未识别 Wi-Fi", resolveWifiSsid("wifi-99", null, campus, true))
        assertEquals("未识别 Wi-Fi", resolveWifiSsid("wifi-42", null, campus, false))
        assertFalse(wifiEnvironmentState(campus, false).permissionGranted)
        assertEquals("Guest", resolveWifiSsid("wifi-42", "Guest", campus, true))
    }

    @Test fun completedWifiCanBeNamedWhenExactlyOneLiveWifiMatchesItsHandle() {
        val name = singleWifiFallback("wifi-42", setOf("wifi-42"), true,
            { LegacyWifiIdentity("\"AndroidWifi\"", 0, true) }, { true })
        assertEquals("AndroidWifi", name)
    }

    @Test fun ambiguousWifiAndMissingPermissionsNeverReadDeviceWideWifiIdentity() {
        val forbiddenRead = { throw AssertionError("device-wide identity must not be read") }
        assertNull(singleWifiFallback("wifi-42", setOf("wifi-42", "wifi-99"), true, forbiddenRead, { true }))
        assertNull(singleWifiFallback("wifi-42", setOf("wifi-99"), true, forbiddenRead, { true }))
        assertNull(singleWifiFallback("wifi-42", emptySet(), true, forbiddenRead, { true }))
        assertNull(singleWifiFallback("wifi-42", setOf("wifi-42"), false, forbiddenRead, { true }))
    }

    @Test fun disconnectAndIncompleteOrRedactedIdentityCannotNameTheNetwork() {
        val live = setOf("wifi-42")
        assertNull(singleWifiFallback("wifi-42", live, true, { LegacyWifiIdentity("GXNU-YC", -1, true) }, { true }))
        assertNull(singleWifiFallback("wifi-42", live, true, { LegacyWifiIdentity("GXNU-YC", 0, false) }, { true }))
        assertNull(singleWifiFallback("wifi-42", live, true, { LegacyWifiIdentity("<unknown ssid>", 0, true) }, { true }))
        assertNull(singleWifiFallback("wifi-42", live, true, { LegacyWifiIdentity("GXNU-YC", 0, true) }, { false }))
    }
}
