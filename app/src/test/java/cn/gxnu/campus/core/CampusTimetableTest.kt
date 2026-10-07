package cn.gxnu.campus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真值全部来自研究生系统的**真实响应**（`app/src/test/resources/gmis/`，一次实机登录后原样存下来的），
 * 不是手抄的期望值 —— 接口字段名写错、节次取错位、单双周读丢，这里都会红。
 */
class CampusTimetableTest {

    /** 系统前端自己算的就是这个值；抓包时它发的正是这一串。 */
    @Test
    fun `the password is the md5 the system's own front end sends`() {
        assertEquals("f8b14ae181afa77c67a51d39fc6179ab", CampusTimetableApi.md5Hex("20031125"))
    }

    @Test
    fun `a successful login yields its token`() {
        val result = CampusTimetableApi.parseSignIn(fixture("login.json"))
        assertTrue("expected Ok, got $result", result is CampusTimetableApi.SignIn.Ok)
        assertEquals(true, (result as CampusTimetableApi.SignIn.Ok).token.startsWith("eyJ"))
    }

    @Test
    fun `a refused login says so instead of holding a blank token`() {
        val result = CampusTimetableApi.parseSignIn("""{"zt":"0","msg":"用户名或密码错误！"}""")
        assertEquals("登录失败：用户名或密码错误！", (result as CampusTimetableApi.SignIn.Failed).reason)
    }

    @Test
    fun `a response that is not json fails rather than throwing`() {
        assertTrue(CampusTimetableApi.parseSignIn("<html>502</html>") is CampusTimetableApi.SignIn.Failed)
        assertEquals(null, CampusTimetableApi.parseCourses("<html>502</html>"))
    }

    /**
     * 那份课表有 8 行，其中一行是「无节次」（`ksjc` 为 99）—— 学校给还没排时间的课留的行。
     * 它必须被丢掉：课表里不存在第 0 节。
     */
    @Test
    fun `the unnumbered row is dropped, not turned into period zero`() {
        val courses = CampusTimetableApi.parseCourses(fixture("xskb-xh.json"))!!
        assertEquals(8, countRows(fixture("xskb-xh.json")))
        assertEquals(7, courses.size)
        assertTrue("朱红艳那门是「无节次」行", courses.none { it.teacher == "朱红艳" })
        assertTrue(courses.all { it.startPeriod >= 1 })
    }

    @Test
    fun `every field of the real timetable is read as the system states it`() {
        val timetable = imported()

        assertEquals(
            listOf(
                "周一 6-9 第2-17周[ALL] 非线性系统与混沌1班 / 韦笃取 / ",
                "周二 2-5 第3-11周[ALL] 中国式现代化的理论与实践（电子1班） / 马希 / ",
                "周二 6-9 第2-18周[ALL] 矩阵理论1班 / 邹艳丽 / ",
                "周三 6-8 第12-17周[ALL] 马克思主义与当代科技（电子1班） / 郭友兵 / ",
                "周三 10-13 第11-18周[ALL] 工程计算方法随机过程1班 / 曾上游 / ",
                "周四 6-9 第2-10周[ALL] 工程计算方法随机过程1班 / 李自立 / ",
                // 系统里这门写的是「3-17周[单周]」，单双周必须原样留下来。
                "周五 10-13 第3-17周[ODD] 深度学习1班 / 夏海英 / "
            ),
            timetable.courses.sortedWith(compareBy({ it.weekday }, { it.startPeriod })).map { it.render() }
        )
    }

    /** 左侧节次栏要的是每一节的时间；跨多节的课按首节起、末节止。 */
    @Test
    fun `the gutter clocks come through and a span joins its first clock to its last`() {
        val timetable = imported()
        assertEquals("08:30-09:10", timetable.periodTime(1))
        assertEquals("11:40-12:20", timetable.periodTime(5))
        assertEquals("14:00-14:40", timetable.periodTime(6))
        assertEquals("20:30-21:10", timetable.periodTime(13))
        assertEquals(null, timetable.periodTime(14))

        val chaos = timetable.courses.first { it.name == "非线性系统与混沌1班" }
        assertEquals("14:00-17:00", timetable.timeSpanOf(chaos))
        val marxism = timetable.courses.first { it.name.startsWith("马克思主义") }
        assertEquals("14:00-16:15", timetable.timeSpanOf(marxism))
    }

    /** 这门课最早第 2 周才开始，所以「第一周有课」不是第 1 周。 */
    @Test
    fun `the first week with classes is where a fresh timetable should open`() {
        val timetable = imported()
        assertEquals(2, timetable.firstWeekWithCourses)
        assertTrue(timetable.coursesInWeek(2).isNotEmpty())
        assertTrue(timetable.coursesInWeek(1).isEmpty())
    }

    /** 单双周：一门单周课不许出现在双周，缺了这条会静默显示错课。 */
    @Test
    fun `an odd week course is absent from an even week`() {
        val timetable = imported()
        val deep = timetable.courses.first { it.name == "深度学习1班" && it.teacher == "夏海英" }
        assertEquals(WeekParity.ODD, deep.parity)
        assertTrue(deep.runsInWeek(3))
        assertTrue(!deep.runsInWeek(4))
    }

    private fun imported(): Timetable = CampusTimetableMapper.build(
        CampusTimetableApi.parseCourses(fixture("xskb-xh.json"))!!,
        term = "",
        recognizedAtMillis = 0L
    )

    private fun TimetableCourse.render(): String =
        "${weekdayLabel} ${startPeriod}-${endPeriod} 第${startWeek}-${endWeek}周[${parity.name}] $name / $teacher / $room"

    /** 原始响应里的行数，用来证明「丢掉的是一门」而不是「本来就少」。 */
    private fun countRows(json: String): Int = Regex("\"bjmc\"").findAll(json).count()

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/gmis/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture /gmis/$name is missing")
}
