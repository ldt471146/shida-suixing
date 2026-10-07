package cn.gxnu.campus.data

import android.content.Context
import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableCourseDraft
import cn.gxnu.campus.core.TimetableException
import cn.gxnu.campus.core.TimetableValidator
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Storage for the one recognised timetable; a plain preference keeps reads cheap at startup. */
internal interface TimetableStorage {
    fun read(): String?
    fun write(value: String)
    fun remove()

    /** Epoch day of the Monday the term starts on, or null while the user has not set it. */
    fun readTermStart(): Long?
    fun writeTermStart(epochDay: Long)
    fun removeTermStart()
}

/** The timetable is not a secret — it is the same list the school shows anyone who logs in. */
class TimetableStore internal constructor(private val storage: TimetableStorage) {
    constructor(context: Context) : this(AndroidTimetableStorage(context))

    /** A missing, unreadable or no longer valid payload all mean "no timetable", never a crash. */
    fun load(): Timetable? = try { storage.read()?.let(TimetableJson::decode) } catch (_: Exception) { null }

    fun save(timetable: Timetable) = safely { storage.write(TimetableJson.encode(timetable)) }

    fun clear() = safely { storage.remove() }

    /**
     * The term's first Monday. It is what turns "which week is it" from a guess into a calculation,
     * so it is stored beside the timetable and survives re-recognising one.
     */
    fun loadTermStart(): Long? = try {
        storage.readTermStart()?.takeIf { it in MIN_TERM_START_EPOCH_DAY..MAX_TERM_START_EPOCH_DAY }
    } catch (_: Exception) {
        null
    }

    fun saveTermStart(epochDay: Long) = safely {
        if (epochDay !in MIN_TERM_START_EPOCH_DAY..MAX_TERM_START_EPOCH_DAY) throw IllegalArgumentException("term start out of range")
        storage.writeTermStart(epochDay)
    }

    fun clearTermStart() = safely { storage.removeTermStart() }

    private inline fun <T> safely(block: () -> T): T = try { block() }
    catch (_: Exception) { throw TimetableStorageException() }

    private companion object {
        // 1970-01-01 through 2100-01-01: wide enough for any real term, narrow enough to reject a
        // corrupted value that would otherwise compute an absurd current week.
        const val MIN_TERM_START_EPOCH_DAY = 0L
        const val MAX_TERM_START_EPOCH_DAY = 47_482L
    }
}

class TimetableStorageException : Exception("课表暂时无法保存，请稍后重试。")

/**
 * Re-validates on read as well as on write, so a corrupted or hand-edited preference can never
 * reach the grid as a half-parsed course.
 */
internal object TimetableJson {
    private const val SCHEMA_VERSION = 1

    fun encode(timetable: Timetable): String {
        val courses = JsonArray().apply {
            timetable.courses.forEach { course ->
                add(JsonObject().apply {
                    addProperty("name", course.name)
                    addProperty("teacher", course.teacher)
                    addProperty("room", course.room)
                    addProperty("weekday", course.weekday)
                    addProperty("start_period", course.startPeriod)
                    addProperty("end_period", course.endPeriod)
                    addProperty("start_week", course.startWeek)
                    addProperty("end_week", course.endWeek)
                    addProperty("parity", course.parity.name)
                })
            }
        }
        return JsonObject().apply {
            addProperty("version", SCHEMA_VERSION)
            addProperty("term", timetable.term)
            addProperty("recognized_at", timetable.recognizedAtMillis)
            add("courses", courses)
            // The 节次 column's clock labels, one per period. It is an added optional key rather than
            // a schema bump: a payload written before it existed still decodes, and one written with
            // it still decodes on a build that ignores it.
            add("periods", JsonArray().apply { timetable.periodTimes.forEach { add(it) } })
        }.toString()
    }

    fun decode(text: String): Timetable? {
        val root = try {
            JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }.use { reader ->
                val parsed = JsonParser.parseReader(reader)
                if (reader.peek() != JsonToken.END_DOCUMENT) null
                else parsed.takeIf { it.isJsonObject }?.asJsonObject
            }
        } catch (_: Exception) { null } ?: return null
        if ((root.get("version")?.takeIf { it.isJsonPrimitive }?.asInt) != SCHEMA_VERSION) return null
        val courses = root.get("courses")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        // Parity is stored beside the draft and goes back in through the draft, so a 单周 course and
        // a 双周 course sharing one slot are re-validated as a legal pair rather than as a conflict.
        val entries = courses.mapNotNull { element ->
            val course = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            TimetableCourseDraft(
                name = text(course, "name"),
                teacher = text(course, "teacher"),
                room = text(course, "room"),
                weekday = text(course, "weekday"),
                startPeriod = text(course, "start_period"),
                endPeriod = text(course, "end_period"),
                startWeek = text(course, "start_week"),
                endWeek = text(course, "end_week"),
                parity = text(course, "parity")
            )
        }
        return try {
            TimetableValidator.build(text(root, "term"), entries, recognizedAt(root), periodTimes(root))
        } catch (_: TimetableException) { null }
    }

    /** The stored clock labels; a payload written before they existed simply has none. */
    private fun periodTimes(root: JsonObject): List<String> =
        root.get("periods")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { element ->
            element.takeIf { it.isJsonPrimitive }?.asString
        }.orEmpty()

    private fun text(value: JsonObject, key: String): String? =
        value.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun recognizedAt(root: JsonObject): Long =
        root.get("recognized_at")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
}

private class AndroidTimetableStorage(context: Context) : TimetableStorage {
    private val applicationContext = context.applicationContext
    private val preferences by lazy { applicationContext.getSharedPreferences("campus_timetable", Context.MODE_PRIVATE) }
    override fun read(): String? = preferences.getString("timetable_payload", null)
    override fun write(value: String) {
        check(preferences.edit().putString("timetable_payload", value).commit())
    }
    override fun remove() { check(preferences.edit().remove("timetable_payload").commit()) }

    override fun readTermStart(): Long? =
        preferences.getLong("term_start_epoch_day", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

    override fun writeTermStart(epochDay: Long) {
        check(preferences.edit().putLong("term_start_epoch_day", epochDay).commit())
    }

    override fun removeTermStart() { check(preferences.edit().remove("term_start_epoch_day").commit()) }
}
