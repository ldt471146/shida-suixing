package cn.gxnu.campus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.data.TimetableStore
import cn.gxnu.campus.data.VisionApiKeyStore
import cn.gxnu.campus.data.maskApiKey
import cn.gxnu.campus.network.HttpTimetableVisionClient
import cn.gxnu.campus.network.TimetableImage
import cn.gxnu.campus.network.TimetableVisionClient
import cn.gxnu.campus.network.TimetableVisionException
import cn.gxnu.campus.network.TimetableVisionFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TimetableUiState(
    val restoring: Boolean = true,
    val recognizing: Boolean = false,
    val timetable: Timetable? = null,
    val keyConfigured: Boolean = false,
    val keyHint: String = "",
    val message: String? = null,
    val messageId: Long = 0,
    val failure: TimetableVisionFailure? = null,
    val canRetry: Boolean = false
)

interface TimetableActions {
    fun useImage(image: TimetableImage)
    fun saveApiKey(value: String)
    fun clearApiKey()
    fun retry()
    fun deleteTimetable()
    fun clearMessage(expectedId: Long? = null)
}

/**
 * Owns everything the timetable screen does that outlives one frame: the stored timetable, the
 * encrypted key, and the recognition request. The API key is read back from the vault for each
 * request instead of being cached in a field, so it is never held longer than the call needs it.
 */
class TimetableController internal constructor(
    private val client: TimetableVisionClient,
    private val store: TimetableStore,
    private val keys: VisionApiKeyStore,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : TimetableActions {

    private val mutableState = MutableStateFlow(TimetableUiState())
    val state: StateFlow<TimetableUiState> = mutableState.asStateFlow()

    private var pendingImage: TimetableImage? = null
    private var messageId = 0L

    /** Reads the saved timetable and key hint; the empty state is a normal, expected outcome. */
    fun restore() {
        scope.launch {
            val restored = withContext(dispatcher) { restoreFromDisk() }
            mutableState.value = mutableState.value.copy(
                restoring = false,
                timetable = restored.timetable,
                keyConfigured = restored.key != null,
                keyHint = maskApiKey(restored.key),
                message = if (restored.keyUnreadable) "已保存的 API Key 无法解密，请重新填写。" else mutableState.value.message,
                messageId = if (restored.keyUnreadable) ++messageId else mutableState.value.messageId
            )
        }
    }

    private fun restoreFromDisk(): Restored {
        var key: String? = null
        var unreadable = false
        // load() returns null when nothing is stored and throws only when the vault itself failed.
        try { key = keys.load() } catch (_: Exception) { unreadable = true }
        return Restored(store.load(), key, unreadable)
    }

    override fun useImage(image: TimetableImage) {
        pendingImage = image
        mutableState.value = mutableState.value.copy(canRetry = true)
        recognize(image)
    }

    override fun retry() {
        val image = pendingImage
        if (image == null) {
            publish("请先选择一张课表图片，再开始识别。")
            return
        }
        recognize(image)
    }

    override fun saveApiKey(value: String) {
        val key = value.trim()
        if (key.isEmpty()) {
            publish("请输入 API Key。")
            return
        }
        if (key.length > 512) {
            publish("API Key 过长，请检查后重试。")
            return
        }
        scope.launch {
            val saved = withContext(dispatcher) {
                try { keys.save(key); true } catch (_: Exception) { false }
            }
            mutableState.value = mutableState.value.copy(
                keyConfigured = saved,
                keyHint = if (saved) maskApiKey(key) else mutableState.value.keyHint,
                message = if (saved) "API Key 已加密保存在本机。" else "API Key 未能保存，请重试。",
                messageId = ++messageId
            )
        }
    }

    override fun clearApiKey() {
        scope.launch {
            val cleared = withContext(dispatcher) {
                try { keys.clear(); true } catch (_: Exception) { false }
            }
            mutableState.value = mutableState.value.copy(
                keyConfigured = if (cleared) false else mutableState.value.keyConfigured,
                keyHint = if (cleared) "" else mutableState.value.keyHint,
                message = if (cleared) "API Key 已从本机删除。" else "API Key 未能删除，请重试。",
                messageId = ++messageId
            )
        }
    }

    override fun deleteTimetable() {
        scope.launch {
            val cleared = withContext(dispatcher) {
                try { store.clear(); true } catch (_: Exception) { false }
            }
            mutableState.value = mutableState.value.copy(
                timetable = if (cleared) null else mutableState.value.timetable,
                failure = null,
                message = if (cleared) "本机课表已删除。" else "课表未能删除，请重试。",
                messageId = ++messageId
            )
        }
    }

    override fun clearMessage(expectedId: Long?) {
        if (expectedId != null && expectedId != mutableState.value.messageId) return
        mutableState.value = mutableState.value.copy(message = null)
    }

    private fun recognize(image: TimetableImage) {
        if (mutableState.value.recognizing) return
        mutableState.value = mutableState.value.copy(recognizing = true, failure = null, message = null)
        scope.launch {
            val outcome = withContext(dispatcher) { attempt(image) }
            val current = mutableState.value
            mutableState.value = when (outcome) {
                is Outcome.Recognized -> current.copy(
                    recognizing = false,
                    // A refused request keeps the timetable that is already stored.
                    timetable = outcome.timetable,
                    failure = null,
                    message = if (outcome.persisted) "课表已识别并保存在本机。"
                    else "课表已识别，但未能保存到本机，本次结果重开后会丢失。",
                    messageId = ++messageId
                )
                is Outcome.Refused -> current.copy(
                    recognizing = false,
                    failure = outcome.failure,
                    message = outcome.message,
                    messageId = ++messageId
                )
            }
        }
    }

    private suspend fun attempt(image: TimetableImage): Outcome {
        val key = try { keys.load() } catch (_: Exception) { null }
        if (key.isNullOrBlank()) return Outcome.Refused(TimetableVisionFailure.MISSING_KEY, "请先填写 API Key，再识别课表。")
        val timetable = try {
            client.recognize(image, key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TimetableVisionException) {
            return Outcome.Refused(failure.failure, failure.message ?: "识别失败，请重试。")
        } catch (_: Exception) {
            return Outcome.Refused(null, "识别失败，请检查网络后重试。")
        }
        val persisted = try { store.save(timetable); true } catch (_: Exception) { false }
        return Outcome.Recognized(timetable, persisted)
    }

    private fun publish(message: String) {
        mutableState.value = mutableState.value.copy(message = message, messageId = ++messageId)
    }

    private sealed interface Outcome {
        data class Recognized(val timetable: Timetable, val persisted: Boolean) : Outcome
        data class Refused(val failure: TimetableVisionFailure?, val message: String) : Outcome
    }

    private data class Restored(val timetable: Timetable?, val key: String?, val keyUnreadable: Boolean)
}

/**
 * Ready-to-use controller for the timetable destination. Its scope is remembered rather than
 * composition-bound so a running recognition finishes even if the user leaves the screen.
 */
@Composable
fun rememberTimetableController(): TimetableController {
    val context = LocalContext.current
    val controller = remember(context) {
        TimetableController(
            client = HttpTimetableVisionClient(),
            store = TimetableStore(context),
            keys = VisionApiKeyStore(context),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        )
    }
    LaunchedEffect(controller) { controller.restore() }
    return controller
}
