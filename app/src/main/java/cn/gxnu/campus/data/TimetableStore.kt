package cn.gxnu.campus.data

import android.content.Context
import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableCourseDraft
import cn.gxnu.campus.core.TimetableException
import cn.gxnu.campus.core.TimetableValidator
import cn.gxnu.campus.core.WeekParity
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
}

/** The timetable is not a secret, so unlike [VisionApiKeyStore] it needs no cipher. */
class TimetableStore internal constructor(private val storage: TimetableStorage) {
    constructor(context: Context) : this(AndroidTimetableStorage(context))

    /** A missing, unreadable or no longer valid payload all mean "no timetable", never a crash. */
    fun load(): Timetable? = try { storage.read()?.let(TimetableJson::decode) } catch (_: Exception) { null }

    fun save(timetable: Timetable) = safely { storage.write(TimetableJson.encode(timetable)) }

    fun clear() = safely { storage.remove() }

    private inline fun <T> safely(block: () -> T): T = try { block() }
    catch (_: Exception) { throw TimetableStorageException() }
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
        // Parity is stored beside the draft because the model contract does not carry it.
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
                endWeek = text(course, "end_week")
            ) to parityOf(course)
        }
        return try {
            TimetableValidator.build(text(root, "term"), entries.map { it.first }, recognizedAt(root))
                .let { timetable ->
                    // The validator keeps one course per draft, in order, so the pair stays aligned.
                    timetable.copy(courses = timetable.courses.mapIndexed { index, course ->
                        course.copy(parity = entries[index].second)
                    })
                }
        } catch (_: TimetableException) { null }
    }

    private fun text(value: JsonObject, key: String): String? =
        value.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun recognizedAt(root: JsonObject): Long =
        root.get("recognized_at")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

    private fun parityOf(course: JsonObject): WeekParity {
        val name = text(course, "parity") ?: return WeekParity.ALL
        return WeekParity.entries.firstOrNull { it.name == name } ?: WeekParity.ALL
    }
}

private class AndroidTimetableStorage(context: Context) : TimetableStorage {
    private val applicationContext = context.applicationContext
    private val preferences by lazy { applicationContext.getSharedPreferences("campus_timetable", Context.MODE_PRIVATE) }
    override fun read(): String? = preferences.getString("timetable_payload", null)
    override fun write(value: String) {
        check(preferences.edit().putString("timetable_payload", value).commit())
    }
    override fun remove() { check(preferences.edit().remove("timetable_payload").commit()) }
}
