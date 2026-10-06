package cn.gxnu.campus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import cn.gxnu.campus.core.Provider
import cn.gxnu.campus.network.VisiblePortalSession
import cn.gxnu.campus.ui.CampusActions
import cn.gxnu.campus.ui.ThemeMode

class CampusViewModel(application: Application) : AndroidViewModel(application), CampusActions {
    private val runtime = (application as CampusApplication).runtime
    val uiState = runtime.uiState
    val events = runtime.events
    fun refreshPermissions() = runtime.refreshPermissions()
    override fun connect() = runtime.connect()
    override fun cancelConnect() = runtime.cancelConnect()
    override fun selectProvider(provider: Provider) = runtime.selectProvider(provider)
    override suspend fun saveAccount(account: String, password: String, remember: Boolean) = runtime.saveAccount(account, password, remember)
    override fun deleteAccount() = runtime.deleteAccount()
    override fun setAutoConnect(enabled: Boolean) = runtime.setAutoConnect(enabled)
    override fun setTheme(theme: ThemeMode) = runtime.setTheme(theme)
    override fun requestPermissions() = runtime.requestPermissions()
    override fun openOfficialPortal() = runtime.openOfficialPortal()
    override fun openWifiSettings() = runtime.openWifiSettings()
    override fun clearFeedback(expectedId: Long?) = runtime.clearFeedback(expectedId)

    fun visiblePortalSession(): VisiblePortalSession? = runtime.visiblePortalSession()
    fun onEmbeddedPortalUnavailable() = runtime.onEmbeddedPortalUnavailable()
}
