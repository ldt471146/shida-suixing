package cn.gxnu.campus.ui

import cn.gxnu.campus.core.CampusTimetableApi
import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.core.TimetableCourseDraft
import cn.gxnu.campus.data.TimetableStorage
import cn.gxnu.campus.data.TimetableStore
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 控制器的行为。网络与凭据仓都被换成假的，所以这些用例不需要校园网，也不会碰到 Android Keystore。
 *
 * 课表数据用的是**真实响应**存下来的 fixture，不是手抄的期望值。
 */
class TimetableControllerTest {

    private class MemoryStorage : TimetableStorage {
        var value: String? = null
        var termStart: Long? = null
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
        override fun remove() { value = null }
        override fun readTermStart(): Long? = termStart
        override fun writeTermStart(epochDay: Long) { termStart = epochDay }
        override fun removeTermStart() { termStart = null }
    }

    private class FakeVault(var stored: Credentials? = null) : AccountVault {
        var failOn: String? = null
        override fun save(credentials: Credentials) { fail("save"); stored = credentials }
        override fun load(): Credentials? { fail("load"); return stored }
        override fun clear() { fail("clear"); stored = null }
        private fun fail(action: String) { if (failOn == action) throw IllegalStateException("vault $action failed") }
    }

    private class FakeSource(
        var signInResult: CampusTimetableApi.SignIn = CampusTimetableApi.SignIn.Ok(TOKEN),
        var fetchResult: CampusTimetableApi.Fetch = CampusTimetableApi.Fetch.Ok(SAMPLE),
        var fetchFailure: Exception? = null
    ) : CampusTimetableSource {
        var lastUserId: String? = null
        var lastPassword: String? = null
        var lastToken: String? = null

        override fun signIn(userId: String, password: String): CampusTimetableApi.SignIn {
            lastUserId = userId
            lastPassword = password
            return signInResult
        }

        override fun fetchTimetable(token: String): CampusTimetableApi.Fetch {
            lastToken = token
            fetchFailure?.let { throw it }
            return fetchResult
        }
    }

    private fun controller(
        source: FakeSource = FakeSource(),
        storage: MemoryStorage = MemoryStorage(),
        vault: FakeVault = FakeVault()
    ): TimetableController = TimetableController(
        source = source,
        store = TimetableStore(storage),
        accounts = vault,
        // Unconfined so a launched fetch settles before the assertion, without coroutine-test plumbing.
        scope = CoroutineScope(Dispatchers.Unconfined),
        dispatcher = Dispatchers.Unconfined,
        today = { LocalDate.of(2026, 10, 7) },
        now = { 1_700_000_000_000L }
    )

    @Test
    fun `signing in brings the timetable in and remembers who signed in`() {
        val vault = FakeVault()
        val source = FakeSource()
        val state = controller(source = source, vault = vault)

        state.signIn(" 2026010039 ", "20031125", remember = true)

        assertEquals("2026010039", source.lastUserId)
        assertEquals("20031125", source.lastPassword)
        assertEquals(TOKEN, source.lastToken)
        assertEquals(7, state.state.value.timetable?.courseCount)
        assertEquals("2026010039", state.state.value.account)
        assertEquals(Credentials("2026010039", "20031125"), vault.stored)
        assertFalse(state.state.value.signingIn)
        assertTrue(state.state.value.message!!.contains("7 门课"))
    }

    /**
     * 这学期最早的一门课从第 2 周开始。没有开学日期时「当前周」无从谈起，停在第 1 周会让用户
     * 看到一张空网格，以为课表没取回来。
     */
    @Test
    fun `a freshly fetched timetable opens on a week that actually has classes`() {
        val state = controller()
        state.signIn("2026010039", "20031125", remember = false)

        assertEquals(2, state.state.value.selectedWeek)
        assertTrue(state.state.value.timetable!!.coursesInWeek(2).isNotEmpty())
    }

    @Test
    fun `a refused login says why and leaves no timetable behind`() {
        val source = FakeSource(signInResult = CampusTimetableApi.SignIn.Failed("学号或密码不正确。"))
        val state = controller(source = source)

        state.signIn("2026010039", "wrong", remember = true)

        assertEquals("学号或密码不正确。", state.state.value.message)
        assertNull(state.state.value.timetable)
        assertEquals("", state.state.value.account)
        assertFalse(state.state.value.signingIn)
    }

    @Test
    fun `choosing not to remember clears anything the vault held`() {
        val vault = FakeVault(Credentials("old", "old"))
        val state = controller(vault = vault)

        state.signIn("2026010039", "20031125", remember = false)

        assertNull("不记住就必须把旧的清掉", vault.stored)
        assertEquals("", state.state.value.rememberedAccount)
        assertNotNull(state.state.value.timetable)
    }

