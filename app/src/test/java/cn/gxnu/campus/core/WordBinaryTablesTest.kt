package cn.gxnu.campus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Word 97-2003 arm of the Word import, held to the oracle both arms share: section C of
 * `word/课表打印.expected.txt`, the 15×9 ground-truth matrix. The matrix is parsed out of the oracle
 * file and the document out of the real `word/课表打印.doc` compound file, so nothing about the
 * sample is restated here by hand.
 *
 * The 0x07 character stream cannot tell a cell mark from a row mark — both are U+0007 — and a merged
 * continuation cell is byte-for-byte an empty one. So the matrix below is what pins those two apart:
 * every cell is also checked against what the document's own row definitions (sprmPFTtp/0x2417 +
 * sprmTDefTable/0xD608 in the `Data` stream, `continues` from the TC fVertMerge/fVertRestart bits)
 * say the row looks like.
 */
class WordBinaryTablesTest {

    @Test
    fun thePrintedTimetableComesBackCellForCell() {
        val bytes = sampleBytes(SAMPLE_DOC)
        assertTrue("the sample is not a compound file", isCompoundFile(bytes))

        val document = WordBinaryTables.read(bytes)
        assertNotNull("the .doc sample did not read as a Word document", document)

        val tables = requireNotNull(document).tables
        assertEquals("the printed timetable is read as one table", 1, tables.size)

        val matrix = matrixOf(oracleText())
        assertEquals("the oracle must parse as a matrix of 15 rows", ROWS, matrix.size)
        matrix.forEachIndexed { index, row ->
            assertEquals("the oracle's row ${index + 1} must hold 9 cells", COLUMNS, row.size)
        }

        assertMatrix(matrix, tables.single().rows)
    }

    /** The caller's own entry point has to hand the .doc arm the same bytes it recognises. */
    @Test
    fun theDocumentEntryPointDispatchesTheCompoundFileToThisReader() {
        val bytes = sampleBytes(SAMPLE_DOC)
        assertTrue("a compound file looks like Word to the picker", WordDocuments.looksLikeWord(bytes))
        assertNotNull("WordDocuments.read must route the .doc to the binary reader", WordDocuments.read(bytes))
    }

    /**
     * The two fixtures are one timetable — Word saved the .docx out of the .doc — so the two readers
     * must agree on it cell for cell, bit for bit. This is the strictest oracle the sample has: it
     * does not restate the printed matrix at all, it holds the binary arm to whatever the OOXML arm
     * reads, including the shape an empty cell takes and where a merge begins and ends.
     */
    @Test
    fun bothArmsReadTheSameTimetableIntoTheSameDocument() {
        val binary = WordBinaryTables.read(sampleBytes(SAMPLE_DOC))
        val packaged = WordDocxTables.read(sampleBytes(SAMPLE_DOCX))
        assertNotNull("the .doc sample did not read", binary)
        assertNotNull("the .docx sample did not read", packaged)
        assertEquals("the .doc and the .docx are the same timetable", packaged, binary)
    }

    /** A merge runs from its restart cell down; only the restart cell prints, the rest carry nothing. */
    @Test
    fun aMergeContinuationHoldsNoTextAndTheCellThatRestartsItDoes() {
        val rows = sampleRows()

        // R3C0 上/   午 and R8C0 下/   午 each restart a block of four period rows, R12C0 晚/   上
        // a block of three; the rows below each of them continue it.
        assertEquals(listOf("上", "   午"), rows[2][0].lines)
        assertFalse("the cell that restarts the merge is not a continuation", rows[2][0].continues)

        for (row in 3..6) {
            assertTrue("R${row + 1}C0 continues the merge R3C0 restarts", rows[row][0].continues)
            assertEquals("R${row + 1}C0 prints no text of its own", emptyList<String>(), rows[row][0].lines)
        }

        // R2C0 (无节次) carries text but no merge, so it must not be read as a continuation either.
        assertEquals(listOf("无节次"), rows[1][0].lines)
        assertFalse("a cell with no merge bit is not a continuation", rows[1][0].continues)
    }

