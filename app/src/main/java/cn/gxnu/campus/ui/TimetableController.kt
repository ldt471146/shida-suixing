package cn.gxnu.campus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableCalendar
import cn.gxnu.campus.data.TimetableStore
import cn.gxnu.campus.data.VisionApiKeyStore
import cn.gxnu.campus.data.maskApiKey
import cn.gxnu.campus.network.HttpTimetableVisionClient
import cn.gxnu.campus.network.TimetableImage
import cn.gxnu.campus.network.TimetableVisionClient
import cn.gxnu.campus.network.TimetableVisionException
import cn.gxnu.campus.network.TimetableVisionFailure
import cn.gxnu.campus.network.VisionBuiltInCredentials
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class TimetableUiState(
    val restoring: Boolean = true,
    val recognizing: Boolean = false,
    val timetable: Timetable? = null,
    /** True when this build carries its own endpoint and key, so the key screen is never shown. */
    val builtInKey: Boolean = false,
    val keyConfigured: Boolean = false,
    val keyHint: String = "",
    /** The week the grid is showing. */
    val selectedWeek: Int = 1,
    /** The week today falls in, or 1 while no term start has been set. */
    val currentWeek: Int = 1,
    val weekCount: Int = 1,
    /** Epoch day of the term's first Monday; null until the user sets it. */
    val termStartEpochDay: Long? = null,
    /** 1 = 周一 … 7 = 周日. */
    val todayWeekday: Int = 1,
    /** True once an image has been picked, so 重新识别 has something to repeat. */
    val canRetry: Boolean = false,
    val message: String? = null,
    val messageId: Long = 0,
    val failure: TimetableVisionFailure? = null
)

interface TimetableActions {
    fun useImage(image: TimetableImage)
    fun saveApiKey(value: String)
    fun clearApiKey()
    fun retry()

    /**
     * Stops a recognition that is in flight. The picked image is kept, so 重新识别 can start over
     * without asking the user to choose the photo again.
     */
    fun cancelRecognition()
    fun deleteTimetable()
    fun clearMessage(expectedId: Long? = null)
    fun selectWeek(week: Int)
    fun showCurrentWeek()
    fun setTermStart(epochDay: Long)
    fun clearTermStart()
}

/**
 * Owns everything the timetable screen does that outlives one frame: the stored timetable, the
 * encrypted key, the chosen teaching week and the recognition request. The API key is read back
 * from the vault for each request instead of being cached in a field, so it is never held longer
 * than the call needs it; a key compiled into the build is never stored at all.
 */
