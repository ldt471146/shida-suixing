package cn.gxnu.campus

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import cn.gxnu.campus.core.ConnectionCoordinator
import cn.gxnu.campus.core.ConnectionStatus
import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.core.NetworkSnapshot
import cn.gxnu.campus.core.Provider
import cn.gxnu.campus.core.PortalFailure
import cn.gxnu.campus.data.CampusPreferences
import cn.gxnu.campus.data.CredentialSession
import cn.gxnu.campus.data.CredentialStore
import cn.gxnu.campus.network.OfficialPortalTransport
import cn.gxnu.campus.network.VisiblePortalSession
import cn.gxnu.campus.network.VisiblePortalTarget
import cn.gxnu.campus.network.WifiEnvironment
import cn.gxnu.campus.runtime.FeedbackEvents
import cn.gxnu.campus.runtime.ManualConnectionIntent
import cn.gxnu.campus.runtime.ManualIntentStage
import cn.gxnu.campus.runtime.ManualReadiness
import cn.gxnu.campus.runtime.RuntimeCredentialSession
import cn.gxnu.campus.runtime.RuntimeDiagnosticPhase
import cn.gxnu.campus.runtime.RuntimeDiagnostics
import cn.gxnu.campus.runtime.RuntimeStorageQueue
import cn.gxnu.campus.service.AutoConnectService
import cn.gxnu.campus.ui.CampusActions
import cn.gxnu.campus.ui.CampusEvent
import cn.gxnu.campus.ui.CampusUiState
import cn.gxnu.campus.ui.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class CampusApplication : Application() {
    // A debug preview never accesses this property and cannot create a real connection controller.
    val runtime: CampusRuntime by lazy { CampusRuntime(this) }
}

