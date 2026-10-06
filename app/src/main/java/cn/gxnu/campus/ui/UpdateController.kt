package cn.gxnu.campus.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import cn.gxnu.campus.BuildConfig
import cn.gxnu.campus.data.UpdatePreferences
import cn.gxnu.campus.network.ApkDownload
import cn.gxnu.campus.network.DownloadFailure
import cn.gxnu.campus.network.HttpsReleaseTransport
import cn.gxnu.campus.network.ReleaseCheck
import cn.gxnu.campus.network.ReleaseUpdater
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the update surface currently has to say. */
sealed interface UpdatePhase {
    /** Nothing to surface: no newer release, or the user already waved this one away. */
    data object Hidden : UpdatePhase
    data object Checking : UpdatePhase
    data class Available(val versionName: String, val apkBytes: Long) : UpdatePhase
    data class Downloading(val versionName: String, val downloadedBytes: Long, val totalBytes: Long) : UpdatePhase
    /** The apk is downloaded and verified; only the system installer is left. */
    data class Ready(val versionName: String, val needsInstallPermission: Boolean) : UpdatePhase
    data class Failed(val versionName: String, val message: String) : UpdatePhase
}

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.Hidden,
    /** The outcome of the last manual check, shown on the settings row. Null until one has run. */
    val checkSummary: String? = null,
    val message: String? = null,
    val messageId: Long = 0
)

/** The update surface's actions, kept separate from the connection contract so screens stay thin. */
interface UpdateActions {
    fun checkForUpdate()
    fun downloadUpdate()
    fun cancelDownload()
    fun dismissUpdate()
    fun installUpdate()
    fun clearUpdateMessage(expectedId: Long? = null)
}

/**
 * Owns the whole update path: one check per app start, a cancellable download into the cache, and
 * the handover to the system installer. Nothing here installs anything by itself - Android always
 * asks the user, and this controller only ever starts that dialog.
 */
