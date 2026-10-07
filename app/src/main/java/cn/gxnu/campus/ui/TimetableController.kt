package cn.gxnu.campus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cn.gxnu.campus.core.CampusTimetableApi
import cn.gxnu.campus.core.CampusTimetableMapper
import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.core.TIMETABLE_MAX_COURSES
import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableCalendar
import cn.gxnu.campus.core.TimetableCourse
import cn.gxnu.campus.core.TimetableCourseDraft
import cn.gxnu.campus.core.TimetableException
import cn.gxnu.campus.core.TimetableFailure
import cn.gxnu.campus.core.TimetableValidator
import cn.gxnu.campus.data.GmisAccountStore
import cn.gxnu.campus.data.TimetableStore
import java.time.LocalDate
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
    val signingIn: Boolean = false,
    val timetable: Timetable? = null,
    /** 已经登录的学号；空串表示这台设备现在没有登录。 */
    val account: String = "",
    /** 本机记住的学号，登录页用它预填。密码不回到界面上。 */
    val rememberedAccount: String = "",
    val selectedWeek: Int = 1,
    val currentWeek: Int = 1,
    val weekCount: Int = 1,
    /** Epoch day of the term's first Monday; null until the user sets it. */
    val termStartEpochDay: Long? = null,
    /** 1 = 周一 … 7 = 周日. */
    val todayWeekday: Int = 1,
    val message: String? = null,
    val messageId: Long = 0
)

interface TimetableActions {
    /** 用学号和密码登录研究生系统，成功后就地把课表取回来。 */
    fun signIn(userId: String, password: String, remember: Boolean)

    /** 用本机记住的账号重新取一次课表。 */
    fun refresh()

    /** 忘掉本机记住的账号并清掉登录状态；已经取回来的课表保留。 */
    fun signOut()

    /** 新增一门课；[index] 不为 null 时替换已有的那一门。 */
    fun saveCourse(index: Int?, draft: TimetableCourseDraft)

    /** 删除第 [index] 门课。 */
    fun removeCourse(index: Int)

    fun deleteTimetable()
    fun clearMessage(expectedId: Long? = null)
    fun selectWeek(week: Int)
    fun showCurrentWeek()
    fun setTermStart(epochDay: Long)
    fun clearTermStart()
}

/** 网络那一层的接口，抽出来是为了让控制器可以在没有校园网的单元测试里跑。 */
interface CampusTimetableSource {
    fun signIn(userId: String, password: String): CampusTimetableApi.SignIn
    fun fetchTimetable(token: String): CampusTimetableApi.Fetch

    companion object {
        val Real = object : CampusTimetableSource {
            override fun signIn(userId: String, password: String) = CampusTimetableApi.signIn(userId, password)
            override fun fetchTimetable(token: String) = CampusTimetableApi.fetchTimetable(token)
        }
    }
}

/** 记住的账号放在哪里；抽出来是为了让控制器不必依赖 Android Keystore 就能被测。 */
interface AccountVault {
    fun save(credentials: Credentials)
    fun load(): Credentials?
    fun clear()
}

/**
 * 课表的全部状态：登录会话、取回来的课表、正在看的教学周，以及对课表本身的手工修改。
 *
 * 课表来自研究生系统（学号 + 密码换一枚 token，再用 token 取 `/xskb/xh`）。token 只活在内存里：
 * 应用重启会用本机记住的账号重新登录一次，所以不需要把凭证写进磁盘。密码只在本机记住时才写入
 * Android Keystore 加密的保险箱，注销即删。
 */
