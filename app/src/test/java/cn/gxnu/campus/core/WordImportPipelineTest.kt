package cn.gxnu.campus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end gate for the Word import path: the two real 教务系统 print-outs kept in test resources
 * go through the whole chain — [WordDocuments.read] and then [TimetableWordImporter.read] — and the
 * courses that come out are checked against the timetable the document actually prints.
 *
 * The oracle is section C of `word/课表打印.expected.txt`, the 15×9 ground-truth matrix. That matrix
 * is the only place the vertical merges are written down, and a merge is the only thing that carries
 * a period span, so every expected end period below is read off the merge block rather than off the
 * row the course starts in. The two readers are held to the same matrix by their own tests, so both
 * arms of this pipeline are measured against one oracle.
 *
 * The .doc and the .docx arms are separate methods on purpose: the two readers land independently,
 * and a green OOXML arm has to be able to report as green while the binary arm is still red.
 */
class WordImportPipelineTest {

    @Test
    fun docxSampleImportsThePrintedTimetable() {
        assertPrintedTimetable(SAMPLE_DOCX, importOf(SAMPLE_DOCX), PRINTED_COURSES)
    }

    @Test
    fun docSampleImportsThePrintedTimetable() {
        assertPrintedTimetable(SAMPLE_DOC, importOf(SAMPLE_DOC), PRINTED_COURSES)
    }

    /** Runs the whole chain over one sample and prints what it actually produced. */
    private fun importOf(sample: String): WordImportResult {
        val bytes = sampleBytes(sample)
        assertTrue("$sample is not shaped like a Word container", WordDocuments.looksLikeWord(bytes))
        val isZipPackage = bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        assertEquals("$sample went down the wrong reader branch", sample.endsWith(".docx"), isZipPackage)

        val document = WordDocuments.read(bytes)
        println()
        println("=== $sample: WordDocuments.read -> ${if (document == null) "null" else "${document.tables.size} table(s)"} ===")
        document?.tables?.forEachIndexed { index, table -> printTable(sample, index, table) }
        assertNotNull("$sample did not read as a Word document", document)

        val word = requireNotNull(document)
        assertEquals("$sample should hold exactly the printed timetable table", 1, word.tables.size)

        val table = word.tables.single()
        assertEquals("$sample: printed timetable row count", EXPECTED_ROWS, table.rows.size)
        table.rows.forEachIndexed { index, row ->
            assertEquals("$sample: cells in printed row R${index + 1}", EXPECTED_COLUMNS, row.size)
        }

        val result = TimetableWordImporter.read(word, TERM, RECOGNIZED_AT)
        printCourses(sample, result)
        return result
    }

    /** Every course the document prints, in the order the grid shows them. */
    private fun assertPrintedTimetable(sample: String, result: WordImportResult, expected: List<Printed>) {
        val timetable = result.timetable
        assertEquals("$sample: term", TERM, timetable.term)
        assertEquals("$sample: recognizedAtMillis", RECOGNIZED_AT, timetable.recognizedAtMillis)
        assertEquals("$sample: the 无节次 row holds exactly one course", 1, result.skippedUnnumbered)

        val courses = timetable.courses.sortedWith(compareBy({ it.weekday }, { it.startPeriod }))
        assertEquals("$sample: imported course count", expected.size, courses.size)

        expected.forEachIndexed { index, want ->
            val got = courses.getOrNull(index)
            assertNotNull("$sample: course #${index + 1} is missing", got)
            val course = requireNotNull(got)
            val where = "$sample: course #${index + 1} (周${want.weekday} 第${want.startPeriod}-${want.endPeriod}节 ${want.name})"

            assertEquals("$where weekday", want.weekday, course.weekday)
            assertEquals("$where startPeriod", want.startPeriod, course.startPeriod)
            assertEquals("$where endPeriod — the printed merge block, not just its first row", want.endPeriod, course.endPeriod)
            assertEquals("$where name", want.name, course.name)
            assertEquals("$where teacher", want.teacher, course.teacher)
            assertEquals("$where room", "", course.room)
            assertEquals("$where startWeek", want.weeks.first, course.startWeek)
            assertEquals("$where endWeek", want.weeks.last, course.endWeek)
            assertEquals("$where parity", WeekParity.ALL, course.parity)
        }

        // The 无节次 row carries no period; it must never be imported as 第 0 节.
        assertTrue("$sample: a course was imported with a period below 1",
            timetable.courses.none { it.startPeriod <= 0 || it.endPeriod <= 0 })
        // The one course of that row is 深度学习1班 / 朱红艳, and it is dropped on purpose.
        assertTrue("$sample: the 无节次 course was imported anyway",
            timetable.courses.none { it.name == "深度学习1班" && it.teacher == "朱红艳" })

        // The grid is only as tall as the week it shows, so which courses run in 第2周 decides both
        // the period list and the blocks. 第2周 holds three of the seven: 非线性系统与混沌1班
        // (2-17周), 矩阵理论1班 (2-18周) and 工程计算方法随机过程1班/李自立 (2-10周). 中国式现代化
        // (3-11周) and 深度学习1班 (3-17周) start after it, 马克思主义 (12-17周) and 曾上游's
        // 工程计算方法 (11-18周) start well after it. The tallest of the three ends at 第9节.
        val grid = TimetableGridLayout.build(timetable, week = 2)
        assertEquals("$sample: week 2 grid periods", (1..9).toList(), grid.periods)
        assertEquals("$sample: week 2 grid weekday count", 5, grid.days.size)
        assertEquals(
            "$sample: week 2 grid blocks",
            listOf(
                "F1 F2 F3 F4 F5 非线性系统与混沌1班(6-9)",
                "F1 F2 F3 F4 F5 矩阵理论1班(6-9)",
                "F1 F2 F3 F4 F5 F6 F7 F8 F9",
                "F1 F2 F3 F4 F5 工程计算方法随机过程1班(6-9)",
                "F1 F2 F3 F4 F5 F6 F7 F8 F9"
            ),
            grid.days.map(::renderDay)
        )
    }

