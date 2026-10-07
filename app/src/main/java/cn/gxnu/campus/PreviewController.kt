package cn.gxnu.campus

import cn.gxnu.campus.core.ConnectionStatus
import cn.gxnu.campus.core.PortalFailure
import cn.gxnu.campus.core.Provider
import cn.gxnu.campus.runtime.RuntimeDiagnostics
import cn.gxnu.campus.ui.CampusActions
import cn.gxnu.campus.ui.CampusUiState
import cn.gxnu.campus.ui.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Debug-only screen preview. This controller has no storage or network dependencies. */
internal class PreviewController(private val scope: CoroutineScope, initial: ConnectionStatus) : CampusActions {
    private val mutable = MutableStateFlow(CampusUiState(
        status = initial,
        failure = when (initial) {
            ConnectionStatus.AUTH_ERROR -> PortalFailure.ACCOUNT
            ConnectionStatus.UNREACHABLE -> PortalFailure.UNREACHABLE
            else -> null
        },
        attemptId = if (initial in previewAttemptStatuses) 1 else 0,
        connectionStartedAtMillis = if (initial in previewAttemptStatuses) RuntimeDiagnostics.now() else null,
        message = previewMessage(initial),
        wifiName = when (initial) {
            ConnectionStatus.NO_WIFI -> "未连接 Wi-Fi"
            ConnectionStatus.OUTSIDE_CAMPUS -> "Home-WiFi"
            else -> "GXNU.YC"
        },
        accountConfigured = initial != ConnectionStatus.NEED_ACCOUNT,
        maskedAccount = "202***0001",
        accountSaved = initial != ConnectionStatus.NEED_ACCOUNT,
        selectedProvider = if (initial == ConnectionStatus.NEED_PROVIDER) null else Provider.CAMPUS,
        autoConnect = initial != ConnectionStatus.NEED_ACCOUNT,
        autoRunning = initial !in setOf(ConnectionStatus.NEED_ACCOUNT, ConnectionStatus.NEED_PROVIDER, ConnectionStatus.NEED_PERMISSION),
        theme = ThemeMode.LIGHT,
        initializing = false,
        diagnosticLines = listOf("预览模式 · 未执行真实网络和存储操作"),
        isPreview = true
    ))
    val state = mutable.asStateFlow()
    private var pending: Job? = null
    private var accountRevision = 0L

    override fun connect() {
        if (pending?.isActive == true) return
        val attemptId = mutable.value.attemptId + 1
        mutable.update { it.copy(status = ConnectionStatus.PREPARING, message = "正在演示校园 Wi-Fi 识别…",
            failure = null, attemptId = attemptId, connectionStartedAtMillis = RuntimeDiagnostics.now(), feedback = null) }
        pending = scope.launch {
            delay(250)
            mutable.update { it.copy(status = ConnectionStatus.CHECKING, message = "正在演示当前校园 Wi-Fi 上网检查…") }
            delay(450)
            mutable.update { it.copy(status = ConnectionStatus.AUTHENTICATING, message = "正在演示账户认证…") }
            delay(700)
            mutable.update { it.copy(status = ConnectionStatus.VERIFYING, message = "正在演示网络检查…") }
            delay(700)
            mutable.update { it.copy(status = ConnectionStatus.ONLINE, message = "预览连接成功状态；未执行真实认证。",
                feedback = "预览连接成功状态；未执行真实认证。", feedbackId = it.feedbackId + 1) }
        }
    }

    override fun cancelConnect() {
        pending?.cancel()
        pending = null
        mutable.update { it.copy(status = ConnectionStatus.CANCELLED, failure = null,
            message = "预览连接已取消。", feedback = "预览连接已取消。", feedbackId = it.feedbackId + 1) }
    }

    override fun selectProvider(provider: Provider) {
        pending?.cancel()
        mutable.update { it.copy(selectedProvider = provider, status = ConnectionStatus.READY, failure = null) }
    }

