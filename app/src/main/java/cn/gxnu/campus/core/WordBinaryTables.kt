package cn.gxnu.campus.core

/**
 * Reads a Word 97-2003 binary document (OLE2 compound file) into a [WordDocument].
 *
 * FROZEN SIGNATURE — replaced in place by the binary-format implementation. Callers only ever use
 * [WordDocuments.read], so nothing else needs to know how the text is recovered.
 *
 * The reader walks the format bottom-up: the compound file's directory and FAT give the
 * `WordDocument` / `1Table` / `Data` streams, the FIB gives the piece table (`fcClx`) and the
 * paragraph map (`fcPlcfBtePapx`), and the piece table's pieces give the character stream.
 *
 * Two things the character stream alone cannot settle, and where this reader therefore goes to the
 * document's own table definitions rather than guessing from the characters:
 *
 *  - `U+0007` is both a cell mark and a row mark, so counting cells cannot say where a row ends.
 *  - A merged continuation cell is character-for-character an empty cell, so "the cell is empty"
 *    cannot say whether the lesson above it keeps running through the row.
 *
 * Both answers live in the row's own table definition, which this file family stores as one record
 * per row in the `Data` stream: [SPRM_TABLE_TERMINATOR] (sprmPFTtp) marks the record as a row end
 * and [SPRM_TABLE_DEFINITION] (sprmTDefTable) carries the row's cell count plus the per-cell merge
 * bits. Those records are consumed in document order against the cell marks, and the three
 * invariants that make that safe are checked before the answer is used — see [RowSpecs.rows]: one
 * record per row, a row holding exactly its record's cell count, and a row mark that prints
 * nothing. A document whose rows do not line up that way, or that keeps its table properties
 * somewhere else — Word itself writes them into the row mark's PAPX inside the FKP page — falls
 * back to [inferredRows], which reads the text exactly but can only guess at a merge.
 */
internal object WordBinaryTables {

    fun read(bytes: ByteArray): WordDocument? = try {
        readDocument(bytes)
    } catch (e: IndexOutOfBoundsException) {
        // The file is untrusted input: a length that runs off the end reads as "not a document".
        null
    } catch (e: NegativeArraySizeException) {
        null
    }

    private fun readDocument(bytes: ByteArray): WordDocument? {
        val file = CompoundFile.open(bytes) ?: return null
        val word = file.stream(WORD_DOCUMENT) ?: return null
        val head = Fib.head(word) ?: return null
        val table = file.stream(if (head.usesOneTable) ONE_TABLE else ZERO_TABLE) ?: return null

        // The fc/lcb blob follows the FIB's counted fields. Word 97 writes the counts in, so the blob
        // starts at 154; a writer that omits them puts it at 148. The right one is the one whose piece
        // table reads, so each candidate is carried through the whole read before it is believed.
        for (pairs in PAIRS_OFFSETS) {
            val fib = Fib.at(word, head, pairs) ?: continue
            readTimetable(file, word, table, fib)?.let { return it }
        }
        return null
    }

    private fun readTimetable(
        file: CompoundFile,
        word: ByteArray,
        table: ByteArray,
        fib: Fib
    ): WordDocument? {
        val pieces = PieceTable.parse(table, fib) ?: return null
        val text = pieces.text(word) ?: return null
        val mainLength = minOf(fib.mainTextChars, text.length)
        if (mainLength <= 0) return null
        val main = text.substring(0, mainLength)

        // Cells come off the paragraph map when it is readable, so a title paragraph printed above
        // the table stays out of the table's first cell; the character stream is the fallback.
        val cells = paragraphCells(word, table, fib, pieces, text, mainLength) ?: streamCells(main)
        if (cells.isEmpty()) return null

        val rows = RowSpecs.parse(file.stream(DATA))?.rows(cells) ?: inferredRows(cells)
        if (rows.isNullOrEmpty()) return null
        return WordDocument(listOf(WordTable(rows)))
    }

    // ----------------------------------------------------------------- cells

    /** One cell's content, already split at its paragraph marks. */
    private class RawCell(val lines: List<String>) {

        val blank: Boolean get() = lines.all { it.isEmpty() }
    }

    /** A cell's text as printed lines: a paragraph mark and a manual line break both start one. */
    private fun linesOf(content: String): List<String> =
        content.split(PARAGRAPH_MARK, LINE_BREAK, PAGE_BREAK)