/** One application-owned controller is shared by the visible UI and the foreground service. */
class CampusRuntime(private val application: Application) : CampusActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = CampusPreferences(application)
    private val storage = RuntimeStorageQueue()
    private val session = RuntimeCredentialSession(CredentialSession(CredentialStore(application)), storage)
    private val wifi = WifiEnvironment(application, scope)
    private val coordinator = ConnectionCoordinator(scope, OfficialPortalTransport(application, wifi))
    private val eventChannel = Channel<CampusEvent>(Channel.BUFFERED)
    val events: Flow<CampusEvent> = eventChannel.receiveAsFlow()
    private val feedback = FeedbackEvents()
    private val diagnostics = RuntimeDiagnostics()
    private var selectedProvider: Provider? = null
    private var theme = ThemeMode.LIGHT
    private var wantsAuto = false
    private var initializing = true
    private var foregroundRunning = false
    private var serviceStarting = false
    private var configurationChanges = 0
    private var accountChanges = 0
    private var accountRevision = 0L
    private var providerRevision = 0L
    private var autoRevision = 0L
    private var themeRevision = 0L
    private var permissionRefresh: Job? = null
    private var preparingStartedAt: Long? = null
    private var continuingManual = false
    private val mutableUiState = MutableStateFlow(CampusUiState())
    val uiState: StateFlow<CampusUiState> = mutableUiState.asStateFlow()

    private val initialization = scope.async(start = CoroutineStart.LAZY) {
        val accountStarted = RuntimeDiagnostics.now()
        val restored = session.restore()
        diagnostics.record(RuntimeDiagnosticPhase.RESTORE_ACCOUNT, accountStarted, restored)
        val optionsStarted = RuntimeDiagnostics.now()
        try {
            val options = storage.run {
                val provider = preferences.provider
                val storedTheme = preferences.theme
                var auto = preferences.autoConnect
                if (!session.state.value.remembered) {
                    preferences.autoConnect = false
                    auto = false
                }
                RestoredOptions(provider, storedTheme, auto)
            }
            if (providerRevision == 0L) selectedProvider = options.provider
            if (themeRevision == 0L) theme = options.theme
            if (autoRevision == 0L) wantsAuto = options.auto
            diagnostics.record(RuntimeDiagnosticPhase.RESTORE_OPTIONS, optionsStarted)
        } catch (_: Exception) {
            diagnostics.record(RuntimeDiagnosticPhase.RESTORE_OPTIONS, optionsStarted, false)
            feedback.publish("设备设置暂时无法读取，请重新选择供应商和自动连接设置。")
        }
        if (!restored) feedback.publish("已保存账户暂时无法解密，请重新填写校园网账号。")
        initializing = false
        updateCoordinator()
        render()
    }

    private val manualIntent = ManualConnectionIntent(
        scope = scope,
        refresh = {
            try {
                withTimeout(18_000) {
                    initialization.await()
                    wifi.start()
                    wifi.refreshAndAwait()
                    updateCoordinator()
                    if (networkPermissionReady()) ManualReadiness.READY else ManualReadiness.NEED_PERMISSION
                }
            } catch (_: TimeoutCancellationException) {
                ManualReadiness.UNAVAILABLE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { ManualReadiness.UNAVAILABLE }
        },
        onPreparing = {
            preparingStartedAt = RuntimeDiagnostics.now()
            feedback.clear()
            diagnostics.record(RuntimeDiagnosticPhase.PREPARING)
            render()
        },
        onPermissionRequired = { requestDialog ->
            diagnostics.record(RuntimeDiagnosticPhase.NEED_PERMISSION, succeeded = false)
            feedback.publish(permissionMessage())
            updateCoordinator()
            render()
            if (requestDialog) eventChannel.trySend(CampusEvent.RequestPermissions)
        },
        onContinue = {
            preparingStartedAt = null
            continuingManual = true
            updateCoordinator()
            coordinator.connect()
            continuingManual = false
            val connection = coordinator.state.value
            if (connection.status !in runningStatuses && connection.status !in connectionTerminals) {
                feedback.publish(connectionMessage())
            }
            render()
        },
        onUnavailable = {
            diagnostics.record(RuntimeDiagnosticPhase.PREPARING, preparingStartedAt ?: RuntimeDiagnostics.now(), false)
            feedback.publish("校园 Wi-Fi 识别失败，请确认网络后重试。")
            render()
        }
    )

    init {
        scope.launch {
            coordinator.state.collect { connection ->
                feedback.observeConnection(connection)
                diagnostics.observe(connection)
                render()
            }
        }
        scope.launch { wifi.state.collect { updateCoordinator(); render() } }
        scope.launch { session.state.collect { updateCoordinator(); render() } }
        initialization.start()
    }

    /** Called by a visible Activity after both permission results and settings/onResume. */
    fun refreshPermissions() {
        if (manualIntent.pending) {
            manualIntent.resumeAfterPermissionChange()
            return
        }
        permissionRefresh?.cancel()
        permissionRefresh = scope.launch {
            initialization.await()
            wifi.start()
            wifi.refreshAndAwait()
            if (!networkPermissionReady() || !WifiEnvironment.hasNotificationPermission(application)) {
                if (foregroundRunning || serviceStarting) stopAutomaticService()
            }
            activateAutomaticFromUser(requestMissingPermissions = false)
            updateCoordinator()
            render()
        }
    }

    override fun connect() {
        if (configurationChanges > 0) {
            feedback.publish("账号或设置正在保存，完成后即可连接。")
            render()
            return
        }
        permissionRefresh?.cancel()
        manualIntent.request()
    }

    override fun cancelConnect() {
        val pendingManual = manualIntent.pending
        manualIntent.cancel()
        preparingStartedAt = null
        coordinator.cancel(userInitiated = true)
        if (pendingManual || coordinator.state.value.attemptId <= 0) feedback.publish("本次连接已取消。")
        feedback.observeConnection(coordinator.state.value)
        render()
    }

    override fun selectProvider(provider: Provider) {
        val ticket = ++providerRevision
        selectedProvider = provider
        beginConfigurationChange()
        scope.launch {
            initialization.await()
            val started = RuntimeDiagnostics.now()
            var saved = false
            try {
                storage.run { preferences.provider = provider }
                saved = true
            } catch (_: Exception) {
                if (ticket == providerRevision) {
                    feedback.publish("供应商选择暂未保存，本次仍使用当前选择，请重试保存。")
                }
            } finally {
                diagnostics.record(RuntimeDiagnosticPhase.PROVIDER, started, saved)
                finishConfigurationChange(requestPermissions = saved && ticket == providerRevision)
            }
        }
    }

    override suspend fun saveAccount(account: String, password: String, remember: Boolean): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (account.isBlank() || password.isEmpty()) {
                feedback.publish("请填写校园网账号和密码。")
                render()
                return@withContext false
            }
            if (account.length > 1024 || password.length > 8192) {
                feedback.publish("输入过长，请检查账号和密码。")
                render()
                return@withContext false
            }
            if (accountChanges > 0) {
                feedback.publish("账号正在保存，请稍候。")
                render()
                return@withContext false
            }
            val ticket = ++accountRevision
            val autoTicket = ++autoRevision
            accountChanges++
            beginConfigurationChange()
            stopAutomaticService()
            // The app-owned task completes persistence even if the form leaves composition while awaiting it.
            scope.async {
                initialization.await()
                val nextAuto = remember && (wantsAuto || !session.state.value.remembered)
                val started = RuntimeDiagnostics.now()
                val saved = session.save(Credentials(account.trim(), password), remember,
                    beforePersist = { preferences.autoConnect = false }) {
                    preferences.autoConnect = nextAuto
                }
                diagnostics.record(RuntimeDiagnosticPhase.SAVE_ACCOUNT, started, saved)
                val current = ticket == accountRevision
                if (current) {
                    if (autoTicket == autoRevision) wantsAuto = saved && nextAuto
                    if (saved) feedback.publish(if (remember)
                        "校园网账号已安全保存，选择供应商后即可连接。"
                        else "账号仅用于本次会话，自动连接已关闭。")
                    else {
                        wantsAuto = false
                        stopAutomaticService()
                        feedback.publish("安全保存失败，请重试；输入内容仍保留在表单中。")
                    }
                }
                accountChanges--
                finishConfigurationChange(requestPermissions = saved && current && wantsAuto)
                saved && current
            }.await()
        }

    override fun deleteAccount() {
        val ticket = ++accountRevision
        ++providerRevision
        ++autoRevision
        accountChanges++
        wantsAuto = false
        selectedProvider = null
        beginConfigurationChange()
        stopAutomaticService()
        scope.launch {
            initialization.await()
            val started = RuntimeDiagnostics.now()
            val deleted = session.delete { preferences.clearAccountOptions() }
            diagnostics.record(RuntimeDiagnosticPhase.DELETE_ACCOUNT, started, deleted)
            if (ticket == accountRevision) feedback.publish(if (deleted)
                "校园网账号与自动连接配置已删除。"
                else "删除未完整保存，请重试。当前连接任务已取消。")
            accountChanges--
            finishConfigurationChange()
        }
    }

    override fun setAutoConnect(enabled: Boolean) {
        if (enabled && !session.state.value.remembered) {
            feedback.publish("请先在账号页开启安全保存，再启用后台自动连接。")
            render()
            return
        }
        val ticket = ++autoRevision
        wantsAuto = enabled
        beginConfigurationChange()
        if (!enabled) stopAutomaticService()
        scope.launch {
            initialization.await()
            val started = RuntimeDiagnostics.now()
            var saved = false
            try {
                storage.run { preferences.autoConnect = enabled }
                saved = true
                if (ticket == autoRevision) feedback.publish(if (enabled)
                    "自动连接已开启；强制停止或重启后，请重新打开 App 恢复。"
                    else "自动连接已暂停。")
            } catch (_: Exception) {
                if (ticket == autoRevision) {
                    wantsAuto = false
                    stopAutomaticService()
                    feedback.publish("自动连接设置暂未保存，本次已暂停，请重试。")
                }
            } finally {
                diagnostics.record(RuntimeDiagnosticPhase.AUTO_CONNECT, started, saved)
                finishConfigurationChange(requestPermissions = saved && enabled && ticket == autoRevision)
            }
        }
    }

    override fun setTheme(theme: ThemeMode) {
        val ticket = ++themeRevision
        this.theme = theme
        render()
        scope.launch {
            initialization.await()
            val started = RuntimeDiagnostics.now()
            var saved = false
            try { storage.run { preferences.theme = theme }; saved = true }
            catch (_: Exception) {
                if (ticket == themeRevision) feedback.publish("主题已切换但未能保存，重新打开 App 可能恢复上次设置，请重试。")
            }
            diagnostics.record(RuntimeDiagnosticPhase.THEME, started, saved)
            render()
        }
    }

    override fun requestPermissions() {
        feedback.publish(permissionMessage())
        render()
        eventChannel.trySend(CampusEvent.RequestPermissions)
    }
    override fun openOfficialPortal() { eventChannel.trySend(CampusEvent.OpenOfficialPortal) }
    override fun openWifiSettings() { eventChannel.trySend(CampusEvent.OpenWifiSettings) }
    override fun clearFeedback(expectedId: Long?) { feedback.clear(expectedId); render() }

    /**
     * The embedded school page for the current campus network. It is null until the network and
     * saved credentials are both usable, so the page can never carry a login to another Wi-Fi.
     */
    fun visiblePortalSession(): VisiblePortalSession? {
        val target = embeddedPortalTarget(
            initializing = initializing,
            configurationChanges = configurationChanges,
            network = wifi.state.value.network,
            credentials = session.state.value.credentials,
            provider = selectedProvider
        ) ?: return null
        return VisiblePortalSession(application, wifi, target)
    }

    fun onEmbeddedPortalUnavailable() {
        feedback.publish("请先连接 GXNU-YC 并设置校园网账号和供应商，再打开学校认证页面。")
        render()
    }

    /**
     * The embedded page left the screen. Its result is evidence for the runtime, not a status the
     * runtime may copy: the network is read again and [ConnectionCoordinator] - which owns 在线 -
     * re-runs its own verification before publishing anything, so the home screen cannot keep a
     * stale result. A page that never confirmed online only refreshes, because a manual login may
     * still be in progress in the user's hands.
     */
    fun onEmbeddedPortalClosed(verifiedOnline: Boolean) {
        refreshPermissions()
        // A pending manual attempt publishes its own outcome, and a running one must not be doubled.
        if (!verifiedOnline || manualIntent.pending) return
        val refreshed = permissionRefresh ?: return
        scope.launch {
            refreshed.join()
            coordinator.connect()
        }
    }

    internal fun mayRunForegroundService(): Boolean = !initializing && configurationChanges == 0 &&
        wantsAuto && session.state.value.remembered && session.state.value.credentials != null &&
        selectedProvider != null && networkPermissionReady() &&
        WifiEnvironment.hasNotificationPermission(application)

    internal fun onForegroundStarted() {
        serviceStarting = false
        foregroundRunning = true
        wifi.start()
        updateCoordinator()
        render()
    }

    internal fun onForegroundStopped() {
        serviceStarting = false
        foregroundRunning = false
        // update(autoConnect=false) invalidates only an automatic attempt; a manual one can continue.
        updateCoordinator()
        render()
    }

    internal fun onForegroundFailed() {
        serviceStarting = false
        foregroundRunning = false
        diagnostics.record(RuntimeDiagnosticPhase.SERVICE, succeeded = false)
        feedback.publish("后台监听未能启动，请保持 App 打开后重试；也可继续手动连接。")
        updateCoordinator()
        render()
    }

    internal fun onForegroundPermissionLost() {
        serviceStarting = false
        foregroundRunning = false
        feedback.publish(if (!wantsAuto || !session.state.value.remembered || selectedProvider == null)
            "自动连接已暂停。"
            else if (!networkPermissionReady()) permissionMessage()
            else "通知权限未开启，后台自动连接已暂停；仍可手动连接。")
        updateCoordinator()
        render()
    }

    private fun activateAutomaticFromUser(requestMissingPermissions: Boolean) {
        if (initializing || configurationChanges > 0 || !wantsAuto || !session.state.value.remembered) return
        if (!networkPermissionReady() || !WifiEnvironment.hasNotificationPermission(application)) {
            stopAutomaticService()
            if (requestMissingPermissions) {
                feedback.publish(if (!networkPermissionReady()) permissionMessage()
                    else "通知权限未开启，后台自动连接已暂停；仍可手动连接。")
                render()
                eventChannel.trySend(CampusEvent.RequestPermissions)
            }
            return
        }
        if (selectedProvider != null && !foregroundRunning && !serviceStarting) {
            try {
                serviceStarting = true
                ContextCompat.startForegroundService(application, Intent(application, AutoConnectService::class.java))
            } catch (_: RuntimeException) { onForegroundFailed() }
        }
    }

    private fun stopAutomaticService() {
        serviceStarting = false
        foregroundRunning = false
        application.stopService(Intent(application, AutoConnectService::class.java))
    }

    private fun beginConfigurationChange() {
        configurationChanges++
        manualIntent.cancel()
        preparingStartedAt = null
        permissionRefresh?.cancel()
        coordinator.cancel()
        updateCoordinator()
        render()
    }

    private fun finishConfigurationChange(requestPermissions: Boolean = false) {
        configurationChanges--
        updateCoordinator()
        render()
        if (configurationChanges == 0) {
            activateAutomaticFromUser(requestPermissions)
            refreshPermissions()
        }
    }

    private fun networkPermissionReady() = WifiEnvironment.hasNetworkPermissions(application) &&
        WifiEnvironment.isLocationEnabled(application)

    private fun permissionMessage(): String = when {
        !WifiEnvironment.hasNetworkPermissions(application) && !WifiEnvironment.isLocationEnabled(application) ->
            "请允许精确位置和附近 Wi-Fi 权限，并开启系统位置开关，以识别 GXNU-YC。"
        !WifiEnvironment.isLocationEnabled(application) -> "请开启系统位置开关，以识别校园 Wi-Fi 名称。"
        else -> "请允许精确位置和附近 Wi-Fi 权限，以确认当前连接的是 GXNU-YC。"
    }

    private fun updateCoordinator() {
        val environment = wifi.state.value
        if (foregroundRunning && !mayRunForegroundService()) {
            stopAutomaticService()
            if (configurationChanges == 0) feedback.publish(if (!networkPermissionReady()) permissionMessage()
                else "通知权限未开启，后台自动连接已暂停；仍可手动连接。")
        }
        val credentialState = session.state.value
        val credentials = credentialState.credentials.takeIf {
            !initializing && !credentialState.changing && configurationChanges == 0
        }
        coordinator.update(environment.network, credentials, selectedProvider,
            permissionGranted = networkPermissionReady() && environment.permissionGranted,
            autoConnect = !manualIntent.pending && !continuingManual && !initializing &&
                configurationChanges == 0 && wantsAuto && foregroundRunning && credentialState.remembered)
    }

    private fun connectionMessage(): String = when {
        coordinator.state.value.status == ConnectionStatus.NEED_PERMISSION -> permissionMessage()
        else -> coordinator.state.value.message
    }

    private fun render() {
        val connection = coordinator.state.value
        val credentialState = session.state.value
        val status = when (manualIntent.stage) {
            ManualIntentStage.PREPARING -> ConnectionStatus.PREPARING
            ManualIntentStage.WAITING_PERMISSION -> ConnectionStatus.NEED_PERMISSION
            ManualIntentStage.FAILED -> ConnectionStatus.UNREACHABLE
            ManualIntentStage.NONE -> connection.status
        }
        val message = when (manualIntent.stage) {
            ManualIntentStage.PREPARING -> if (initializing) "正在恢复账号并识别校园 Wi-Fi…" else "正在识别当前 Wi-Fi 并准备连接…"
            ManualIntentStage.WAITING_PERMISSION -> permissionMessage()
            ManualIntentStage.FAILED -> "校园 Wi-Fi 识别失败，请确认网络后重试。"
            ManualIntentStage.NONE -> connectionMessage()
        }
        mutableUiState.value = CampusUiState(
            status = status,
            failure = when (manualIntent.stage) {
                ManualIntentStage.NONE -> connection.failure
                ManualIntentStage.FAILED -> PortalFailure.UNREACHABLE
                else -> null
            },
            attemptId = connection.attemptId,
            connectionStartedAtMillis = if (manualIntent.stage in setOf(ManualIntentStage.PREPARING, ManualIntentStage.FAILED)) preparingStartedAt
                else connection.connectionStartedAtMillis,
            message = message,
            wifiName = wifi.state.value.network?.ssid ?: "未连接 Wi-Fi",
            accountConfigured = credentialState.credentials != null,
            maskedAccount = maskAccount(credentialState.credentials?.account),
            accountSaved = credentialState.remembered,
            selectedProvider = selectedProvider,
            autoConnect = wantsAuto,
            autoRunning = foregroundRunning,
            theme = theme,
            feedback = feedback.message,
            feedbackId = feedback.id,
            initializing = initializing,
            accountSaving = accountChanges > 0 || credentialState.changing,
            diagnosticLines = diagnostics.lines
        )
    }

    private fun maskAccount(value: String?): String = when {
        value.isNullOrEmpty() -> "尚未设置"
        value.length <= 4 -> value.take(1) + "•••"
        else -> value.take(2) + "••••" + value.takeLast(2)
    }

    private data class RestoredOptions(val provider: Provider?, val theme: ThemeMode, val auto: Boolean)
    private companion object {
        val runningStatuses = setOf(ConnectionStatus.PREPARING, ConnectionStatus.CHECKING,
            ConnectionStatus.AUTHENTICATING, ConnectionStatus.VERIFYING)
        val connectionTerminals = setOf(ConnectionStatus.ONLINE, ConnectionStatus.AUTH_ERROR,
            ConnectionStatus.UNREACHABLE, ConnectionStatus.CANCELLED)
    }
}

/**
 * Decides which target the embedded school page may be handed, or nothing at all. The page is the
 * only part of the app that fills in the school's own form, so opening it on another Wi-Fi or with
 * account data the runtime is not settled on would put the user's login off the campus network.
 * Kept as a pure function so the refusal cases are pinned by tests rather than by the UI.
 */
internal fun embeddedPortalTarget(
    initializing: Boolean,
    configurationChanges: Int,
    network: NetworkSnapshot?,
    credentials: Credentials?,
    provider: Provider?
): VisiblePortalTarget? {
    // Changes in flight invalidate the runtime's view of both the network and the account.
    if (initializing || configurationChanges > 0) return null
    // Any Wi-Fi the caller is about to authenticate on qualifies — see readyState(): a name that was
    // never read, or a school that renamed its SSID, must not lock the user out of the portal page.
    val campus = network?.takeIf { it.isWifi } ?: return null
    val account = credentials ?: return null
    val selected = provider ?: return null
    return VisiblePortalTarget(campus, account, selected)
}
