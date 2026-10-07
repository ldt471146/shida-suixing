package cn.gxnu.campus.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OOXML arm of the Word import, held to the oracle both arms share: section C of
 * `word/课表打印.expected.txt`, the 15×9 ground-truth matrix. The matrix is parsed out of the oracle
 * file and the document out of the real `word/课表打印.docx` package, so nothing about the sample is
 * restated here by hand and no expectation can drift away from what the document prints.
 *
 * A handful of rules the printed sample cannot show — a manual line break, a nested table, a DTD — are
 * pinned on packages built in this file; each of those tests says so.
 */
class WordDocxTablesTest {

    @Test
    fun thePrintedTimetableComesBackCellForCell() {
        val bytes = sampleBytes(SAMPLE_DOCX)
        assertTrue("the sample is not a ZIP package", isZipPackage(bytes))

        val document = WordDocxTables.read(bytes)
        assertNotNull("the .docx sample did not read as a Word document", document)

        val tables = requireNotNull(document).tables
        assertEquals("the printed timetable is read as one top-level table", 1, tables.size)

        val matrix = matrixOf(oracleText())
        assertEquals("the oracle must parse as a matrix of 15 rows", ROWS, matrix.size)
        matrix.forEachIndexed { index, row ->
            assertEquals("the oracle's row ${index + 1} must hold 9 cells", COLUMNS, row.size)
        }

        assertMatrix(matrix, tables.single().rows)
    }

    /** A merge runs from its restart cell down; only the `restart` cell is the one that prints. */
    @Test
    fun aMergeContinuationHoldsNoTextAndTheCellThatRestartsItDoes() {
        val rows = sampleRows()

        // R3C0 上/   午 and R8C0 下/   午 each restart a block of four period rows, R12C0 晚/   上
        // a block of three; the rows below each of them continue it.
        assertEquals(listOf("上", "   午"), rows[2][0].lines)
        assertFalse("a <w:vMerge w:val=\"restart\"/> cell is not a continuation", rows[2][0].continues)

        for (row in 3..6) {
            assertTrue("R${row + 1}C0 continues the merge R3C0 restarts", rows[row][0].continues)
            assertEquals("R${row + 1}C0 prints no text of its own", emptyList<String>(), rows[row][0].lines)
        }

        // R2C0 (无节次) carries text but no merge, so it must not be read as a continuation either.
        assertEquals(listOf("无节次"), rows[1][0].lines)
        assertFalse("a cell with no <w:vMerge/> is not a continuation", rows[1][0].continues)
    }

    /** The 节次 gutter prints 上/   午 as two paragraphs, the second one indented by spaces. */
    @Test
    fun theGutterCellKeepsItsLeadingSpacesAndBothParagraphs() {
        val cell = sampleRows()[2][0]
        assertEquals(listOf("上", "   午"), cell.lines)
        assertEquals("the cell as one line of text", "上 午", cell.text)
    }

    /** A course cell prints a blank paragraph above the course; the lines stay split, not merged. */
    @Test
    fun aCourseCellKeepsTheBlankParagraphAboveTheCourse() {
        val cell = sampleRows()[3][3]
        assertEquals(
            listOf("", "中国式现代化的理论与实践（电子1班）[3-11周]马希[]"),
            cell.lines
        )
    }

    @Test
    fun bytesThatAreNotADocxPackageReadAsNullRatherThanThrowing() {
        assertNull("plain garbage", WordDocxTables.read(ByteArray(64) { 0x41 }))
        assertNull("a ZIP header with no package behind it", WordDocxTables.read("PK\u0003\u0004nothing".toByteArray()))
        assertNull("a ZIP package with no word/document.xml", WordDocxTables.read(packageOf(entry = null)))
    }

    /** The printed sample has no `<w:br/>`; this package is built here to pin the rule it follows. */
    @Test
    fun aManualLineBreakInsideAParagraphStartsANewLine() {
        val bytes = packageOf(
            "<w:p><w:r><w:t>上</w:t><w:br/><w:t>午</w:t></w:r></w:p><w:p><w:r><w:t>下</w:t></w:r></w:p>"
        )
        val cell = requireNotNull(WordDocxTables.read(bytes)).tables.single().rows.single().single()
        assertEquals(listOf("上", "午", "下"), cell.lines)
    }