    /**
     * The cell marks in document order, grouped by paragraph. A cell is the run of paragraphs ending
     * with one that terminates in a cell mark; a paragraph terminating in a paragraph mark only
     * continues the run. A run no cell mark closes — the title above the table, the footer below it
     * — is not a cell, and nothing before the first cell mark is one either, so the first cell holds
     * its own closing paragraph and not the title printed above the table.
     */
    private fun paragraphCells(
        word: ByteArray,
        table: ByteArray,
        fib: Fib,
        pieces: PieceTable,
        text: String,
        mainLength: Int
    ): List<RawCell>? {
        val boundaries = paragraphBoundaries(word, table, fib) ?: return null
        val marks = boundaries.mapNotNull { fc -> pieces.cpOf(fc) }.distinct().sorted()
        if (marks.size < 2) return null

        val cells = ArrayList<RawCell>()
        val buffer = StringBuilder()
        var started = false
        var inCell = false
        for (i in 0 until marks.size - 1) {
            val start = marks[i]
            val end = minOf(marks[i + 1], text.length)
            if (start >= mainLength || end <= start) continue
            val paragraph = text.substring(start, end)
            val closed = paragraph.endsWith(CELL_MARK)
            val content = if (closed) paragraph.dropLast(1) else paragraph
            if (!started) {
                // Nothing printed before the first cell mark belongs to a cell, so the first cell
                // holds its own closing paragraph only and the title above the table stays out of it.
                if (!closed) continue
                cells += RawCell(linesOf(content))
                started = true
                continue
            }
            if (!inCell) {
                buffer.setLength(0)
                inCell = true
            }
            buffer.append(content)
            if (closed) {
                cells += RawCell(linesOf(buffer.toString()))
                inCell = false
            }
        }
        return cells
    }

    /**
     * The paragraph boundaries the document's own FKP pages hold, as FC offsets into the text. The
     * page number array and each page's boundary array are read for what they are; the PAPX bytes
     * behind a page's byte array are not needed to bound a paragraph.
     */
    private fun paragraphBoundaries(word: ByteArray, table: ByteArray, fib: Fib): List<Int>? {
        val (fc, lcb) = fib.papxTable
        if (fc < 0 || lcb < 12 || fc + lcb > table.size) return null
        val count = (lcb - 4) / 8
        if (count <= 0 || fc + 4 * (count + 1) + 4 * count > table.size) return null

        val boundaries = ArrayList<Int>()
        for (index in 0 until count) {
            val page = table.u32(fc + 4 * (count + 1) + 4 * index)
            val base = page * PAGE_SIZE
            if (page < 0 || base < 0 || base + PAGE_SIZE > word.size) return null
            val runs = word[base + PAGE_SIZE - 1].toInt() and 0xFF
            if (runs == 0) continue
            for (i in 0..runs) boundaries += word.u32(base + 4 * i)
        }
        val sorted = boundaries.distinct().sorted()
        return if (sorted.size < 2) null else sorted
    }

    /** The same cells straight off the character stream, for a document with no readable FKP. */
    private fun streamCells(main: String): List<RawCell> {
        val cells = ArrayList<RawCell>()
        val buffer = StringBuilder()
        var seenMark = false
        for (ch in main) {
            if (ch == CELL_MARK) {
                // The first cell starts after the last paragraph break before it, which keeps the
                // title printed above the table out of it.
                val seen = buffer.toString()
                val content = if (seenMark) seen else seen.substring(seen.lastIndexOf(PARAGRAPH_MARK) + 1)
                cells += RawCell(linesOf(content))
                buffer.setLength(0)
                seenMark = true
            } else {
                buffer.append(ch)
            }
        }
        return cells
    }

    // ------------------------------------------------------------- row shape

    /** What one row's own table definition says: how many cells it holds and which ones continue. */
    private class RowSpec(val cellCount: Int, val continues: BooleanArray)

    /** The `Data` stream as one row definition per record, in document order. */
    private class RowSpecs(private val specs: List<RowSpec>) {

