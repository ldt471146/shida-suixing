package cn.gxnu.campus.core

/**
 * One cell of a Word table. [lines] are the cell's paragraphs in order; a manual line break inside a
 * paragraph also starts a new line, so the printed 上午 / 午 gutter cell arrives as two lines.
 */
data class WordCell(
    val lines: List<String>,
    /**
     * True when this cell is the lower half of a vertical merge, so the course printed in the cell
     * above it keeps running through this row. A printed 课表 merges the rows one course occupies,
     * which is the only way the period span survives the export.
     */
    val continues: Boolean = false
) {
    /** The cell as one line of text — what a course cell is parsed from. */
    val text: String get() = lines.joinToString(" ").replace(WHITESPACE, " ").trim()

    val isBlank: Boolean get() = text.isEmpty()

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/** One table: rows in document order, cells left to right. */
data class WordTable(val rows: List<List<WordCell>>)

/** Every table the document holds. A printed 课表 is one table; the rest is title and footer text. */
data class WordDocument(val tables: List<WordTable>)

/**
 * Reads a Word file into a [WordDocument]. Both shapes a 教务系统 print-out arrives in are accepted:
 * the Word 97-2003 binary .doc, and the .docx that Word saves from it. Anything else — including a
 * file too large to hold in memory — is null rather than a guess.
 */
object WordDocuments {

    /** Refuses a file no timetable could be hiding in, well below the memory an import may take. */
    const val MAX_BYTES = 24 * 1024 * 1024

    fun read(bytes: ByteArray): WordDocument? = when {
        bytes.size > MAX_BYTES -> null
        bytes.isCompoundFile() -> WordBinaryTables.read(bytes)
        bytes.isZipPackage() -> WordDocxTables.read(bytes)
        else -> null
    }

    /** Whether [bytes] is one of the two Word container shapes; the picker filter is advisory only. */
    fun looksLikeWord(bytes: ByteArray): Boolean =
        bytes.size <= MAX_BYTES && (bytes.isZipPackage() || bytes.isCompoundFile())

    private fun ByteArray.isZipPackage(): Boolean =
        size >= 4 && this[0] == 0x50.toByte() && this[1] == 0x4B.toByte() &&
            (this[2] == 0x03.toByte() || this[2] == 0x05.toByte() || this[2] == 0x07.toByte())

    private fun ByteArray.isCompoundFile(): Boolean =
        size >= 8 && this[0] == 0xD0.toByte() && this[1] == 0xCF.toByte() &&
            this[2] == 0x11.toByte() && this[3] == 0xE0.toByte()

    /** The entry a .docx keeps its body in, so a reader does not walk the whole archive. */
    internal const val DOCX_DOCUMENT_ENTRY = "word/document.xml"
}
