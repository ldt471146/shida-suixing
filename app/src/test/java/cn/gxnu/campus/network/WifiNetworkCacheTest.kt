package cn.gxnu.campus.network

import cn.gxnu.campus.core.NetworkSnapshot
import org.junit.Assert.*
import org.junit.Test

class WifiNetworkCacheTest {
    private val campus = NetworkSnapshot("wifi-42", "GXNU-YC", true)

    // Catches treating capability metadata as a new physical network and breaking the bound transport.
    @Test fun capabilityChangesKeepTheOriginalTargetBoundToItsWifiHandle() {
        val cache = WifiNetworkCache<String>()
        val original = campus.copy(isValidated = false, isCaptivePortal = true)
        cache.apply(cache.beginRead("wifi-42", available = true)!!, "android-handle-42", original)
        val updated = original.copy(isValidated = true, isCaptivePortal = false)
        cache.apply(cache.beginRead("wifi-42")!!, "android-handle-42", updated)

        assertEquals("android-handle-42", cache.entryFor(original)?.network)
        assertNull(cache.entryFor(original.copy(ssid = "Guest")))
        assertNull(cache.entryFor(original.copy(id = "wifi-99")))
        assertNull(cache.entryFor(original.copy(isWifi = false)))
    }

    @Test fun snapshotForAnotherHandleCannotPopulateTheTargetEntry() {
        val cache = WifiNetworkCache<String>()
        val ticket = cache.beginRead("wifi-42", available = true)!!
        cache.apply(ticket, "android-handle-42", campus.copy(id = "wifi-99"))

        assertTrue(cache.snapshots().isEmpty())
        assertNull(cache.entry("wifi-42"))
    }

    // Catches a binder read completing late and overwriting a newer capability/identity event.
    @Test fun oldReadCannotOverwriteNewIdentityForTheSameHandle() {
        val cache = WifiNetworkCache<String>()
        val old = cache.beginRead("wifi-42", available = true)!!
        val current = cache.beginRead("wifi-42")!!
        cache.apply(current, "android-handle-42", campus)
        cache.apply(old, "android-handle-42", campus.copy(ssid = "Guest"))

        assertEquals("GXNU-YC", cache.entry("wifi-42")?.snapshot?.ssid)
    }

    // Catches an allNetworks scan started before a new handle removing the newly observed network.
    @Test fun oldRefreshCannotEraseNewlyAvailableCampusWifi() {
        val cache = WifiNetworkCache<String>()
        val oldRefresh = cache.beginRefresh()
        val current = cache.beginRead("wifi-42", available = true)!!
        cache.apply(current, "android-handle-42", campus)
        cache.prune(oldRefresh, emptySet())

        assertEquals(campus, cache.entry("wifi-42")?.snapshot)
    }

    // Catches two permission/foreground scans completing in the reverse order.
    @Test fun supersededRefreshCannotDeleteTheResultOfANewerRefresh() {
        val cache = WifiNetworkCache<String>()
        val oldRefresh = cache.beginRefresh()
        val currentRefresh = cache.beginRefresh()
        val current = cache.beginRefreshRead(currentRefresh, "wifi-42")!!
        cache.apply(current, "android-handle-42", campus)
        cache.prune(oldRefresh, emptySet())

        assertEquals(campus, cache.entry("wifi-42")?.snapshot)
        assertNull(cache.beginRefreshRead(oldRefresh, "wifi-42"))
    }

    @Test fun lossCannotResurrectAHandleOrEraseAnotherCampusNetwork() {
        val cache = WifiNetworkCache<String>()
        val old = cache.beginRead("wifi-42", available = true)!!
        val next = cache.beginRead("wifi-99", available = true)!!
        val newCampus = campus.copy(id = "wifi-99")
        cache.apply(next, "android-handle-99", newCampus)
        cache.remove("wifi-42")
        cache.apply(old, "android-handle-42", campus)

        assertNull(cache.entry("wifi-42"))
        assertNull(cache.beginRead("wifi-42"))
        assertEquals(listOf(newCampus), cache.snapshots())
    }

    @Test fun refreshAfterTheLatestEventRemovesADisconnectedHandle() {
        val cache = WifiNetworkCache<String>()
        val ticket = cache.beginRead("wifi-42", available = true)!!
        cache.apply(ticket, "android-handle-42", campus)
        cache.prune(cache.beginRefresh(), emptySet())

        assertTrue(cache.snapshots().isEmpty())
    }

    @Test fun restartingListenerInvalidatesAllOldReadResults() {
        val cache = WifiNetworkCache<String>()
        val old = cache.beginRead("wifi-42", available = true)!!
        cache.clear()
        cache.apply(old, "android-handle-42", campus)

        assertNull(cache.entry("wifi-42"))
    }
}