    /** The printed sample has no nested table either; this package is built here to pin the rule. */
    @Test
    fun aTableNestedInACellIsNotReadAsAnotherTopLevelTable() {
        val bytes = packageOf(
            "<w:p><w:r><w:t>外</w:t></w:r></w:p>" +
                "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>内</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
        )
        val tables = requireNotNull(WordDocxTables.read(bytes)).tables
        assertEquals("a nested table is not a top-level table", 1, tables.size)
        assertEquals(
            "the nested table's text does not leak into the cell that holds it",
            listOf("外"),
            tables.single().rows.single().single().lines
        )
    }

    /** This package is built here as well: the reader takes untrusted input and must not follow a DTD. */
    @Test
    fun aDocumentXmlThatDeclaresADoctypeIsRefused() {
        val cell = "<w:p><w:r><w:t>x</w:t></w:r></w:p>"
        // The control first: the same package without the DOCTYPE reads, so the null below is the
        // refusal and not an unreadable package.
        assertNotNull(
            "the control package should read",
            WordDocxTables.read(packageOf(cell))
        )
        assertNull(
            "a document declaring an external entity is refused, not parsed",
            WordDocxTables.read(
                packageOf(
                    cell,
                    prolog = "<?xml version=\"1.0\"?>" +
                        "<!DOCTYPE w:document [<!ENTITY e SYSTEM \"file:///etc/hostname\">]>"
                )
            )
        )
    }

    /** The sample's own table, read from the package the test resources hold. */
    private fun sampleRows(): List<List<WordCell>> {
        val document = WordDocxTables.read(sampleBytes(SAMPLE_DOCX))
        assertNotNull("the .docx sample did not read as a Word document", document)
        return requireNotNull(document).tables.single().rows
    }

    /** Holds [rows] to the oracle cell by cell: a continuation is checked by shape, not by text. */
    private fun assertMatrix(matrix: List<List<String>>, rows: List<List<WordCell>>) {
        assertEquals("printed row count", matrix.size, rows.size)
        matrix.forEachIndexed { rowIndex, expectedRow ->
            val actualRow = rows[rowIndex]
            assertEquals("cells printed in row R${rowIndex + 1}", expectedRow.size, actualRow.size)
            expectedRow.forEachIndexed { columnIndex, expected ->
                val cell = actualRow[columnIndex]
                val where = "R${rowIndex + 1}C$columnIndex"
                if (expected == CONTINUATION) {
                    assertTrue("$where prints as the continuation of the merge above it", cell.continues)
                    assertEquals("$where holds no text of its own", emptyList<String>(), cell.lines)
                } else {
                    assertFalse("$where is not a merge continuation", cell.continues)
                    assertEquals("$where lines", expected, cell.lines.joinToString("\n"))
                }
            }
        }
    }

    /** Section C of the oracle as rows of expected cells; the oracle writes a line break as `\n`. */
    private fun matrixOf(oracle: String): List<List<String>> = oracle.lineSequence()
        .filter { MATRIX_ROW.containsMatchIn(it) }
        .map { row ->
            // `R1 |  | 节次 | …`: the label, then one field per cell, each padded by one space.
            row.split('|').drop(1).map { field ->
                field.removePrefix(" ").removeSuffix(" ").replace("\\n", "\n")
            }
        }
        .toList()

    private fun oracleText(): String =
        requireNotNull(javaClass.getResourceAsStream("/word/$ORACLE")) {
            "test resource /word/$ORACLE is missing"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun sampleBytes(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/word/$name")) {
            "test resource /word/$name is missing"
        }.use { it.readBytes() }

    private fun isZipPackage(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    /**
     * A minimal .docx package holding one table of one row and one cell. [cell] is that cell's XML;
     * [entry] overrides the entry name, and a null one packs no document at all.
     */
    private fun packageOf(
        cell: String = "",
        prolog: String = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>",
        entry: String? = DOCUMENT_ENTRY
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            if (entry != null) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(
                    (
                        prolog +
                            "<w:document xmlns:w=\"$W_NS\"><w:body>" +
                            "<w:tbl><w:tr><w:tc>$cell</w:tc></w:tr></w:tbl>" +
                            "</w:body></w:document>"
                        ).toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val SAMPLE_DOCX = "课表打印.docx"
        const val ORACLE = "课表打印.expected.txt"
        const val DOCUMENT_ENTRY = "word/document.xml"
        const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

        /** How the oracle writes a cell that only continues the vertical merge above it. */
        const val CONTINUATION = "<<VERT-MERGED-CONTINUATION>>"

        const val ROWS = 15
        const val COLUMNS = 9

        /** Matches the oracle's labelled matrix rows — `R1 | …` — and nothing else in section C. */
        val MATRIX_ROW = Regex("""^R\d+ \|""")
    }
}