    override suspend fun saveAccount(account: String, password: String, remember: Boolean): Boolean {
        if (mutable.value.accountSaving || account.isBlank() || password.isEmpty()) return false
        pending?.cancel()
        val ticket = ++accountRevision
        mutable.update { it.copy(accountSaving = true) }
        return try {
            delay(120)
            if (ticket != accountRevision) return false
            val value = account.trim()
            val mask = if (value.length > 4) value.take(2) + "***" + value.takeLast(2) else "***"
            mutable.update { it.copy(accountConfigured = true, maskedAccount = mask,
                accountSaved = remember, status = ConnectionStatus.READY,
                failure = null, accountSaving = false, autoConnect = remember, autoRunning = remember,
                feedback = "预览账号已更新，仅用于界面演示。", feedbackId = it.feedbackId + 1) }
            true
        } finally {
            if (ticket == accountRevision) mutable.update { it.copy(accountSaving = false) }
        }
    }

    override fun deleteAccount() {
        pending?.cancel()
        ++accountRevision
        mutable.update { it.copy(accountConfigured = false, accountSaved = false,
            maskedAccount = "尚未设置", selectedProvider = null, autoConnect = false,
            autoRunning = false, status = ConnectionStatus.NEED_ACCOUNT, failure = null, accountSaving = false,
            feedback = "预览账号已移除。", feedbackId = it.feedbackId + 1) }
    }
    override fun setAutoConnect(enabled: Boolean) {
        mutable.update { it.copy(autoConnect = enabled, autoRunning = enabled) }
    }
    override fun setTheme(theme: ThemeMode) { mutable.update { it.copy(theme = theme) } }
    override fun requestPermissions() { mutable.update { it.copy(feedback = "预览模式无需申请设备权限。", feedbackId = it.feedbackId + 1) } }
    override fun openOfficialPortal() { mutable.update { it.copy(feedback = "预览模式未打开真实学校认证页面。", feedbackId = it.feedbackId + 1) } }
    override fun openWifiSettings() { mutable.update { it.copy(feedback = "预览模式使用虚拟校园 Wi-Fi。", feedbackId = it.feedbackId + 1) } }
    override fun clearFeedback(expectedId: Long?) {
        mutable.update { if (expectedId == null || expectedId == it.feedbackId) it.copy(feedback = null) else it }
    }
}

private fun previewMessage(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.NO_WIFI -> "请先在系统设置中连接校园 Wi-Fi，再返回这里认证。"
    ConnectionStatus.OUTSIDE_CAMPUS -> "当前连接的是其他 Wi-Fi，请切换到校园 Wi-Fi。"
    ConnectionStatus.NEED_ACCOUNT -> "首次设置校园网账号，保存后不用反复填写。"
    ConnectionStatus.NEED_PROVIDER -> "选择学校认证页面对应的供应商，下次沿用。"
    ConnectionStatus.NEED_PERMISSION -> "允许精确位置与附近 Wi-Fi 权限，以识别校园网络。"
    ConnectionStatus.READY -> "校园 Wi-Fi 已连接，点击下方按钮即可认证。"
    ConnectionStatus.PREPARING -> "正在演示校园 Wi-Fi 识别，请稍候。"
    ConnectionStatus.CHECKING -> "正在演示当前校园 Wi-Fi 是否已经可以上网。"
    ConnectionStatus.AUTHENTICATING -> "正在演示认证过程，请稍候。"
    ConnectionStatus.VERIFYING -> "正在演示校园 Wi-Fi 的外网检查。"
    ConnectionStatus.ONLINE -> "预览联网成功状态，未执行真实校园认证。"
    ConnectionStatus.AUTH_ERROR -> "预览账号或密码错误，请检查后重新连接。"
    ConnectionStatus.UNREACHABLE -> "预览学校入口无响应，可稍后重试或打开认证页面。"
    ConnectionStatus.CANCELLED -> "预览连接已取消，可以重新开始。"
}

private val previewAttemptStatuses = setOf(ConnectionStatus.PREPARING, ConnectionStatus.CHECKING,
    ConnectionStatus.AUTHENTICATING, ConnectionStatus.VERIFYING, ConnectionStatus.ONLINE,
    ConnectionStatus.AUTH_ERROR, ConnectionStatus.UNREACHABLE, ConnectionStatus.CANCELLED)