    /** token 过期等于这次会话没了：账号要让出来，用户得重新登录。 */
    @Test
    fun `an expired session signs the account out and says so`() {
        val source = FakeSource(
            fetchResult = CampusTimetableApi.Fetch.Failed("登录状态已过期，请重新登录。", expired = true)
        )
        val state = controller(source = source)

        state.signIn("2026010039", "20031125", remember = true)

        assertEquals("", state.state.value.account)
        assertEquals("登录状态已过期，请重新登录。", state.state.value.message)
        assertNull(state.state.value.timetable)
    }

    /** 网络炸了不该逃出协程：它必须收敛成一次失败，否则「正在登录」会一直挂着。 */
    @Test
    fun `a network blow-up is folded into a refusal`() {
        val source = FakeSource(fetchFailure = IllegalStateException("socket closed"))
        val state = controller(source = source)

        state.signIn("2026010039", "20031125", remember = true)

        assertFalse("失败后必须释放登录中状态", state.state.value.signingIn)
        assertNotNull(state.state.value.message)
        assertNull(state.state.value.timetable)
    }

    @Test
    fun `restoring a remembered account signs in again on its own`() {
        val source = FakeSource()
        val state = controller(source = source, vault = FakeVault(Credentials("2026010039", "20031125")))

        state.restore()

        assertEquals("2026010039", source.lastUserId)
        assertEquals("2026010039", state.state.value.account)
        assertEquals("2026010039", state.state.value.rememberedAccount)
        assertEquals(7, state.state.value.timetable?.courseCount)
        assertFalse(state.state.value.restoring)
    }

    @Test
    fun `refreshing without a remembered account asks the user to sign in instead of guessing`() {
        val source = FakeSource()
        val state = controller(source = source)

        state.refresh()

        assertNull("没有记住的账号就不该发请求", source.lastUserId)
        assertEquals("请先登录研究生系统，再更新课表。", state.state.value.message)
    }

    @Test
    fun `signing out forgets the account but keeps the timetable on this device`() {
        val vault = FakeVault()
        val state = controller(vault = vault)
        state.signIn("2026010039", "20031125", remember = true)

        state.signOut()

        assertEquals("", state.state.value.account)
        assertEquals("", state.state.value.rememberedAccount)
        assertNull(vault.stored)
        assertEquals("退出登录不该删掉已经取回来的课表", 7, state.state.value.timetable?.courseCount)
    }

    /** 手工改课表走的是同一条校验，并且改完要真的落到本机。 */
    @Test
    fun `a hand edit of a fetched timetable is validated and persisted`() {
        val storage = MemoryStorage()
        val state = controller(storage = storage)
        state.signIn("2026010039", "20031125", remember = false)
        val target = state.state.value.timetable!!.courses.first { it.room.isEmpty() }

        state.saveCourse(
            state.state.value.timetable!!.courses.indexOf(target),
            TimetableCourseDraft(
                name = target.name, teacher = target.teacher, room = "理二楼 401",
                weekday = target.weekday.toString(), startPeriod = target.startPeriod.toString(),
                endPeriod = target.endPeriod.toString(), startWeek = target.startWeek.toString(),
                endWeek = target.endWeek.toString(), parity = target.parity.name
            )
        )

        assertEquals("理二楼 401", state.state.value.timetable!!.courses.first { it.name == target.name }.room)
        assertTrue("改完必须写进本机", storage.value!!.contains("理二楼 401"))
    }

    @Test
    fun `the gutter clocks survive a hand edit`() {
        val state = controller()
        state.signIn("2026010039", "20031125", remember = false)
        val before = state.state.value.timetable!!.periodTimes

        state.saveCourse(null, TimetableCourseDraft(
            name = "自习-fixture", weekday = "6", startPeriod = "1", endPeriod = "1",
            startWeek = "1", endWeek = "16"
        ))

        assertEquals("时间是课表的属性，编辑一门课不该抹掉它", before, state.state.value.timetable!!.periodTimes)
    }

    private companion object {
        const val TOKEN = "eyJhbGciOiJIUzI1NiJ9.fixture"

        /** 真实响应，实机登录后原样存下来的。 */
        val SAMPLE: List<CampusTimetableApi.CampusCourse> by lazy {
            val json = TimetableControllerTest::class.java.getResourceAsStream("/gmis/xskb-xh.json")
                ?.bufferedReader()?.use { it.readText() } ?: error("fixture /gmis/xskb-xh.json is missing")
            CampusTimetableApi.parseCourses(json)!!
        }
    }
}
