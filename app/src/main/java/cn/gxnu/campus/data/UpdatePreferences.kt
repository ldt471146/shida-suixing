package cn.gxnu.campus.data

import android.content.Context

/**
 * Bookkeeping for the update notice. Nothing sensitive belongs here, and a lost write is harmless:
 * the worst outcome is one more notice on the next launch, which is why this uses a plain apply
 * instead of the synchronous commit the connection settings require.
 */
class UpdatePreferences(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences by lazy {
        applicationContext.getSharedPreferences("update_options", Context.MODE_PRIVATE)
    }

    /** The release the user waved away, so the same version stops being offered on every launch. */
    var dismissedVersionCode: Int
        get() = preferences.getInt("dismissed_version_code", 0)
        set(value) {
            preferences.edit().putInt("dismissed_version_code", value).apply()
        }
}