class UpdateController private constructor(
    private val application: Application,
    private val updater: ReleaseUpdater,
    private val preferences: UpdatePreferences,
    private val versionCode: Int,
    private val updateDir: File
) : UpdateActions {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = mutable.asStateFlow()

    /** The release the current notice is about, kept so a retry does not have to check again. */
    private var offered: ReleaseCheck.Available? = null
    private var downloaded: File? = null
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var checkedOnLaunch = false
    private var awaitingInstallPermission = false

    /**
     * The only automatic check, run once per app start. GitHub's unauthenticated allowance is 60
     * requests per hour per address, so a launch check that also survived activity recreation would
     * be spending the allowance for nothing.
     */
    fun checkOnLaunch() {
        if (checkedOnLaunch) return
        checkedOnLaunch = true
        check(manual = false)
    }

    override fun checkForUpdate() = check(manual = true)

    private fun check(manual: Boolean) {
        if (checkJob?.isActive == true) return
        when (mutable.value.phase) {
            // A running transfer or a file waiting for the installer must not be thrown away by a
            // check that cannot change either of them.
            is UpdatePhase.Downloading -> return
            is UpdatePhase.Ready -> {
                if (manual) publish("安装包已下载，请先完成安装。")
                return
            }
            else -> Unit
        }
        if (manual) mutable.update { it.copy(phase = UpdatePhase.Checking) }
        checkJob = scope.launch { apply(updater.check(versionCode), manual) }
    }

    private fun apply(result: ReleaseCheck, manual: Boolean) {
        if (result is ReleaseCheck.Available) {
            offered = result
            val dismissed = !manual && preferences.dismissedVersionCode == result.version.versionCode
            mutable.update {
                it.copy(
                    phase = if (dismissed) UpdatePhase.Hidden
                    else UpdatePhase.Available(result.version.versionName, result.apkBytes)
                )
            }
            if (manual) publish("发现新版本 ${result.version.versionName}。")
            return
        }
        val summary = result.summary()
        mutable.update {
            it.copy(
                phase = if (it.phase is UpdatePhase.Checking) UpdatePhase.Hidden else it.phase,
                checkSummary = if (manual) summary else it.checkSummary
            )
        }
        if (manual) publish(summary)
    }

    override fun downloadUpdate() {
        val target = offered ?: return
        if (downloadJob?.isActive == true) return
        val file = File(updateDir, "shida-suixing-${target.version.versionName}.apk")
        // One transfer at a time: a stale apk from an earlier version would only waste cache.
        updateDir.listFiles()?.forEach { if (it != file) it.delete() }
        mutable.update { it.copy(phase = UpdatePhase.Downloading(target.version.versionName, 0L, target.apkBytes)) }
        downloadJob = scope.launch {
            val result = updater.download(target.apkUrl, file) { read, total ->
                mutable.update { current ->
                    val phase = current.phase
                    if (phase is UpdatePhase.Downloading) {
                        current.copy(phase = phase.copy(downloadedBytes = read, totalBytes = total))
                    } else {
                        current
                    }
                }
            }
            when (result) {
                is ApkDownload.Saved -> {
                    downloaded = result.file
                    val needsPermission = !canRequestPackageInstalls()
                    mutable.update {
                        it.copy(phase = UpdatePhase.Ready(target.version.versionName, needsPermission))
                    }
                }
                is ApkDownload.Failed -> {
                    mutable.update {
                        it.copy(phase = UpdatePhase.Failed(target.version.versionName, result.reason.explain()))
                    }
                }
            }
        }
    }

    override fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        // Cancelling the coroutine alone would leave the socket read waiting for its timeout.
        updater.abort()
        mutable.update {
            // A transfer that finished in the same instant keeps its result rather than being
            // rewritten back to "available" by a tap that arrived too late.
            if (it.phase is UpdatePhase.Downloading) it.copy(phase = offered?.availablePhase() ?: UpdatePhase.Hidden) else it
        }
    }

    override fun dismissUpdate() {
        val target = offered ?: return
        preferences.dismissedVersionCode = target.version.versionCode
        mutable.update { it.copy(phase = UpdatePhase.Hidden) }
    }

    override fun installUpdate() {
        val file = downloaded ?: return
        val versionName = offered?.version?.versionName.orEmpty()
        if (!canRequestPackageInstalls()) {
            awaitingInstallPermission = true
            mutable.update {
                it.copy(
                    phase = UpdatePhase.Ready(versionName, needsInstallPermission = true),
                    message = "请先允许本应用安装应用，返回后会自动继续。",
                    messageId = it.messageId + 1
                )
            }
            openUnknownSourcesSettings()
            return
        }
        launchInstaller(file, versionName)
    }

    /** Called when the app returns to the foreground, including from the unknown-sources setting. */
    fun onResume() {
        if (!awaitingInstallPermission) return
        awaitingInstallPermission = false
        if (canRequestPackageInstalls()) {
            installUpdate()
            return
        }
        val versionName = offered?.version?.versionName.orEmpty()
        mutable.update {
            it.copy(
                phase = UpdatePhase.Ready(versionName, needsInstallPermission = true),
                message = "仍未允许安装应用，可稍后再试。",
                messageId = it.messageId + 1
            )
        }
    }

    override fun clearUpdateMessage(expectedId: Long?) {
        mutable.update { if (expectedId == null || expectedId == it.messageId) it.copy(message = null) else it }
    }

    private fun openUnknownSourcesSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${application.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            application.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // The setting screen is missing on a few vendor builds; the card still offers 安装.
        }
    }

    /**
     * Hands the cached file to the system installer through a content uri. Android shows its own
     * confirmation for every sideloaded package, so this only opens that dialog.
     */
    private fun launchInstaller(file: File, versionName: String) {
        val uri = FileProvider.getUriForFile(application, "${application.packageName}$FILE_PROVIDER_SUFFIX", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, ANDROID_PACKAGE_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            application.startActivity(intent)
            publish("系统会再次确认安装，按提示完成即可。")
        } catch (_: ActivityNotFoundException) {
            mutable.update { it.copy(phase = UpdatePhase.Failed(versionName, "设备上没有可用的安装程序。")) }
        }
    }

    private fun canRequestPackageInstalls(): Boolean = application.packageManager.canRequestPackageInstalls()

    private fun publish(message: String) {
        mutable.update { it.copy(message = message, messageId = it.messageId + 1) }
    }

    private fun ReleaseCheck.Available.availablePhase() = UpdatePhase.Available(version.versionName, apkBytes)

    companion object {
        private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
        private const val ANDROID_PACKAGE_MIME = "application/vnd.android.package-archive"

        @Volatile
        private var shared: UpdateController? = null

        /** One controller per process, so the launch check is not repeated per activity. */
        fun of(application: Application): UpdateController = shared ?: synchronized(this) {
            shared ?: UpdateController(
                application = application,
                updater = ReleaseUpdater(HttpsReleaseTransport()),
                preferences = UpdatePreferences(application),
                versionCode = BuildConfig.VERSION_CODE,
                updateDir = File(application.cacheDir, "updates")
            ).also { shared = it }
        }
    }
}

private fun ReleaseCheck.summary(): String = when (this) {
    is ReleaseCheck.Available -> "发现新版本 ${version.versionName}"
    ReleaseCheck.UpToDate -> "已是最新版本"
    ReleaseCheck.NoRelease -> "暂无发布版本"
    ReleaseCheck.RateLimited -> "更新服务请求过于频繁，请稍后再试"
    ReleaseCheck.MissingApk -> "新版本缺少安装包，请稍后再试"
    ReleaseCheck.Unreadable -> "更新信息暂时无法读取"
    ReleaseCheck.Unreachable -> "暂时无法连接更新服务"
}

private fun DownloadFailure.explain(): String = when (this) {
    DownloadFailure.NETWORK -> "下载中断，请检查网络后重试。"
    DownloadFailure.NOT_AN_APK -> "下载的内容不是安装包，已丢弃。"
    DownloadFailure.TOO_LARGE -> "安装包超出预期大小，已丢弃。"
    DownloadFailure.STORAGE -> "无法写入缓存目录，请清理存储后重试。"
}
