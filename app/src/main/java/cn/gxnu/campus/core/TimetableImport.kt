package cn.gxnu.campus.core

/** What one Word import produced, and what it left out on purpose. */
data class WordImportResult(
    val timetable: Timetable,
    /**
     * Courses the document printed in the unnumbered 无节次 row. That row is the school's
     * "not scheduled yet" line: it carries no period, so it is never imported as 第 0 节. The count
     * is reported rather than dropped in silence.
     */
    val skippedUnnumbered: Int
)

/**
 * Turns a printed 课表 — as a Word file exported by the 教务系统 — into a [Timetable].
 *
 * The printed shape is a table whose header row names the seven weekdays and whose remaining rows
 * are periods, one row per 节次 with that period's courses in the weekdays' columns. A course cell
 * reads `课程名[3-11周]任课教师[上课地点]`; the brackets are what separate the four fields, which is
 * why the teacher's name and the room can both be blank without the row becoming unreadable.
 *
 * Every draft built here goes through [TimetableValidator], so an imported timetable is held to
 * exactly the ranges and conflicts a recognised one is — there is no second rule book.
 */
object TimetableWordImporter {

    /** Weeks assumed when a cell states no range at all; the school's default term length. */
    private const val ASSUMED_TERM_WEEKS = 20

    fun read(document: WordDocument, term: String, recognizedAtMillis: Long): WordImportResult {
        val shape = document.tables
            .mapNotNull(::shapeOf)
            .maxByOrNull { it.weekdays.size }
            ?: throw TimetableException(
                TimetableFailure.NOT_A_TIMETABLE,
                "这个 Word 文件里没有找到课表表格，请确认导出的是教务系统的课表。"
            )

        val drafts = mutableListOf<TimetableCourseDraft>()
        // The draft each weekday column's last course opened, so a vertical merge can extend it.
        val open = mutableMapOf<Int, Int>()
        // The clock the 节次 column prints for each period, which is what the grid gutter shows.
        val times = mutableMapOf<Int, String>()
        var period = 0
        var skipped = 0

        for (row in shape.rows) {
            val labels = shape.labelCells(row)
            if (isUnnumbered(labels)) {
                skipped += shape.weekdays.keys.count { !(row.getOrNull(it)?.isBlank ?: true) }
                continue
            }
            period = periodOf(labels) ?: (period + 1)
            clockOf(labels)?.let { times[period] = it }

            for ((column, weekday) in shape.weekdays) {
                val cell = row.getOrNull(column) ?: continue
                val previous = open[column]
                if (cell.continues && previous != null) {
                    drafts[previous] = drafts[previous].extendedTo(period)
                    continue
                }
                if (cell.isBlank) continue
                for (line in cell.lines) {
                    val draft = draftOf(line, weekday, period) ?: continue
                    drafts += draft
                    open[column] = drafts.lastIndex
                }
            }
        }

        // Positions are kept so `periodTimes[3]` is still 第 4 节 even when a row states no clock.
        val periodTimes = (1..(times.keys.maxOrNull() ?: 0)).map { times[it].orEmpty() }
        return WordImportResult(
            TimetableValidator.build(term, drafts, recognizedAtMillis, periodTimes),
            skipped
        )
    }

    /**
     * `08:30-09:10` as the 节次 gutter prints it, normalised to the one shape the model stores and
     * the renderer expects: ASCII colon and hyphen, no spaces. A full-width colon is what some
     * exports print, and `~` is what some use for the dash.
     */
    private fun clockOf(labels: List<String>): String? = labels
        .firstNotNullOfOrNull { TIME_RANGE.find(it)?.value }
        ?.map { char ->
            when (char) {
                '：' -> ':'
                '~', '～', '—', '－', '至' -> '-'
                else -> char
            }
        }
        ?.filterNot { it.isWhitespace() }
        ?.joinToString("")

    /** A cell that carries a course, or null when the line is punctuation or a stray label. */
    private fun draftOf(line: String, weekday: Int, period: Int): TimetableCourseDraft? {
        val cleaned = line.trim().trim(*LEADING_SEPARATORS).trim()
        if (cleaned.isEmpty()) return null
        val groups = BRACKET.findAll(cleaned).toList()
        val weeks = groups.firstOrNull { group -> group.groupValues[1].any { it.isDigit() } }
            ?: return TimetableCourseDraft(
                // No week range at all: one course taught all term, not a parse failure.
                name = cleaned,
                weekday = weekday.toString(),
                startPeriod = period.toString(),
                endPeriod = period.toString(),
                startWeek = "1",
                endWeek = ASSUMED_TERM_WEEKS.toString()
            )

        val name = cleaned.substring(0, weeks.range.first).trimEnd(*TRAILING_SEPARATORS).trim()
        if (name.isEmpty()) return null
        val tailStart = weeks.range.last + 1
        val room = groups.firstOrNull { it.range.first > weeks.range.last }
        val teacher = if (room == null) cleaned.substring(tailStart)
        else cleaned.substring(tailStart, room.range.first)
        val span = weekSpanOf(weeks.groupValues[1])
        return TimetableCourseDraft(
            name = name,
            teacher = teacher.trim(),
            room = room?.groupValues?.get(1)?.trim().orEmpty(),
            weekday = weekday.toString(),
            startPeriod = period.toString(),
            endPeriod = period.toString(),
            startWeek = span.first.toString(),
            endWeek = span.last.toString(),
            parity = parityOf(weeks.groupValues[1]).name
        )
    }