    /**
     * R11 is what an empty-cell guess cannot get right: column 4 (马克思主义…) has no lesson in that
     * row while column 5 (工程计算方法…) still runs through it. Reading "empty means the merge above
     * keeps running" would mark both as continuations; the row's own table definition marks only c5.
     */
    @Test
    fun anEmptyCellIsNotAContinuationUnlessItsOwnRowSaysSo() {
        val row = sampleRows()[10]

        assertEquals("R11C4 prints nothing at all", listOf(""), row[4].lines)
        assertFalse("R11C4 is an empty cell, not the continuation of R8C4", row[4].continues)

        assertTrue("R11C5 continues the lesson R8C5 restarts", row[5].continues)
        assertEquals("R11C5 prints no text of its own", emptyList<String>(), row[5].lines)
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

    /**
     * A document whose rows carry no table definition — what another 教务系统 export could hand us —
     * is still read, but a merge can then only be guessed: an empty cell below a lesson that is still
     * running reads as a continuation. This sample keeps its row definitions in its `Data` stream, so
     * renaming that stream away is what puts the reader on the fallback, and the one cell the guess
     * gets wrong is pinned here so that the day it starts to matter it is visible.
     */
    @Test
    fun aDocumentWithoutRowDefinitionsIsReadByShapeAndOneMergeHasToBeGuessed() {
        val exact = requireNotNull(WordBinaryTables.read(sampleBytes(SAMPLE_DOC))).tables.single().rows
        val guessed =
            requireNotNull(WordBinaryTables.read(sampleBytes(SAMPLE_DOC).withoutDataStream())).tables.single().rows

        assertEquals("the rows still come back", exact.size, guessed.size)
        val differing = ArrayList<String>()
        exact.forEachIndexed { row, cells ->
            assertEquals("cells in row R${row + 1}", cells.size, guessed[row].size)
            cells.forEachIndexed { column, cell ->
                if (cell != guessed[row][column]) differing += "R${row + 1}C$column"
            }
        }
        assertEquals(
            "the guess must differ from the document's own row definitions in exactly one cell",
            listOf("R11C4"),
            differing
        )
        assertTrue("R11C4 is the cell the guess reads as running on", guessed[10][4].continues)
    }

    @Test
    fun bytesThatAreNotACompoundFileReadAsNullRatherThanThrowing() {
        assertNull("plain garbage", WordBinaryTables.read(ByteArray(64) { 0x41 }))
        assertNull("a ZIP package is the other arm's job", WordBinaryTables.read("PK\u0003\u0004docx".toByteArray()))
        assertNull(
            "a compound file header with nothing behind it",
            WordBinaryTables.read(
                byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
            )
        )
    }

    /** The sample's own table, read from the file the test resources hold. */
    private fun sampleRows(): List<List<WordCell>> {
        val document = WordBinaryTables.read(sampleBytes(SAMPLE_DOC))
        assertNotNull("the .doc sample did not read as a Word document", document)
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

    private fun isCompoundFile(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()

    /**
     * The sample with its `Data` stream renamed out of the file's directory — what a document that
     * keeps its rows some other way looks like to this reader. The bytes are only renamed, so the
     * text and every paragraph in the document stay exactly as they were.
     */
    private fun ByteArray.withoutDataStream(): ByteArray {
        val patched = copyOf()
        val name = "Data".toByteArray(Charsets.UTF_16LE) + ByteArray(8)
        val at = (0..patched.size - name.size).firstOrNull { offset ->
            name.indices.all { patched[offset + it] == name[it] }
        }
        requireNotNull(at) { "the sample no longer holds a Data stream directory entry to rename" }
        patched[at + 6] = 'X'.code.toByte()
        return patched
    }

    private companion object {
        const val SAMPLE_DOC = "课表打印.doc"
        const val SAMPLE_DOCX = "课表打印.docx"
        const val ORACLE = "课表打印.expected.txt"

        /** How the oracle writes a cell that only continues the vertical merge above it. */
        const val CONTINUATION = "<<VERT-MERGED-CONTINUATION>>"

        const val ROWS = 15
        const val COLUMNS = 9

        /** Matches the oracle's labelled matrix rows — `R1 | …` — and nothing else in section C. */
        val MATRIX_ROW = Regex("""^R\d+ \|""")
    }
}