        /**
         * The rows the records describe: each record claims its own number of cells, the mark that
         * closes the row follows them, and nothing may be left over. Any of those failing means the
         * records and the cell marks do not describe the same table, so the answer is refused rather
         * than patched up.
         */
        fun rows(cells: List<RawCell>): List<List<WordCell>>? {
            var index = 0
            val rows = ArrayList<List<WordCell>>(specs.size)
            for (spec in specs) {
                val row = ArrayList<WordCell>(spec.cellCount)
                for (column in 0 until spec.cellCount) {
                    val cell = cells.getOrNull(index++) ?: return null
                    row += if (spec.continues[column]) {
                        WordCell(emptyList(), continues = true)
                    } else {
                        WordCell(cell.lines)
                    }
                }
                val mark = cells.getOrNull(index++) ?: return null
                if (!mark.blank) return null
                rows += row
            }
            return if (index == cells.size) rows else null
        }

        companion object {
            fun parse(data: ByteArray?): RowSpecs? {
                if (data == null) return null
                val specs = ArrayList<RowSpec>()
                var offset = 0
                while (offset + 3 <= data.size) {
                    val size = data.u16(offset)
                    if (size < 8 || offset + 2 + size > data.size) break
                    val spec = rowSpec(data, offset + 2, size) ?: break
                    specs += spec
                    offset += 2 + size
                }
                return if (specs.isEmpty()) null else RowSpecs(specs)
            }

            /**
             * One record as a row. [SPRM_TABLE_TERMINATOR] with its bit set says the record ends a
             * row; [SPRM_TABLE_DEFINITION] then holds the cell count and the merge bits. A record
             * body is the row's PAPX, and only these two sprms matter here, so the definition is
             * found by its own two bytes and accepted only once it passes the shape checks.
             */
            private fun rowSpec(record: ByteArray, offset: Int, size: Int): RowSpec? {
                if (!hasTerminator(record, offset, size)) return null
                for (at in offset..offset + size - 5) {
                    if (record.u16(at) == SPRM_TABLE_DEFINITION) {
                        definition(record, at + 2, offset + size)?.let { return it }
                    }
                }
                return null
            }

            private fun hasTerminator(record: ByteArray, offset: Int, size: Int): Boolean {
                for (at in offset..offset + size - 3) {
                    if (record.u16(at) == SPRM_TABLE_TERMINATOR && record[at + 2].toInt() and 0xFF == 1) {
                        return true
                    }
                }
                return false
            }

            private fun definition(record: ByteArray, at: Int, limit: Int): RowSpec? {
                val cells = record[at + 2].toInt() and 0xFF
                if (cells !in 1..MAX_COLUMNS) return null
                val length = record.u16(at)
                val centres = at + 3 + 2 * (cells + 1)
                if (length < 1 + 2 * (cells + 1) + cells * TC_BYTES) return null
                if (centres + cells * TC_BYTES > limit) return null

                // The column centres have to climb; that is what a byte pair that merely looks like a
                // table definition fails.
                var previous = -1
                for (i in 0..cells) {
                    val centre = record.u16(at + 3 + 2 * i)
                    if (centre <= previous) return null
                    previous = centre
                }

                val continues = BooleanArray(cells)
                for (column in 0 until cells) {
                    val tc = centres + column * TC_BYTES
                    val flags = record.u16(tc)
                    // Two layouts are in the wild: the specification's TC keeps the bits at the bottom
                    // of the word with a zero word after them, while the one this file family writes
                    // puts the cell's width there and the same two bits five places up. A zero second
                    // word means the specification's layout.
                    val shift = if (record.u16(tc + 2) != 0) WIDTH_LAYOUT_SHIFT else SPEC_LAYOUT_SHIFT
                    val merged = flags shr shift and 1 == 1
                    val restarts = flags shr (shift + 1) and 1 == 1
                    continues[column] = merged && !restarts
                }
                return RowSpec(cells, continues)
            }
        }
    }

    // ------------------------------------------------------------------ rows

    /**
     * The rows of a document that carries no row definitions: the first row fixes the column count
     * and every row after it is that many cells plus the row mark. A merge can only be guessed at —
     * an empty cell below a lesson that is still running reads as a continuation — which is why this
     * path is the fallback and not the answer.
     */
    private fun inferredRows(cells: List<RawCell>): List<List<WordCell>>? {
        val columns = columnCount(cells) ?: return null
        val rows = ArrayList<List<WordCell>>()
        var running = BooleanArray(columns)
        var index = 0
        while (index + columns <= cells.size) {
            val content = (0 until columns).map { cells[index + it] }
            index += columns
            if (cells.getOrNull(index)?.blank == true) index++
            if (content[0].lines.joinToString("\n").trim().isNotEmpty()) running = BooleanArray(columns)
            rows += content.mapIndexed { column, cell ->
                if (!cell.blank) {
                    running[column] = true
                    WordCell(cell.lines)
                } else if (running[column]) {
                    WordCell(emptyList(), continues = true)
                } else {
                    WordCell(cell.lines)
                }
            }
        }
        return rows.ifEmpty { null }
    }