    private fun sampleBytes(sample: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/word/$sample")) {
            "test resource /word/$sample is missing"
        }.use { it.readBytes() }

    /** The table as the reader recovered it, in the notation section C of the oracle uses. */
    private fun printTable(sample: String, index: Int, table: WordTable) {
        println("--- $sample table #$index: ${table.rows.size} rows × ${table.rows.maxOfOrNull { it.size } ?: 0} cells ---")
        table.rows.forEachIndexed { rowIndex, row ->
            val cells = row.joinToString(" | ") { cell ->
                if (cell.continues) "<<VERT-MERGED-CONTINUATION>>" else cell.lines.joinToString("\\n")
            }
            println("R${rowIndex + 1} | $cells")
        }
    }

    private fun printCourses(sample: String, result: WordImportResult) {
        println("--- $sample imported: ${result.timetable.courseCount} courses, skippedUnnumbered=${result.skippedUnnumbered} ---")
        result.timetable.courses
            .sortedWith(compareBy({ it.weekday }, { it.startPeriod }))
            .forEach { course ->
                println(
                    "  ${course.weekdayLabel} 第${course.startPeriod}-${course.endPeriod}节" +
                        " | ${course.name} | 教师='${course.teacher}' | 地点='${course.room}'" +
                        " | ${course.startWeek}-${course.endWeek}周${course.parity.label}"
                )
            }
        val week2 = TimetableGridLayout.build(result.timetable, week = 2)
        println("--- $sample grid week=2: periods=${week2.periods} ---")
        week2.days.forEach { day -> println("  周${day.weekday}: ${renderDay(day)}") }
        println()
    }

    /** A day's blocks in a form a failure message can be read from: F=free period, name=course span. */
    private fun renderDay(day: TimetableDay): String = day.blocks.joinToString(" ") { block ->
        when (block) {
            is TimetableBlock.Free -> "F${block.period}"
            is TimetableBlock.Course ->
                "${block.course.name}(${block.course.startPeriod}-${block.course.endPeriod})"
        }
    }

    /** One course exactly as the printed timetable shows it, merge block included. */
    private data class Printed(
        val weekday: Int,
        val startPeriod: Int,
        val endPeriod: Int,
        val name: String,
        val teacher: String,
        val weeks: IntRange
    )

    private companion object {
        const val SAMPLE_DOC = "课表打印.doc"
        const val SAMPLE_DOCX = "课表打印.docx"
        const val TERM = "2025-2026学年第一学期"
        const val RECOGNIZED_AT = 1_760_000_000_000L
        const val EXPECTED_ROWS = 15
        const val EXPECTED_COLUMNS = 9

        /**
         * Section C of the oracle, sorted by weekday then start period. A span that reaches
         * 16:20-17:00 in R11 or 20:30-21:10 in R15 is a merge the printed timetable really covers, so
         * it is expected in full — a reader that stops at the block's first row has lost the span,
         * not passed the test.
         *
         * Both arms are held to this one list, with no slack, because both file shapes carry the
         * merge: the .docx as `<w:vMerge>`, the .doc as the TC flags of the `sprmTDefTable` record in
         * its TAP properties. Corroborated two independent ways —
         *   * the `<w:vMerge/>` continuation map read straight out of `word/document.xml` matches
         *     section C in all 135 cells (30 continuation cells on each side, zero differences);
         *   * section A's raw character stream (150 cell marks, 15 rows of 9 + 1) rebuilt into a
         *     15×9 table matches section C's text layer in all 135 cells, so the matrix is neither
         *     misaligned nor invented.
         */
        val PRINTED_COURSES = listOf(
            Printed(1, 6, 9, "非线性系统与混沌1班", "韦笃取", 2..17),
            Printed(2, 2, 5, "中国式现代化的理论与实践（电子1班）", "马希", 3..11),
            Printed(2, 6, 9, "矩阵理论1班", "邹艳丽", 2..18),
            Printed(3, 6, 8, "马克思主义与当代科技（电子1班）", "郭友兵", 12..17),
            Printed(3, 10, 13, "工程计算方法随机过程1班", "曾上游", 11..18),
            Printed(4, 6, 9, "工程计算方法随机过程1班", "李自立", 2..10),
            Printed(5, 10, 13, "深度学习1班", "夏海英", 3..17)
        )
    }
}