class TimetableController internal constructor(
    private val client: TimetableVisionClient,
    private val store: TimetableStore,
    private val keys: VisionApiKeyStore,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val builtInKey: String = "",
    private val today: () -> LocalDate = { LocalDate.now() },
    private val recognitionTimeoutMillis: Long = DEFAULT_RECOGNITION_TIMEOUT_MILLIS
) : TimetableActions {

    private val mutableState = MutableStateFlow(TimetableUiState(builtInKey = builtInKey.isNotBlank()))
    val state: StateFlow<TimetableUiState> = mutableState.asStateFlow()

    private var pendingImage: TimetableImage? = null
    private var messageId = 0L

    /** The recognition currently in flight, so it can be cancelled and so it can be replaced. */
    private var recognition: Job? = null

    /** Reads the saved timetable, term start and key hint; the empty state is a normal outcome. */
    fun restore() {
        scope.launch {
            val restored = withContext(dispatcher) { restoreFromDisk() }
            mutableState.value = mutableState.value.withTimetable(restored.timetable, restored.termStart).copy(
                restoring = false,
                keyConfigured = restored.key != null,
                keyHint = maskApiKey(restored.key),
                // A build with its own key has no key screen, so a vault problem is not the user's.
                message = if (restored.keyUnreadable && builtInKey.isBlank()) "已保存的 API Key 无法解密，请重新填写。"
                else mutableState.value.message,
                messageId = if (restored.keyUnreadable && builtInKey.isBlank()) ++messageId else mutableState.value.messageId
            )
        }
    }

    private fun restoreFromDisk(): Restored {
        var key: String? = null
        var unreadable = false
        // load() returns null when nothing is stored and throws only when the vault itself failed.
        try { key = keys.load() } catch (_: Exception) { unreadable = true }
        return Restored(store.load(), key, unreadable, store.loadTermStart())
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

    override fun selectWeek(week: Int) {
        val current = mutableState.value
        mutableState.value = current.copy(selectedWeek = week.coerceIn(1, current.weekCount.coerceAtLeast(1)))
    }

    override fun showCurrentWeek() {
        mutableState.value = mutableState.value.copy(selectedWeek = mutableState.value.currentWeek)
    }

    override fun setTermStart(epochDay: Long) {
        scope.launch {
            val saved = withContext(dispatcher) {
                try { store.saveTermStart(epochDay); true } catch (_: Exception) { false }
            }
            if (!saved) {
                publish("开学日期未能保存，请重试。")
                return@launch
            }
            // Setting the date is a request to be taken to the current week, so jump there.
            val current = mutableState.value
            val week = TimetableCalendar.weekOf(today(), epochDay).coerceIn(1, current.weekCount.coerceAtLeast(1))
            mutableState.value = current.copy(
                termStartEpochDay = epochDay,
                currentWeek = week,
                selectedWeek = week,
                message = "开学日期已保存，当前周按第 $week 周显示。",
                messageId = ++messageId
            )
        }
    }

    override fun clearTermStart() {
        scope.launch {
            val cleared = withContext(dispatcher) {
                try { store.clearTermStart(); true } catch (_: Exception) { false }
            }
            mutableState.value = mutableState.value.copy(
                termStartEpochDay = if (cleared) null else mutableState.value.termStartEpochDay,
                currentWeek = if (cleared) 1 else mutableState.value.currentWeek,
                message = if (cleared) "已清除开学日期。" else "开学日期未能清除，请重试。",
                messageId = ++messageId
            )
        }
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
            // The term start is kept: it belongs to the term, not to the recognition that just went.
            val current = mutableState.value
            val kept = if (cleared) null else current.timetable
            mutableState.value = current.withTimetable(kept, current.termStartEpochDay).copy(
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
        // Replacing a live request would leave the first one's answer to land on top of the second's.
        if (recognition?.isActive == true) return
        mutableState.value = mutableState.value.copy(recognizing = true, failure = null, message = null)
        recognition = scope.launch {
            val outcome = try {
                // The watchdog is the last resort behind the visible 取消 button: it bounds how long
                // a stalled request can keep the screen in its busy state.
                withTimeoutOrNull(recognitionTimeoutMillis) { withContext(dispatcher) { attempt(image) } }
            } catch (cancelled: CancellationException) {
                // cancelRecognition() has already published the cancelled state.
                throw cancelled
            } catch (_: Exception) {
                Outcome.Refused(null, "识别失败，请检查网络后重试。")
            }
            val current = mutableState.value
            mutableState.value = when (outcome) {
                null -> current.copy(
                    recognizing = false,
                    failure = null,
                    message = "识别用时过长，已自动停止，请重试。",
                    messageId = ++messageId
                )
                is Outcome.Recognized -> current.withTimetable(outcome.timetable, current.termStartEpochDay).copy(
                    recognizing = false,
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

    override fun cancelRecognition() {
        recognition?.cancel()
        recognition = null
        if (!mutableState.value.recognizing) return
        mutableState.value = mutableState.value.copy(
            recognizing = false,
            failure = null,
            message = "已取消识别，可以换一张图片重新开始。",
            messageId = ++messageId
        )
    }

    private suspend fun attempt(image: TimetableImage): Outcome {
        val key = resolvedApiKey()
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

    /** The build's own key wins; the encrypted vault is only consulted when the build has none. */
    private suspend fun resolvedApiKey(): String? {
        if (builtInKey.isNotBlank()) return builtInKey
        return try { keys.load()?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
    }

    private fun publish(message: String) {
        mutableState.value = mutableState.value.copy(message = message, messageId = ++messageId)
    }

    /**
     * Recomputes everything that follows from the timetable and the term start, so the week switcher
     * and 当前周 can never drift apart from the courses being shown.
     */
    private fun TimetableUiState.withTimetable(timetable: Timetable?, termStart: Long?): TimetableUiState {
        val weekCount = timetable?.weekCount ?: 1
        val current = termStart?.let { TimetableCalendar.weekOf(today(), it) }?.coerceIn(1, weekCount) ?: 1
        val selected = if (timetable == null) 1 else selectedWeek.coerceIn(1, weekCount)
        return copy(
            timetable = timetable,
            weekCount = weekCount,
            currentWeek = current,
            termStartEpochDay = termStart,
            // A fresh timetable opens on the current week; an unchanged one keeps the browsed week.
            selectedWeek = if (timetable != this.timetable) current else selected,
            todayWeekday = TimetableCalendar.weekdayOf(today())
        )
    }

    private sealed interface Outcome {
        data class Recognized(val timetable: Timetable, val persisted: Boolean) : Outcome
        data class Refused(val failure: TimetableVisionFailure?, val message: String) : Outcome
    }

    private data class Restored(
        val timetable: Timetable?,
        val key: String?,
        val keyUnreadable: Boolean,
        val termStart: Long?
    )

    companion object {
        /**
         * Longer than the transport's own read timeout, so the watchdog only ever fires on a request
         * that has genuinely stalled instead of racing the socket.
         */
        const val DEFAULT_RECOGNITION_TIMEOUT_MILLIS = 90_000L
    }
}

/**
 * Ready-to-use controller for the timetable destination. Its scope is remembered rather than
 * composition-bound so a running recognition finishes even if the user leaves the screen.
 */
@Composable
fun rememberTimetableController(): TimetableController {
    val context = LocalContext.current
    val builtIn = remember { VisionBuiltInCredentials }
    val controller = remember(context) {
        TimetableController(
            // The endpoint's base url is only meaningful when the build actually carries a key.
            client = HttpTimetableVisionClient(baseUrl = builtIn.baseUrl),
            store = TimetableStore(context),
            keys = VisionApiKeyStore(context),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            builtInKey = builtIn.apiKey
        )
    }
    LaunchedEffect(controller) { controller.restore() }
    return controller
}