    /** The week names the first row prints tell how many cells a row of this table holds. */
    private fun columnCount(cells: List<RawCell>): Int? {
        for (index in cells.indices) {
            if (cells[index].lines.joinToString("\n").contains(LAST_WEEKDAY)) {
                return (index + 1).takeIf { it in 2..MAX_COLUMNS }
            }
        }
        return null
    }

    // ------------------------------------------------------------------- FIB

    private class Fib(
        val usesOneTable: Boolean,
        val mainTextChars: Int,
        val clx: Pair<Int, Int>,
        val papxTable: Pair<Int, Int>
    ) {
        /** The FIB fields that do not move with the fc/lcb blob. */
        class Head(val usesOneTable: Boolean, val mainTextChars: Int)

        companion object {
            fun head(word: ByteArray): Head? {
                if (word.size < FIB_MIN_BYTES) return null
                if (word.u16(0) != FIB_SIGNATURE) return null
                return Head(
                    usesOneTable = (word.u16(FIB_FLAGS) shr FIB_ONE_TABLE_BIT) and 1 == 1,
                    mainTextChars = word.u32(FIB_MAIN_TEXT_CHARS)
                )
            }

            /** The FIB as the fc/lcb blob at [pairs] would have it, or null when it does not fit. */
            fun at(word: ByteArray, head: Head, pairs: Int): Fib? {
                val clx = pairs + INDEX_CLX * 8
                val papx = pairs + INDEX_PAPX * 8
                if (clx + 8 > word.size || papx + 8 > word.size) return null
                val clxLength = word.u32(clx + 4)
                if (clxLength < 5) return null
                return Fib(
                    head.usesOneTable,
                    head.mainTextChars,
                    word.u32(clx) to clxLength,
                    word.u32(papx) to word.u32(papx + 4)
                )
            }
        }
    }

    /** Where each run of the document's text lives, and how wide its characters are. */
    private class PieceTable(private val pieces: List<Piece>) {

        private class Piece(val cpStart: Int, val cpEnd: Int, val fc: Int, val step: Int)

        /** The character at [cp] is held at text offset [fc]; a piece covers its start through end. */
        fun cpOf(fc: Int): Int? {
            for (piece in pieces) {
                val end = piece.fc + (piece.cpEnd - piece.cpStart) * piece.step
                if (fc in piece.fc..end) return piece.cpStart + (fc - piece.fc) / piece.step
            }
            return null
        }

        fun text(word: ByteArray): String? {
            val out = StringBuilder()
            for (piece in pieces) {
                val count = piece.cpEnd - piece.cpStart
                if (count <= 0) continue
                val length = count * piece.step
                if (piece.fc < 0 || piece.fc + length > word.size) return null
                out.append(
                    if (piece.step == 1) {
                        // An 8-bit piece holds cp1252; the sample and every Chinese print-out is 16-bit.
                        String(word, piece.fc, length, Charsets.ISO_8859_1)
                    } else {
                        String(word, piece.fc, length, Charsets.UTF_16LE)
                    }
                )
            }
            return out.toString()
        }

        companion object {
            fun parse(table: ByteArray, fib: Fib): PieceTable? {
                val (fc, lcb) = fib.clx
                if (fc < 0 || lcb < 5 || fc + lcb > table.size) return null
                var at = fc
                val end = fc + lcb
                // Property modifiers come first, then the piece table itself.
                while (at + 3 <= end && table[at].toInt() and 0xFF == PRC) {
                    at += 3 + table.u16(at + 1)
                }
                if (at + 5 > end || table[at].toInt() and 0xFF != PCDT) return null
                val length = table.u32(at + 1)
                val start = at + 5
                if (length < 4 || start + length > table.size) return null
                val count = (length - 4) / 12
                if (count <= 0) return null

                val pieces = ArrayList<Piece>(count)
                for (index in 0 until count) {
                    val cpStart = table.u32(start + 4 * index)
                    val cpEnd = table.u32(start + 4 * (index + 1))
                    val stored = table.u32(start + 4 * (count + 1) + 8 * index + 2)
                    val compressed = stored and COMPRESSED_PIECE != 0
                    val real = stored and COMPRESSED_PIECE.inv()
                    pieces += Piece(
                        cpStart,
                        cpEnd,
                        if (compressed) real / 2 else real,
                        if (compressed) 1 else 2
                    )
                }
                return PieceTable(pieces)
            }
        }
    }

