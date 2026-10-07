package cn.gxnu.campus.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The mapper on its own. The printed table is rebuilt from the recorded ground truth matrix rather
 * than from a Word file, so a failure here can only be the mapper's — a reader's problem shows up in
 * the pipeline test instead.
 */
class TimetableWordImporterTest {

    @Test
    fun `a printed timetable becomes the seven courses it prints`() {
        val result = TimetableWordImporter.read(WordDocument(listOf(WordTable(printedMatrix()))), "", 0L)

        assertEquals("the 无节次 row is not a period", 1, result.skippedUnnumbered)
        assertEquals(
            listOf(
                "周一 6-9 第2-17周 非线性系统与混沌1班/韦笃取/",
                "周二 2-5 第3-11周 中国式现代化的理论与实践（电子1班）/马希/",
                "周二 6-9 第2-18周 矩阵理论1班/邹艳丽/",
                "周三 6-8 第12-17周 马克思主义与当代科技（电子1班）/郭友兵/",
                "周三 10-13 第11-18周 工程计算方法随机过程1班/曾上游/",
                "周四 6-9 第2-10周 工程计算方法随机过程1班/李自立/",
                "周五 10-13 第3-17周 深度学习1班/夏海英/"
            ),
            result.timetable.courses.sortedWith(compareBy({ it.weekday }, { it.startPeriod })).map { it.render() }
        )
    }

    /** 第 0 节 is what the 无节次 row would become if it were read as a period; it never is. */
    @Test
    fun `no course is ever placed at period zero`() {
        val result = TimetableWordImporter.read(WordDocument(listOf(WordTable(printedMatrix()))), "", 0L)
        assertEquals(emptyList<TimetableCourse>(), result.timetable.courses.filter { it.startPeriod <= 0 })
        assertEquals(emptyList<TimetableCourse>(), result.timetable.courses.filter { it.teacher == "朱红艳" })
    }

    @Test
    fun `a document with no timetable table is refused and says so`() {
        val prose = WordDocument(listOf(WordTable(listOf(listOf(WordCell(listOf("通知")), WordCell(listOf("内容")))))))
        val refusal = try {
            TimetableWordImporter.read(prose, "", 0L)
            null
        } catch (exception: TimetableException) {
            exception
        }
        assertEquals(TimetableFailure.NOT_A_TIMETABLE, refusal?.failure)
    }

    private fun TimetableCourse.render(): String {
        val span = if (startPeriod == endPeriod) "$startPeriod" else "$startPeriod-$endPeriod"
        val weeks = if (startWeek == endWeek) "$startWeek" else "$startWeek-$endWeek"
        return "${weekdayLabel} $span 第${weeks}周 $name/$teacher/$room"
    }

    /**
     * The 15×9 matrix recorded in the fixture, back as the table a reader would have produced.
     * `<<VERT-MERGED-CONTINUATION>>` is the lower half of a merge, and `\n` is a line break inside
     * a cell — both are how the export spells them.
     */
    private fun printedMatrix(): List<List<WordCell>> {
        val recorded = javaClass.getResourceAsStream("/word/课表打印.expected.txt")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("the recorded matrix is missing from the test resources")
        return recorded.lineSequence()
            .filter { ROW.containsMatchIn(it) }
            .map { line -> line.substringAfter("|").split("|").map { cell(it.trim()) } }
            .toList()
    }

    private fun cell(token: String): WordCell = when {
        token == MERGED -> WordCell(emptyList(), continues = true)
        token.isEmpty() -> WordCell(emptyList())
        else -> WordCell(token.replace("\\n", "\n").split("\n"))
    }

    private companion object {
        const val MERGED = "<<VERT-MERGED-CONTINUATION>>"
        val ROW = Regex("^R\\d+\\s*\\|")
    }
}
