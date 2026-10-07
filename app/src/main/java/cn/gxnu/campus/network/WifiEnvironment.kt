package cn.gxnu.campus.network

import cn.gxnu.campus.core.CampusNetworkPolicy
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.SupplicantState
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.gxnu.campus.core.NetworkSnapshot
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext

data class WifiEnvironmentState(
    val network: NetworkSnapshot? = null,
    val permissionGranted: Boolean = false
)

/** Keeps each snapshot tied to the exact Android Network that produced it. */
class WifiEnvironment(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    private val wifi = this.context.getSystemService(WifiManager::class.java)
    private val networks = WifiNetworkCache<Network>()
    private val _state = MutableStateFlow(WifiEnvironmentState())
    val state: StateFlow<WifiEnvironmentState> = _state.asStateFlow()
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var listenerEpoch = 0
    private var listenerPermissions: Boolean? = null
    @Volatile private var refreshJob: Job? = null
    private val pendingReads = ConcurrentHashMap<Long, Job>()

    @Synchronized fun start() {
        val allowed = hasNetworkPermissions(context) && isLocationEnabled(context)
        // Granting location does not necessarily produce a new capabilities event; re-register to get unredacted WifiInfo.
        if (callback != null && listenerPermissions != allowed) stop()
        if (callback != null) { refresh(); return }
        listenerPermissions = allowed
        val epoch = ++listenerEpoch
        val listener = if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) = updateNetwork(network, epoch, available = true)
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    updateNetwork(network, epoch, capabilities)
                override fun onLost(network: Network) = removeNetwork(network, epoch)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = updateNetwork(network, epoch, available = true)
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    updateNetwork(network, epoch, capabilities)
                override fun onLost(network: Network) = removeNetwork(network, epoch)
            }
        }
        try {
            // Captive Wi-Fi must be observed before it has INTERNET or VALIDATED status.
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            connectivity.registerNetworkCallback(request, listener)
            callback = listener
            refresh()
        } catch (_: SecurityException) {
            _state.value = WifiEnvironmentState(permissionGranted = false)
        }
    }

    @Synchronized fun refresh() {
        if (refreshJob?.isActive == true) return
        val epoch = listenerEpoch
        val refresh = networks.beginRefresh()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val currentNetworks = withContext(Dispatchers.IO) {
                    try { connectivity.allNetworks.toList() } catch (_: SecurityException) { emptyList() }
                }
                if (epoch != listenerEpoch) return@launch
                val liveIds = currentNetworks.map { it.networkHandle.toString() }.toSet()
                networks.prune(refresh, liveIds)
                for (network in currentNetworks) {
                    val ticket = networks.beginRefreshRead(refresh, network.networkHandle.toString())
                    readNetwork(network, null, epoch, ticket)
                }
                // A newer capabilities event may have superseded a direct binder read.
                pendingReads.values.toList().joinAll()
                if (epoch == listenerEpoch) publish()
            } finally {
                val completedJob = currentCoroutineContext()[Job]
                synchronized(this@WifiEnvironment) {
                    if (refreshJob === completedJob) refreshJob = null
                }
            }
        }
        refreshJob = job
        job.start()
    }

    /** Returns after the shared live scan and callbacks queued during that scan have published. */
    suspend fun refreshAndAwait() {
        refresh()
        refreshJob?.join()
    }

    @Synchronized fun stop() {
        listenerEpoch++
        callback?.let { try { connectivity.unregisterNetworkCallback(it) } catch (_: IllegalArgumentException) { } }
        callback = null
        refreshJob?.cancel()
        refreshJob = null
        pendingReads.values.forEach { it.cancel() }
        pendingReads.clear()
        networks.clear()
        _state.value = WifiEnvironmentState(permissionGranted = hasNetworkPermissions(context) && isLocationEnabled(context))
    }

    fun openConnection(snapshot: NetworkSnapshot, url: URL): HttpsURLConnection {
        val entry = networks.entryFor(snapshot)
        // Bound by identity, not by name: the network we are authenticating on is the one the caller
        // handed us. Whether it may carry an authentication at all is CampusNetworkPolicy's call.
        if (!hasNetworkPermissions(context) || !isLocationEnabled(context) ||
            entry == null || !CampusNetworkPolicy.allows(snapshot)) {
            throw TargetNetworkUnavailableException()
        }
        // Never use URL.openConnection or bindProcessToNetwork: mobile data cannot satisfy this request.
        return entry.network.openConnection(url) as HttpsURLConnection
    }

    @Synchronized private fun updateNetwork(network: Network, epoch: Int, capabilities: NetworkCapabilities? = null, available: Boolean = false) {
        if (epoch != listenerEpoch) return
        val ticket = networks.beginRead(network.networkHandle.toString(), available) ?: return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                readNetwork(network, capabilities, epoch, ticket)
                if (epoch == listenerEpoch) publish()
            } finally {
                pendingReads.remove(ticket.revision)
            }
        }
        pendingReads[ticket.revision] = job
        job.start()
    }

    @Synchronized private fun removeNetwork(network: Network, epoch: Int) {
        if (epoch != listenerEpoch) return
        // Remove the usable handle immediately; queued callbacks cannot retain a disconnected network.
        val id = network.networkHandle.toString()
        networks.remove(id)
        scope.launch { if (epoch == listenerEpoch) publish() }
    }

    private suspend fun readNetwork(
        network: Network, provided: NetworkCapabilities?, epoch: Int,
        ticket: WifiNetworkCache.ReadTicket?
    ) {
        val id = network.networkHandle.toString()
        if (ticket == null || epoch != listenerEpoch || !networks.isCurrent(ticket)) return
        val snapshot = withContext(Dispatchers.IO) {
            try {
                val capabilities = provided ?: connectivity.getNetworkCapabilities(network) ?: return@withContext null
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@withContext null
                val info = if (Build.VERSION.SDK_INT >= 29) capabilities.transportInfo as? WifiInfo else null
                val identified = usableSsid(info?.ssid) ?: legacyNameForSingleNetwork(network, epoch)
                // getNetworkCapabilities lacks the callback's location-info flag on some releases.
                val ssid = resolveWifiSsid(id, identified, networks.entry(id)?.snapshot,
                    hasNetworkPermissions(context) && isLocationEnabled(context))
                NetworkSnapshot(id, ssid, true,
                    isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    isCaptivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
            } catch (_: SecurityException) {
                NetworkSnapshot(network.networkHandle.toString(), "未识别 Wi-Fi", true)
            }
        }
        if (epoch != listenerEpoch || !networks.isCurrent(ticket)) return
        if (snapshot == null) networks.apply(ticket, network, null)
        else {
            // Confirm it still exists after the blocking binder read, to prevent resurrecting an onLost handle.
            val stillPresent = withContext(Dispatchers.IO) {
                try { connectivity.getNetworkCapabilities(network) != null } catch (_: SecurityException) { false }
            }
            if (epoch == listenerEpoch) networks.apply(ticket, network, snapshot.takeIf { stillPresent })
        }
    }

    /** Device-wide WifiInfo can only identify this Network when no second live Wi-Fi exists. */
    private fun legacyNameForSingleNetwork(network: Network, epoch: Int): String? {
        val id = network.networkHandle.toString()
        val permissionReady = hasNetworkPermissions(context) && isLocationEnabled(context)
        if (!permissionReady || epoch != listenerEpoch || networks.isLost(id)) return null
        return singleWifiFallback(id, liveWifiIds(), permissionReady, readIdentity = {
            @Suppress("DEPRECATION")
            val identity = wifi.connectionInfo ?: return@singleWifiFallback null
            LegacyWifiIdentity(identity.ssid, identity.networkId, identity.supplicantState == SupplicantState.COMPLETED)
        }, stillMatches = {
            epoch == listenerEpoch && !networks.isLost(id) &&
                hasNetworkPermissions(context) && isLocationEnabled(context) && liveWifiIds() == setOf(id)
        })
    }

    @Suppress("DEPRECATION")
    private fun liveWifiIds(): Set<String> = connectivity.allNetworks.mapNotNull { candidate ->
        candidate.takeIf { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            ?.networkHandle?.toString()
    }.toSet()

    private fun publish() {
        // Prefer the network whose name reads as the campus one, but fall back to any Wi-Fi: when no
        // name can be read, the only honest snapshot is the Wi-Fi we are actually on.
        val network = networks.snapshots().sortedBy { it.id }.let { snapshots ->
            snapshots.firstOrNull { it.isNamedCampus } ?: snapshots.firstOrNull()
        }
        _state.value = wifiEnvironmentState(network, hasNetworkPermissions(context) && isLocationEnabled(context))
    }

    companion object {
        fun requiredPermissions(includeNotifications: Boolean = false): Array<String> = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                if (includeNotifications) add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

        fun hasNetworkPermissions(context: Context): Boolean {
            fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            val location = if (Build.VERSION.SDK_INT >= 29) granted(Manifest.permission.ACCESS_FINE_LOCATION)
                else granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
            return location && (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.NEARBY_WIFI_DEVICES))
        }

        fun isLocationEnabled(context: Context): Boolean = try {
            if (Build.VERSION.SDK_INT >= 28) context.getSystemService(LocationManager::class.java).isLocationEnabled
            else Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0) != Settings.Secure.LOCATION_MODE_OFF
        } catch (_: RuntimeException) { false }

        fun hasNotificationPermission(context: Context): Boolean {
            val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
}

class TargetNetworkUnavailableException : Exception("目标校园 Wi-Fi 已不可用。")

internal data class LegacyWifiIdentity(val ssid: String?, val networkId: Int, val completed: Boolean)

internal fun singleWifiFallback(
    targetId: String,
    liveWifiIds: Set<String>,
    permissionReady: Boolean,
    readIdentity: () -> LegacyWifiIdentity?,
    stillMatches: () -> Boolean
): String? {
    if (!permissionReady || liveWifiIds != setOf(targetId)) return null
    val identity = readIdentity() ?: return null
    if (!identity.completed || identity.networkId < 0 || !stillMatches()) return null
    return usableSsid(identity.ssid)
}

private fun usableSsid(value: String?): String? = value?.removeSurrounding("\"")?.takeUnless {
    it.isBlank() || it == WifiManager.UNKNOWN_SSID || it == "0x"
}

internal fun resolveWifiSsid(
    targetId: String, identifiedSsid: String?, previous: NetworkSnapshot?,
    permissionReady: Boolean
): String {
    val retained = previous?.takeIf { permissionReady && it.id == targetId }?.ssid
    return usableSsid(identifiedSsid) ?: retained ?: "未识别 Wi-Fi"
}

internal fun wifiEnvironmentState(network: NetworkSnapshot?, permissionReady: Boolean): WifiEnvironmentState =
    WifiEnvironmentState(network, permissionReady)