    // -------------------------------------------------------- compound file

    /** The streams an OLE2 compound file holds, by name. */
    private class CompoundFile(private val streams: Map<String, ByteArray>) {

        fun stream(name: String): ByteArray? = streams[name]

        companion object {
            fun open(bytes: ByteArray): CompoundFile? {
                if (bytes.size < HEADER_BYTES) return null
                for (i in SIGNATURE.indices) {
                    if (bytes[i] != SIGNATURE[i]) return null
                }
                val sectorShift = bytes.u16(HEADER_SECTOR_SHIFT)
                val miniShift = bytes.u16(HEADER_MINI_SHIFT)
                if (sectorShift !in 7..15 || miniShift !in 2..sectorShift) return null
                val sectorSize = 1 shl sectorShift
                val miniSize = 1 shl miniShift
                val sectors = bytes.size / sectorSize - 1
                if (sectors <= 0) return null

                val fat = fat(bytes, sectorSize, sectors, fatSectors(bytes, sectorSize, sectors)) ?: return null
                val miniFatStart = bytes.u32(HEADER_MINI_FAT_START)
                val miniFat = if (miniFatStart in 0 until sectors) {
                    fat(bytes, sectorSize, sectors, chain(fat, miniFatStart, sectors)) ?: IntArray(0)
                } else {
                    IntArray(0)
                }

                val directory = bytes.readChain(chain(fat, bytes.u32(HEADER_DIRECTORY), sectors), sectorSize)
                    ?: return null
                val entries = ArrayList<Entry>(directory.size / DIRECTORY_ENTRY)
                for (i in 0 until directory.size / DIRECTORY_ENTRY) {
                    val at = i * DIRECTORY_ENTRY
                    val nameLength = directory.u16(at + ENTRY_NAME_LENGTH)
                    if (nameLength < 2 || nameLength > DIRECTORY_ENTRY - ENTRY_NAME_LENGTH) continue
                    entries += Entry(
                        name = String(directory, at, nameLength - 2, Charsets.UTF_16LE),
                        type = directory[at + ENTRY_TYPE].toInt() and 0xFF,
                        start = directory.u32(at + ENTRY_START),
                        size = directory.u32(at + ENTRY_SIZE)
                    )
                }
                val root = entries.firstOrNull { it.type == TYPE_ROOT }
                val miniStream = root?.let {
                    bytes.readChain(chain(fat, it.start, sectors), sectorSize)
                }

                val streams = HashMap<String, ByteArray>()
                for (entry in entries) {
                    if (entry.type != TYPE_STREAM || entry.size <= 0) continue
                    val content = if (entry.size < MINI_CUTOFF && miniStream != null) {
                        miniStream.readMembers(
                            chain(miniFat, entry.start, miniFat.size),
                            miniSize,
                            entry.size
                        )
                    } else {
                        bytes.readChain(chain(fat, entry.start, sectors), sectorSize, entry.size)
                    }
                    if (content != null) streams[entry.name] = content
                }
                return CompoundFile(streams)
            }

            private class Entry(val name: String, val type: Int, val start: Int, val size: Int)

            /** The sector numbers the header's DIFAT lists, plus those the DIFAT sectors chain on. */
            private fun fatSectors(bytes: ByteArray, sectorSize: Int, sectors: Int): List<Int> {
                val numbers = ArrayList<Int>()
                for (i in 0 until HEADER_DIFAT_ENTRIES) numbers += bytes.u32(HEADER_DIFAT + 4 * i)
                var next = bytes.u32(HEADER_DIFAT_START)
                var guard = 0
                while (next in 0 until sectors && guard++ <= sectors) {
                    val at = (next + 1) * sectorSize
                    if (at + sectorSize > bytes.size) break
                    for (i in 0 until sectorSize / 4 - 1) numbers += bytes.u32(at + 4 * i)
                    next = bytes.u32(at + sectorSize - 4)
                }
                return numbers.filter { it in 0 until sectors }.distinct()
            }

            private fun fat(bytes: ByteArray, sectorSize: Int, sectors: Int, from: List<Int>): IntArray? {
                if (from.size > sectors) return null
                val fat = IntArray(sectors * (sectorSize / 4))
                var at = 0
                for (sector in from) {
                    val offset = (sector + 1) * sectorSize
                    if (offset < 0 || offset + sectorSize > bytes.size) continue
                    for (i in 0 until sectorSize / 4) fat[at++] = bytes.u32(offset + 4 * i)
                }
                return fat
            }

            /** The sectors a chain visits from [start], following [fat] and stopping on a bad link. */
            private fun chain(fat: IntArray, start: Int, limit: Int): List<Int> {
                val members = ArrayList<Int>()
                val seen = HashSet<Int>()
                var next = start
                while (next in 0 until limit && seen.add(next) && next < fat.size) {
                    members += next
                    next = fat[next]
                }
                return members
            }
        }
    }

