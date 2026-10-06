package cn.gxnu.campus.data

import android.content.Context
import cn.gxnu.campus.core.Provider
import cn.gxnu.campus.ui.ThemeMode

/** Only non-sensitive choices belong in this file. Credentials use a separate encrypted vault. */
class CampusPreferences(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences by lazy { applicationContext.getSharedPreferences("campus_options", Context.MODE_PRIVATE) }

    var provider: Provider?
        get() = preferences.getString("provider", null)?.let { name ->
            Provider.entries.firstOrNull { it.name == name }
        }
        set(value) { commit { if (value == null) remove("provider") else putString("provider", value.name) } }

    var theme: ThemeMode
        get() = preferences.getString("theme", null)?.let { name ->
            ThemeMode.entries.firstOrNull { it.name == name }
        } ?: ThemeMode.LIGHT
        set(value) { commit { putString("theme", value.name) } }

    var autoConnect: Boolean
        get() = preferences.getBoolean("auto_connect", false)
        set(value) { commit { putBoolean("auto_connect", value) } }

    fun clearAccountOptions() = commit { remove("provider"); putBoolean("auto_connect", false) }

    private inline fun commit(change: android.content.SharedPreferences.Editor.() -> Unit) {
        if (!preferences.edit().apply(change).commit()) throw PreferenceStorageException()
    }
}

class PreferenceStorageException : Exception("无法保存设备设置，请稍后重试。")
