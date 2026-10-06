package cn.gxnu.campus.ui

import cn.gxnu.campus.core.ConnectionStatus
import cn.gxnu.campus.core.PortalFailure
import cn.gxnu.campus.core.Provider

enum class ThemeMode(val title: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

data class CampusUiState(
    val status: ConnectionStatus = ConnectionStatus.NO_WIFI,
    val failure: PortalFailure? = null,
    val attemptId: Long = 0,
    val connectionStartedAtMillis: Long? = null,
    val message: String = "先连接校园 Wi-Fi，再来这里认证。",
    val wifiName: String = "未连接 Wi-Fi",
    val accountConfigured: Boolean = false,
    val maskedAccount: String = "尚未设置",
    val accountSaved: Boolean = false,
    val selectedProvider: Provider? = null,
    val autoConnect: Boolean = false,
    val autoRunning: Boolean = false,
    val theme: ThemeMode = ThemeMode.LIGHT,
    val feedback: String? = null,
    val feedbackId: Long = 0,
    val initializing: Boolean = true,
    val accountSaving: Boolean = false,
    val diagnosticLines: List<String> = emptyList(),
    val isPreview: Boolean = false
)

interface CampusActions {
    fun connect()
    fun cancelConnect()
    fun selectProvider(provider: Provider)
    suspend fun saveAccount(account: String, password: String, remember: Boolean): Boolean
    fun deleteAccount()
    fun setAutoConnect(enabled: Boolean)
    fun setTheme(theme: ThemeMode)
    fun requestPermissions()
    fun openOfficialPortal()
    fun openWifiSettings()
    fun clearFeedback(expectedId: Long? = null)
}

sealed interface CampusEvent {
    data object RequestPermissions : CampusEvent
    data object OpenOfficialPortal : CampusEvent
    data object OpenWifiSettings : CampusEvent
}