    /** The bytes those sectors hold, joined; [limit] cuts the tail the stream does not use. */
    private fun ByteArray.readChain(sectors: List<Int>, sectorSize: Int, limit: Int = Int.MAX_VALUE): ByteArray? {
        if (sectors.isEmpty()) return null
        val length = minOf(sectors.size.toLong() * sectorSize, limit.toLong()).toInt()
        if (length <= 0) return null
        val out = ByteArray(length)
        var at = 0
        for (sector in sectors) {
            val offset = (sector + 1) * sectorSize
            if (offset < 0 || offset + sectorSize > size) return null
            val take = minOf(sectorSize, length - at)
            if (take <= 0) break
            System.arraycopy(this, offset, out, at, take)
            at += take
        }
        return out
    }

    /** The bytes of a small stream's own mini sectors, joined, cut to [limit]. */
    private fun ByteArray.readMembers(members: List<Int>, memberSize: Int, limit: Int): ByteArray? {
        if (members.isEmpty()) return null
        val length = minOf(members.size.toLong() * memberSize, limit.toLong()).toInt()
        if (length <= 0) return null
        val out = ByteArray(length)
        var at = 0
        for (member in members) {
            val offset = member * memberSize
            if (offset < 0 || offset + memberSize > size) return null
            val take = minOf(memberSize, length - at)
            if (take <= 0) break
            System.arraycopy(this, offset, out, at, take)
            at += take
        }
        return out
    }

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)

    private const val WORD_DOCUMENT = "WordDocument"
    private const val ONE_TABLE = "1Table"
    private const val ZERO_TABLE = "0Table"
    private const val DATA = "Data"

    private const val CELL_MARK = '\u0007'
    private const val PARAGRAPH_MARK = '\r'
    private const val LINE_BREAK = '\u000B'
    private const val PAGE_BREAK = '\u000C'

    private const val MAX_COLUMNS = 63
    private const val PAGE_SIZE = 512
    private const val TC_BYTES = 20
    private const val SPEC_LAYOUT_SHIFT = 3
    private const val WIDTH_LAYOUT_SHIFT = 5

    private const val SPRM_TABLE_TERMINATOR = 0x2417
    private const val SPRM_TABLE_DEFINITION = 0xD608

    private const val FIB_SIGNATURE = 0xA5EC
    private const val FIB_MIN_BYTES = 512
    private const val FIB_FLAGS = 10
    private const val FIB_ONE_TABLE_BIT = 9
    private const val FIB_MAIN_TEXT_CHARS = 76
    private const val INDEX_PAPX = 13
    private const val INDEX_CLX = 33
    private val PAIRS_OFFSETS = intArrayOf(154, 148)

    private const val PRC = 0x01
    private const val PCDT = 0x02
    private const val COMPRESSED_PIECE = 0x40000000

    private val SIGNATURE = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
    )
    private const val HEADER_BYTES = 512
    private const val HEADER_SECTOR_SHIFT = 30
    private const val HEADER_MINI_SHIFT = 32
    private const val HEADER_DIRECTORY = 48
    private const val HEADER_MINI_FAT_START = 60
    private const val HEADER_DIFAT_START = 68
    private const val HEADER_DIFAT = 76
    private const val HEADER_DIFAT_ENTRIES = 109
    private const val DIRECTORY_ENTRY = 128
    private const val ENTRY_NAME_LENGTH = 64
    private const val ENTRY_TYPE = 66
    private const val ENTRY_START = 116
    private const val ENTRY_SIZE = 120
    private const val TYPE_STREAM = 2
    private const val TYPE_ROOT = 5
    private const val MINI_CUTOFF = 4096
    private const val LAST_WEEKDAY = "星期日"
}