class TimetableController internal constructor(
    private val source: CampusTimetableSource,
    private val store: TimetableStore,
    private val accounts: AccountVault,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val now: () -> Long = { System.currentTimeMillis() }
) : TimetableActions {

    private val mutableState = MutableStateFlow(TimetableUiState())
    val state: StateFlow<TimetableUiState> = mutableState.asStateFlow()

    private var messageId = 0L

    /** 当前会话的 token。它只在内存里，进程结束即失效。 */
    private var token: String? = null

    /** 读本机保存的课表、学期开始日与记住的学号，然后自动登录一次。 */
    fun restore() {
        scope.launch {
            val restored = withContext(dispatcher) { restoreFromDisk() }
            mutableState.value = mutableState.value.copy(
                restoring = false,
                timetable = restored.timetable,
                termStartEpochDay = restored.termStart,
                rememberedAccount = restored.credentials?.account.orEmpty(),
                todayWeekday = TimetableCalendar.weekdayOf(today())
            ).withTimetable(restored.timetable, restored.termStart)
            val credentials = restored.credentials
            if (credentials != null) {
                // 记住过账号就一直续上，用户不需要每次开应用都点一次登录。
                signIn(credentials.account, credentials.password, remember = true, silent = true)
            }
        }
    }

    private fun restoreFromDisk(): Restored = Restored(
        store.load(),
        store.loadTermStart(),
        try { accounts.load() } catch (_: Exception) { null }
    )

    override fun signIn(userId: String, password: String, remember: Boolean) {
        signIn(userId, password, remember, silent = false)
    }

    private fun signIn(userId: String, password: String, remember: Boolean, silent: Boolean) {
        if (mutableState.value.signingIn) return
        mutableState.value = mutableState.value.copy(
            signingIn = true,
            message = if (silent) mutableState.value.message else null
        )
        scope.launch {
            // 除取消外任何异常都必须收敛成一次失败：这个 scope 上没有 CoroutineExceptionHandler，
            // 逃出去不仅会把进程带下来，还会把 signingIn 永久留在 true —— 页面就卡在「正在登录」。
            val outcome = try {
                withContext(dispatcher) { attemptSignIn(userId.trim(), password, remember) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                Outcome.Refused("登录时出了点问题，请重试。", signedOut = false)
            }
            publish(outcome, silent)
        }
    }

    private fun attemptSignIn(userId: String, password: String, remember: Boolean): Outcome {
        when (val signedIn = source.signIn(userId, password)) {
            is CampusTimetableApi.SignIn.Failed -> return Outcome.Refused(signedIn.reason, signedOut = true)
            is CampusTimetableApi.SignIn.Ok -> {
                // 先写凭据再取课表：取失败也不该让用户重填一次密码。
                val kept = try {
                    if (remember) accounts.save(Credentials(userId, password)) else accounts.clear()
                    true
                } catch (_: Exception) {
                    false
                }
                token = signedIn.token
                val fetched = when (val result = source.fetchTimetable(signedIn.token)) {
                    is CampusTimetableApi.Fetch.Failed -> return Outcome.Refused(result.reason, signedOut = result.expired)
                    is CampusTimetableApi.Fetch.Ok -> result.courses
                }
                val timetable = try {
                    CampusTimetableMapper.build(fetched, term = "", now())
                } catch (refusal: TimetableException) {
                    return Outcome.Refused(campusRefusal(refusal), signedOut = false)
                }
                val persisted = try { store.save(timetable); true } catch (_: Throwable) { false }
                return Outcome.Loaded(timetable, userId, persisted, kept, remember)
            }
        }
    }

    private fun publish(outcome: Outcome, silent: Boolean) {
        val current = mutableState.value
        mutableState.value = when (outcome) {
            is Outcome.Loaded -> current.copy(signingIn = false).withTimetable(outcome.timetable, current.termStartEpochDay)
                .copy(
                    account = outcome.userId,
                    rememberedAccount = if (outcome.kept && outcome.remember) outcome.userId else "",
                    message = when {
                        !outcome.persisted -> "课表已取回，但没能保存到本机，重开后需要再取一次。"
                        !outcome.kept && outcome.remember -> "课表已取回，但账号没能保存到本机。"
                        else -> "课表已更新，共 ${outcome.timetable.courseCount} 门课。"
                    },
                    messageId = ++messageId
                )
            is Outcome.Refused -> current.copy(
                signingIn = false,
                account = if (outcome.signedOut) "" else current.account,
                // 静默续登失败时不弹提示打断用户：已经存下来的课表照常用。
                message = if (silent) current.message else outcome.reason,
                messageId = if (silent) current.messageId else ++messageId
            )
        }
    }

    override fun refresh() {
        val remembered = remembered()
        if (remembered == null) {
            publishNote("请先登录研究生系统，再更新课表。")
            return
        }
        signIn(remembered.account, remembered.password, remember = true, silent = false)
    }

    private fun remembered(): Credentials? = try { accounts.load() } catch (_: Exception) { null }

    override fun signOut() {
        scope.launch {
            val cleared = withContext(dispatcher) {
                try { accounts.clear(); true } catch (_: Exception) { false }
            }
            token = null
            mutableState.value = mutableState.value.copy(
                account = "",
                rememberedAccount = if (cleared) "" else mutableState.value.rememberedAccount,
                message = if (cleared) "已退出登录，本机保存的课表仍然保留。" else "退出登录没能完成，请重试。",
                messageId = ++messageId
            )
        }
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
                publishNote("开学日期未能保存，请重试。")
                return@launch
            }
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

    override fun deleteTimetable() {
        scope.launch {
            val cleared = withContext(dispatcher) {
                try { store.clear(); true } catch (_: Exception) { false }
            }
            // The term start is kept: it belongs to the term, not to the timetable that just went.
            val current = mutableState.value
            val kept = if (cleared) null else current.timetable
            mutableState.value = current.withTimetable(kept, current.termStartEpochDay).copy(
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
            publishNote("还没有课表可以修改，请先登录取回课表。")
            return
        }
        if (index != null && index !in timetable.courses.indices) {
            publishNote("要修改的课程不存在，请重新打开这门课再试。")
            return
        }
        val drafts = timetable.courses.map { it.toDraft() }.toMutableList()
        if (index == null) drafts += draft else drafts[index] = draft
        rebuild(drafts, if (index == null) "已添加这门课并保存在本机。" else "修改已保存在本机。")
    }

    override fun removeCourse(index: Int) {
        val timetable = mutableState.value.timetable
        if (timetable == null) {
            publishNote("还没有课表可以修改，请先登录取回课表。")
            return
        }
        if (index !in timetable.courses.indices) {
            publishNote("要删除的课程不存在，请刷新后再试。")
            return
        }
        rebuild(
            timetable.courses.filterIndexed { position, _ -> position != index }.map { it.toDraft() },
            "已删除这门课并保存在本机。"
        )
    }

    private fun publishNote(message: String) {
        mutableState.value = mutableState.value.copy(message = message, messageId = ++messageId)
    }

    /**
     * Rebuilds the whole timetable from [drafts] to write it, so a hand edit is held to exactly the
     * ranges and conflicts the server's own list is — there is no second rule book. A refusal leaves
     * the timetable the user is looking at untouched and says why.
     */
    private fun rebuild(drafts: List<TimetableCourseDraft>, savedMessage: String) {
        val timetable = mutableState.value.timetable ?: return
        val rebuilt = try {
            // The 节次 clocks survive an edit: they belong to the timetable, not to the course being
            // changed, and dropping them here would silently empty the grid gutter.
            TimetableValidator.build(timetable.term, drafts, timetable.recognizedAtMillis, timetable.periodTimes)
        } catch (refusal: TimetableException) {
            publishNote(editRefusal(refusal))
            return
        }
        scope.launch {
            val persisted = withContext(dispatcher) {
                try { store.save(rebuilt); true } catch (_: Throwable) { false }
            }
            val latest = mutableState.value
            mutableState.value = if (persisted) latest.withEditedTimetable(rebuilt).copy(
                message = savedMessage,
                messageId = ++messageId
            ) else latest.copy(
                message = "已修改，但未能保存到本机，重开后会丢失。",
                messageId = ++messageId
            )
        }
    }

    /** The wording the server side refuses with, said in the language of an edit form. */
    private fun editRefusal(refusal: TimetableException): String = when (refusal.failure) {
        TimetableFailure.CONFLICT -> "这门课与其他课程时间冲突，请调整星期或节次。"
        TimetableFailure.NO_COURSES -> "这是课表里的最后一门课，如需清空请使用「删除课表」。"
        TimetableFailure.TOO_MANY_COURSES -> "课程数量已达上限（$TIMETABLE_MAX_COURSES 门），请先删除一些课程。"
        TimetableFailure.INVALID_FIELD -> invalidFieldReason(refusal)
        TimetableFailure.NOT_A_TIMETABLE -> "这门课的信息无法保存，请检查后重试。"
    }

    private fun campusRefusal(refusal: TimetableException): String = when (refusal.failure) {
        TimetableFailure.NO_COURSES -> "这个学期还没有排课。"
        TimetableFailure.TOO_MANY_COURSES -> "课程数量异常（超过 $TIMETABLE_MAX_COURSES 门），请稍后重试。"
        TimetableFailure.CONFLICT -> "课表里有两门课时间冲突，请到研究生系统确认后再试。"
        else -> "取回的课表无法识别，请稍后重试。"
    }

    /** "第 2 门课程的星期无法识别" — the clause before the comma names the field that is wrong. */
    private fun invalidFieldReason(refusal: TimetableException): String {
        val field = refusal.message.orEmpty().substringBefore("，").trimEnd('。')
        return if (field.isBlank()) "课程信息不完整或不合法，请检查后重试。" else "$field，请检查后重试。"
    }

    /**
     * A stored course back in the untrusted shape the validator takes, so editing it is parsed by the
     * same rules as an import. 单双周 travels with it: without it a 单周 and a 双周 course sharing
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
     * browsed to and the term start survive, and the browsed week is clamped to what the edit left.
     */
    private fun TimetableUiState.withEditedTimetable(timetable: Timetable): TimetableUiState {
        val edited = withTimetable(timetable, termStartEpochDay)
        return edited.copy(selectedWeek = selectedWeek.coerceIn(1, edited.weekCount.coerceAtLeast(1)))
    }

    private sealed interface Outcome {
        data class Loaded(
            val timetable: Timetable,
            val userId: String,
            val persisted: Boolean,
            val kept: Boolean,
            val remember: Boolean
        ) : Outcome

        data class Refused(val reason: String, val signedOut: Boolean) : Outcome
    }

    private data class Restored(
        val timetable: Timetable?,
        val termStart: Long?,
        val credentials: Credentials?
    )
}

/**
 * Ready-to-use controller for the timetable destination. Its scope is remembered rather than
 * composition-bound so a fetch in flight finishes even if the user leaves the screen.
 */
@Composable
fun rememberTimetableController(): TimetableController {
    val context = LocalContext.current
    val controller = remember(context) {
        TimetableController(
            source = CampusTimetableSource.Real,
            store = TimetableStore(context),
            accounts = GmisAccountStore(context),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        )
    }
    LaunchedEffect(controller) { controller.restore() }
    return controller
}
