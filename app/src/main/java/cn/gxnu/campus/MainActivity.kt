package cn.gxnu.campus

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.ContextCompat
import cn.gxnu.campus.core.ConnectionStatus
import cn.gxnu.campus.network.PortalCompletion
import cn.gxnu.campus.network.VisiblePortalSession
import cn.gxnu.campus.network.WifiEnvironment
import cn.gxnu.campus.ui.CampusApp
import cn.gxnu.campus.ui.CampusEvent
import cn.gxnu.campus.ui.screens.OfficialPortalScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val campusViewModel: CampusViewModel by viewModels()
    private var preview = false
    private val embeddedPortal = mutableStateOf<VisiblePortalSession?>(null)
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { if (!preview) campusViewModel.refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        preview = BuildConfig.DEBUG && intent.getBooleanExtra("campus.preview", false)
        if (preview) {
            val initial = runCatching {
                ConnectionStatus.valueOf(intent.getStringExtra("campus.preview.status") ?: "READY")
            }.getOrDefault(ConnectionStatus.READY)
            val controller = PreviewController(lifecycleScope, initial)
            setContent { CampusApp(controller.state.collectAsStateWithLifecycle().value, controller) }
        } else {
            val model = campusViewModel
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    model.events.collect { event ->
                        when (event) {
                            CampusEvent.RequestPermissions -> requestCampusPermissions()
                            CampusEvent.OpenOfficialPortal -> openEmbeddedPortal(model)
                            CampusEvent.OpenWifiSettings -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        }
                    }
                }
            }
            setContent {
                CampusApp(model.uiState.collectAsStateWithLifecycle().value, model)
                embeddedPortal.value?.let { session ->
                    BackHandler(enabled = true) { closeEmbeddedPortal() }
                    OfficialPortalScreen(session = session, onClose = { closeEmbeddedPortal() })
                }
            }
        }
    }

    /**
     * Closing the page also settles the home status. The page's own conclusion is evidence, not a
     * status, so the runtime re-reads this Wi-Fi and lets the coordinator publish 在线 through its
     * own verification. The preview build never opens this page, so its runtime stays untouched.
     */
    private fun closeEmbeddedPortal() {
        val session = embeddedPortal.value ?: return
        val verified = session.completion.state.value is PortalCompletion.Online
        embeddedPortal.value = null
        (application as CampusApplication).runtime.onEmbeddedPortalClosed(verified)
    }

    private fun openEmbeddedPortal(model: CampusViewModel) {
        val session = model.visiblePortalSession()
        if (session == null) {
            model.onEmbeddedPortalUnavailable()
            return
        }
        embeddedPortal.value = session
    }

    private suspend fun requestCampusPermissions() {
        if (WifiEnvironment.hasNetworkPermissions(this) && !WifiEnvironment.isLocationEnabled(this)) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        val required = WifiEnvironment.requiredPermissions(includeNotifications = true)
        val denied = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (denied.isEmpty()) {
            campusViewModel.refreshPermissions()
            return
        }
        val previous = withContext(Dispatchers.IO) {
            getSharedPreferences("permission-ui", MODE_PRIVATE).getStringSet("requested", emptySet()).orEmpty().toSet()
        }
        if (denied.any { it in previous && !shouldShowRequestPermissionRationale(it) }) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName")))
        } else {
            withContext(Dispatchers.IO) {
                getSharedPreferences("permission-ui", MODE_PRIVATE).edit().putStringSet("requested", previous + denied).apply()
            }
            // Android 12 requires coarse and fine location to be requested together, including a precision upgrade.
            permissions.launch(required)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!preview) campusViewModel.refreshPermissions()
    }
}
