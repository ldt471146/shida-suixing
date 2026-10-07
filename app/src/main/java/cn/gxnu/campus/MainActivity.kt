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
import androidx.compose.runtime.getValue
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
import cn.gxnu.campus.ui.UpdateController
import cn.gxnu.campus.ui.screens.OfficialPortalScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val campusViewModel: CampusViewModel by viewModels()
    private var preview = false

    /**
     * The school page the user is looking at. It is owned by the Activity that shows it and by
     * nothing else, and the Activity is not recreated while it is open (see the config changes
     * declared for it in AndroidManifest), so the live WebView, the half-typed form, its captcha
     * and the watch that verifies this Wi-Fi all stay exactly as they were across a rotation.
     *
     * A rotation used to end the attempt instead: the recreated Activity came back with no page,
     * the old composition's teardown ran [VisiblePortalSession.destroy] - which wipes WebStorage
     * and takes the typed login with it - and nothing ever reached
     * [CampusRuntime.onEmbeddedPortalClosed], so the home status was left unsettled.
     *
     * The page is deliberately not saved to a [android.os.Bundle] or retained across recreation:
     * after process death a handle that came back would point at a WebView that died with the old
     * process, and a page that looks open while its session is gone is worse than the home screen
     * asking for one tap. When the host really does go away while the page is open, [onDestroy]
     * settles that attempt once, from the evidence it had gathered.
     */
    private val embeddedPortal = EmbeddedPortalAttempt<VisiblePortalSession> { portalEvidence(it.completion.state.value) }
    private val updateController by lazy { UpdateController.of(application) }
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
            val updates = updateController
            // One check per app start; the controller keeps the result for the rest of the process.
            updates.checkOnLaunch()
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
                val updateState by updates.state.collectAsStateWithLifecycle()
                CampusApp(model.uiState.collectAsStateWithLifecycle().value, model, updates, updateState)
                embeddedPortal.current?.let { session ->
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
     *
     * The attempt is consumed before the runtime is told about it, so an attempt settles exactly
     * once however many exits lead here; a rotation is not one of them, because a rotation no
     * longer ends the page at all.
     */
    private fun closeEmbeddedPortal() {
        val verified = embeddedPortal.settle() ?: return
        (application as CampusApplication).runtime.onEmbeddedPortalClosed(verified)
    }

    private fun openEmbeddedPortal(model: CampusViewModel) {
        val session = model.visiblePortalSession()
        if (session == null) {
            model.onEmbeddedPortalUnavailable()
            return
        }
        embeddedPortal.opened(session)
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
        if (preview) return
        campusViewModel.refreshPermissions()
        // The unknown-sources setting is a separate screen, so the install resumes here.
        updateController.onResume()
    }

    /**
     * A school page that is still open here lost the WebView it needs with this Activity - a
     * configuration this Activity does not handle, the task being removed, or the process going
     * away - so the attempt ends with it instead of disappearing unsettled. A page that was already
     * closed was consumed by [closeEmbeddedPortal], so this settles nothing a second time.
     */
    override fun onDestroy() {
        super.onDestroy()
        closeEmbeddedPortal()
    }
}

/**
 * What a closing school page hands the runtime: its own conclusion as evidence, never as a status.
 * Only the same Wi-Fi's HTTPS 204 probe means online, and the runtime re-runs that probe itself, so
 * a page that was still checking or had given up is not accepted as success here.
 */
internal fun portalEvidence(completion: PortalCompletion): Boolean = completion is PortalCompletion.Online

/**
 * The one embedded attempt this Activity is showing, and the single settlement that ends it.
 *
 * Consuming the page before reporting it is what keeps one attempt to one settlement, whichever
 * exit reaches it first: the page's own back button, the system back, the auto-return after a
 * verified login, and the host being destroyed all land in [settle], and a repeat finds nothing
 * left to settle. The state is Compose state because the page is drawn from it.
 */
internal class EmbeddedPortalAttempt<T : Any>(private val evidence: (T) -> Boolean) {
    private val state = mutableStateOf<T?>(null)

    val current: T? get() = state.value

    fun opened(page: T) {
        state.value = page
    }

    /** Ends the open attempt with the evidence it gathered, or null when there is none to end. */
    fun settle(): Boolean? {
        val page = state.value ?: return null
        state.value = null
        return evidence(page)
    }
}