    /** `2-18周` / `3周` / `2-18周(单)` read as the week range it stands for. */
    private fun weekSpanOf(text: String): IntRange {
        val numbers = NUMBER.findAll(text).map { it.value.toInt() }.toList()
        val first = numbers.getOrNull(0)?.coerceIn(1, TIMETABLE_MAX_WEEKS) ?: 1
        val last = numbers.getOrNull(1)?.coerceIn(1, TIMETABLE_MAX_WEEKS) ?: first
        return minOf(first, last)..maxOf(first, last)
    }

    private fun parityOf(text: String): WeekParity = when {
        text.any { it in ODD_MARKERS } -> WeekParity.ODD
        text.any { it in EVEN_MARKERS } -> WeekParity.EVEN
        else -> WeekParity.ALL
    }

    /**
     * The 无节次 row: the school's line for courses with no period assigned. It is told apart from a
     * real period row by what the gutter holds — a time range or an explicit 第 N 节 is a period, and
     * only a row with neither can be the unnumbered one.
     */
    private fun isUnnumbered(labels: List<String>): Boolean {
        val text = labels.joinToString(" ")
        if (text.contains(UNNUMBERED_LABEL)) return true
        if (TIME_RANGE.containsMatchIn(text) || SECTION_NUMBER.containsMatchIn(text)) return false
        return labels.any { it.trim() == "0" }
    }

    /** The period a row stands for: an explicit 第 N 节 wins, otherwise the caller counts rows. */
    private fun periodOf(labels: List<String>): Int? = labels
        .asSequence()
        .mapNotNull { SECTION_NUMBER.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        .firstOrNull { it in 1..TIMETABLE_MAX_PERIODS }

    /** Where the weekday columns and the period rows of a printed 课表 are. */
    private class TableShape(
        val weekdays: Map<Int, Int>,
        private val afterHeader: List<List<WordCell>>,
        private val labelColumns: IntRange
    ) {
        val rows: List<List<WordCell>> get() = afterHeader

        /** Everything the 节次 gutter of [row] holds, left of the first weekday column. */
        fun labelCells(row: List<WordCell>): List<String> =
            labelColumns.mapNotNull { row.getOrNull(it)?.text }.filter { it.isNotEmpty() }
    }

    /**
     * Reads a table as a 课表, or null when it is not one. The header row is the one naming at least
     * three weekdays; everything left of the first weekday column is the 节次 gutter.
     */
    private fun shapeOf(table: WordTable): TableShape? {
        val headerIndex = table.rows.indexOfFirst { row -> row.count { weekdayOf(it.text) != null } >= 3 }
        if (headerIndex < 0) return null
        val weekdays = table.rows[headerIndex]
            .mapIndexedNotNull { column, cell -> weekdayOf(cell.text)?.let { column to it } }
            .toMap()
        val firstWeekday = weekdays.keys.min()
        return TableShape(weekdays, table.rows.drop(headerIndex + 1), 0 until firstWeekday)
    }

    private fun weekdayOf(text: String): Int? {
        if (text.isBlank()) return null
        WEEKDAY_ALIASES.forEachIndexed { index, aliases ->
            if (aliases.any { text == it }) return index + 1
        }
        return null
    }

    /** A draft whose span now reaches [period], for the lower half of a vertical merge. */
    private fun TimetableCourseDraft.extendedTo(period: Int): TimetableCourseDraft =
        copy(endPeriod = period.toString())

    private val WEEKDAY_ALIASES = listOf(
        listOf("星期一", "周一", "礼拜一", "星期1"),
        listOf("星期二", "周二", "礼拜二", "星期2"),
        listOf("星期三", "周三", "礼拜三", "星期3"),
        listOf("星期四", "周四", "礼拜四", "星期4"),
        listOf("星期五", "周五", "礼拜五", "星期5"),
        listOf("星期六", "周六", "礼拜六", "星期6"),
        listOf("星期日", "星期天", "周日", "周天", "礼拜日", "礼拜天", "星期7")
    )
    private val BRACKET = Regex("\\[([^\\]]*)\\]")
    private val NUMBER = Regex("\\d{1,3}")
    private val TIME_RANGE = Regex("\\d{1,2}\\s*[:：]\\s*\\d{2}\\s*[-~—－]\\s*\\d{1,2}\\s*[:：]\\s*\\d{2}")
    private val SECTION_NUMBER = Regex("第\\s*(\\d{1,2})\\s*节")
    private const val UNNUMBERED_LABEL = "无节次"
    private val LEADING_SEPARATORS = charArrayOf(',', '，', '、', ';', '；', ' ', '\t')
    private val TRAILING_SEPARATORS = charArrayOf(',', '，', '、', ';', '；', ' ')
    private val ODD_MARKERS = "单奇".toCharArray()
    private val EVEN_MARKERS = "双偶".toCharArray()
}
