package cn.gxnu.campus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cn.gxnu.campus.core.TIMETABLE_MAX_COURSES
import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableCalendar
import cn.gxnu.campus.core.TimetableCourse
import cn.gxnu.campus.core.TimetableCourseDraft
import cn.gxnu.campus.core.TimetableException
import cn.gxnu.campus.core.TimetableFailure
import cn.gxnu.campus.core.TimetableValidator
import cn.gxnu.campus.core.TimetableWordImporter
import cn.gxnu.campus.core.WordDocuments
import cn.gxnu.campus.core.WordImportResult
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
    /** True from the moment a Word file is handed over until its courses are in the state. */
    val importing: Boolean = false,
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

    /**
     * Imports a 课表 printed by the 教务系统 and saved as Word. [fileName] only names the file in what
     * the user is told — the bytes decide what the document really is.
     */
    fun importWord(fileName: String, bytes: ByteArray)
    fun saveApiKey(value: String)
    fun clearApiKey()
    fun retry()

    /** 新增一门课；[index] 不为 null 时替换已有的那一门。 */
    fun saveCourse(index: Int?, draft: TimetableCourseDraft)

    /** 删除第 [index] 门课。 */
    fun removeCourse(index: Int)

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
    /** The wall clock an import stamps its timetable with; a recognition stamps its own. */
    private val now: () -> Long = { System.currentTimeMillis() },
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

    override fun importWord(fileName: String, bytes: ByteArray) {
        // One import at a time: two answers racing for the same state is the failure the guard in
        // recognize() exists to prevent, and the same reasoning holds here.
        if (mutableState.value.importing) return
        // An import is a new context. A recognition card still on screen is titled 没识别到课表 and the
        // page shows no notice row while it is up, so it goes with the import instead of framing it.
        mutableState.value = mutableState.value.copy(importing = true, failure = null, message = null)
        val started = try {
            scope.launch { publishImport(importOutcome(fileName, bytes)) }
        } catch (_: Throwable) {
            null
        }
        // A scope that is already cancelled hands back a job that will never run a line, so the
        // release recognize() needs applies here too: a flag with nothing behind it comes down here.
        if (started == null || (started.isCancelled && mutableState.value.importing)) {
            mutableState.value = mutableState.value.copy(
                importing = false,
                message = "导入没能启动，请重试。",
                messageId = ++messageId
            )
        }
    }

    /**
     * One import attempt. Everything but cancellation is folded into a refusal here, so a malformed
     * document, an [Error] out of the readers or a failed write cannot escape the coroutine and leave
     * `importing` set for good — the same shape the recognition path has, for the same reason.
     */
    private suspend fun importOutcome(fileName: String, bytes: ByteArray): ImportOutcome = try {
        runImport(fileName, bytes)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        ImportOutcome.Refused("导入失败，请确认文件是本机教务系统导出的课表，然后重试。")
    }

    private suspend fun runImport(fileName: String, bytes: ByteArray): ImportOutcome = withContext(dispatcher) {
        if (bytes.size > WordDocuments.MAX_BYTES) {
            return@withContext ImportOutcome.Refused("「$fileName」超过 24 MB，课表文件没有这么大，请换一个文件。")
        }
        val document = WordDocuments.read(bytes)
            ?: return@withContext ImportOutcome.Refused("这个文件不是 Word 课表，请选择 .doc 或 .docx。")
        val imported = try {
            // A printed 课表 states its term in the page title, which never reaches the table reader,
            // so the import claims no term rather than inventing one.
            TimetableWordImporter.read(document, term = "", recognizedAtMillis = now())
        } catch (refusal: TimetableException) {
            return@withContext ImportOutcome.Refused(importRefusal(refusal))
        }
        // A result that cannot be written is still a result: it reaches the screen with the warning.
        val persisted = try { store.save(imported.timetable); true } catch (_: Throwable) { false }
        ImportOutcome.Imported(imported, persisted)
    }

    private fun publishImport(outcome: ImportOutcome) {
        val current = mutableState.value
        mutableState.value = when (outcome) {
            is ImportOutcome.Imported -> current.withTimetable(outcome.imported.timetable, current.termStartEpochDay).copy(
                importing = false,
                failure = null,
                message = importMessage(outcome),
                messageId = ++messageId
            )
            is ImportOutcome.Refused -> current.copy(
                importing = false,
                failure = null,
                message = outcome.message,
                messageId = ++messageId
            )
        }
    }

    /** What an import tells the user: the courses it brought in, and the rows it left out on purpose. */
    private fun importMessage(outcome: ImportOutcome.Imported): String {
        val count = outcome.imported.timetable.courseCount
        val skipped = outcome.imported.skippedUnnumbered
        val body = if (skipped > 0) "已从 Word 导入 $count 门课程，忽略「无节次」行的 $skipped 门课。"
        else "已从 Word 导入 $count 门课程。"
        return if (outcome.persisted) body else "${body}未能保存到本机，重开后会丢失。"
    }

    /**
     * The validator's wording is addressed to the photo screen ("请换一张更清晰的课表照片"), so a refused
     * import reports the same failure in the language of a Word file the user picked.
     */
    private fun importRefusal(refusal: TimetableException): String = when (refusal.failure) {
        TimetableFailure.NO_COURSES -> "这个 Word 文件里没有课程，请确认导出的是本学期课表。"
        TimetableFailure.TOO_MANY_COURSES -> "文件里的课程过多（超过 $TIMETABLE_MAX_COURSES 门），请确认选的是课表文件。"
        TimetableFailure.CONFLICT -> "文件里的课程时间有冲突，这张课表无法导入，请检查后再试。"
        // The importer's own wording already names a Word file rather than a photo.
        TimetableFailure.NOT_A_TIMETABLE -> refusal.message?.takeIf { it.isNotBlank() }
            ?: "这个 Word 文件里没有找到课表表格，请确认导出的是教务系统的课表。"
        TimetableFailure.INVALID_FIELD -> invalidFieldReason(refusal)
    }

    override fun selectWeek(week: Int) {        val current = mutableState.value
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

    override fun saveCourse(index: Int?, draft: TimetableCourseDraft) {
        val timetable = mutableState.value.timetable
        if (timetable == null) {
            publishEdit("还没有课表可以修改，请先识别课表或重试识别。")
            return
        }
        if (index != null && index !in timetable.courses.indices) {
            publishEdit("要修改的课程不存在，请重新打开这门课再试。")
            return
        }
        val drafts = timetable.courses.map { it.toDraft() }.toMutableList()
        if (index == null) drafts += draft else drafts[index] = draft
        rebuild(drafts, if (index == null) "已添加这门课并保存在本机。" else "修改已保存在本机。")
    }

    override fun removeCourse(index: Int) {
        val timetable = mutableState.value.timetable
        if (timetable == null) {
            publishEdit("还没有课表可以修改，请先识别课表或重试识别。")
            return
        }
        if (index !in timetable.courses.indices) {
            publishEdit("要删除的课程不存在，请刷新后再试。")
            return
        }
        rebuild(
            timetable.courses.filterIndexed { position, _ -> position != index }.map { it.toDraft() },
            "已删除这门课并保存在本机。"
        )
    }

    /**
     * An edit is a new context. A recognition failure the page may still be showing has to go with it,
     * or its card would frame a message about the course the user just typed under the title
     * "没识别到课表" — and, while a failure is on screen, the page shows no separate notice row at all.
     */
    private fun publishEdit(message: String) {
        mutableState.value = mutableState.value.copy(
            failure = null,
            message = message,
            messageId = ++messageId
        )
    }

    /**
     * Rebuilds the whole timetable from [drafts] to write it, so a hand edit is held to exactly the
     * ranges and conflicts a recognition is — there is no second rule book. A refusal leaves the
     * timetable the user is looking at untouched and says why.
     */
    private fun rebuild(drafts: List<TimetableCourseDraft>, savedMessage: String) {
        val timetable = mutableState.value.timetable ?: return
        val rebuilt = try {
            // The 节次 clocks survive an edit: they belong to the timetable, not to the course being
            // changed, and dropping them here would silently empty the grid gutter.
            TimetableValidator.build(timetable.term, drafts, timetable.recognizedAtMillis, timetable.periodTimes)
        } catch (refusal: TimetableException) {
            publishEdit(editRefusal(refusal))
            return
        }
        scope.launch {
            val persisted = withContext(dispatcher) {
                try { store.save(rebuilt); true } catch (_: Throwable) { false }
            }
            val latest = mutableState.value
            mutableState.value = if (persisted) latest.withEditedTimetable(rebuilt).copy(
                failure = null,
                message = savedMessage,
                messageId = ++messageId
            ) else latest.copy(
                failure = null,
                message = "已修改，但未能保存到本机，重开后会丢失。",
                messageId = ++messageId
            )
        }
    }

    /**
     * The validator's wording is addressed to the recognition screen ("换一张更清晰的课表照片"), so a
     * refused edit reports the same failure in the language of a form the user is filling in.
     */
    private fun editRefusal(refusal: TimetableException): String = when (refusal.failure) {
        TimetableFailure.CONFLICT -> "这门课与其他课程时间冲突，请调整星期或节次。"
        TimetableFailure.NO_COURSES -> "这是课表里的最后一门课，如需清空请使用「删除课表」。"
        TimetableFailure.TOO_MANY_COURSES -> "课程数量已达上限（$TIMETABLE_MAX_COURSES 门），请先删除一些课程。"
        TimetableFailure.INVALID_FIELD -> invalidFieldReason(refusal)
        // Only the response reader raises this one, so it stands in as the last resort here.
        TimetableFailure.NOT_A_TIMETABLE -> "这门课的信息无法保存，请检查后重试。"
    }

    /** "第 2 门课程的星期无法识别" — the clause before the comma names the field that is wrong. */
    private fun invalidFieldReason(refusal: TimetableException): String {
        val field = refusal.message.orEmpty().substringBefore("，").trimEnd('。')
        return if (field.isBlank()) "课程信息不完整或不合法，请检查后重试。" else "$field，请检查后重试。"
    }

    /**
     * A stored course back in the untrusted shape the validator takes, so editing it is parsed by the
     * same rules as a recognition. 单双周 travels with it: without it a 单周 and a 双周 course sharing
     * one slot would read as a clash the moment anything else is rebuilt.
     */
    private fun TimetableCourse.toDraft(): TimetableCourseDraft = TimetableCourseDraft(
        name = name,
        teacher = teacher,
        room = room,
        weekday = weekday.toString(),
        startPeriod = startPeriod.toString(),
        endPeriod = endPeriod.toString(),
        startWeek = startWeek.toString(),
        endWeek = endWeek.toString(),
        parity = parity.name
    )

    private fun recognize(image: TimetableImage) {
        // Replacing a live request would leave the first one's answer to land on top of the second's.
        if (recognition?.isActive == true) return
        mutableState.value = mutableState.value.copy(recognizing = true, failure = null, message = null)
        // A scope that is already cancelled hands back a job that will never run a line, so a flag
        // with nothing behind it is released from here rather than by a completion that cannot fire.
        val started = try {
            scope.launch { publishOutcome(runRecognition(image)) }
        } catch (_: Throwable) {
            null
        }
        recognition = started
        if (started == null || (started.isCancelled && mutableState.value.recognizing)) {
            recognition = null
            mutableState.value = mutableState.value.copy(
                recognizing = false,
                message = "识别没能启动，请重试。",
                messageId = ++messageId
            )
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

    /**
     * One attempt at a recognition. Everything but cancellation is folded into a refusal here, so an
     * [Error] out of the client, the vault or the watchdog cannot escape the coroutine. Nothing
     * behind this scope installs a CoroutineExceptionHandler, so an escapee would take the process
     * down and leave `recognizing` set for good. A null answer means the watchdog ran out.
     */
    private suspend fun runRecognition(image: TimetableImage): Outcome? = try {
        // The watchdog is the last resort behind the visible 取消 button: it bounds how long
        // a stalled request can keep the screen in its busy state.
        withTimeoutOrNull(recognitionTimeoutMillis) { withContext(dispatcher) { attempt(image) } }
    } catch (cancelled: CancellationException) {
        // cancelRecognition() has already published the cancelled state.
        throw cancelled
    } catch (_: Throwable) {
        Outcome.Refused(null, "识别失败，请检查网络后重试。")
    }

    /** The one place an attempt turns into what the screen renders, timeout included. */
    private fun publishOutcome(outcome: Outcome?) {
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

    private suspend fun attempt(image: TimetableImage): Outcome {
        val key = resolvedApiKey()
        if (key.isNullOrBlank()) return Outcome.Refused(TimetableVisionFailure.MISSING_KEY, "请先填写 API Key，再识别课表。")
        val timetable = try {
            client.recognize(image, key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TimetableVisionException) {
            return Outcome.Refused(failure.failure, failure.message ?: "识别失败，请重试。")
        } catch (_: Throwable) {
            return Outcome.Refused(null, "识别失败，请检查网络后重试。")
        }
        // A result that cannot be written is still a result: it reaches the screen with the warning.
        val persisted = try { store.save(timetable); true } catch (_: Throwable) { false }
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

    /**
     * Swaps in an edited timetable without disturbing what the user was doing: the week they had
     * browsed to, the term start and the ability to re-recognise the last photo all survive, and the
     * browsed week is clamped to the term the edit left behind.
     */
    private fun TimetableUiState.withEditedTimetable(timetable: Timetable): TimetableUiState {
        val edited = withTimetable(timetable, termStartEpochDay)
        return edited.copy(selectedWeek = selectedWeek.coerceIn(1, edited.weekCount.coerceAtLeast(1)))
    }

    private sealed interface Outcome {
        data class Recognized(val timetable: Timetable, val persisted: Boolean) : Outcome
        data class Refused(val failure: TimetableVisionFailure?, val message: String) : Outcome
    }

    private sealed interface ImportOutcome {
        data class Imported(val imported: WordImportResult, val persisted: Boolean) : ImportOutcome
        data class Refused(val message: String) : ImportOutcome
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
