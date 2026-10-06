package cn.gxnu.campus.data

import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableCourse
import cn.gxnu.campus.core.WeekParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fictional fixtures only; nothing here touches SharedPreferences. */
class TimetableStoreTest {

    private class MemoryStorage : TimetableStorage {
        var value: String? = null
        var failure: Exception? = null
        override fun read(): String? = failure?.let { throw it } ?: value
        override fun write(value: String) { failure?.let { throw it }; this.value = value }
        override fun remove() { failure?.let { throw it }; value = null }
    }

    private fun course(
        name: String = "高等数学-fixture",
        weekday: Int = 1,
        parity: WeekParity = WeekParity.ALL
    ) = TimetableCourse(name, "教师甲", "文理楼201", weekday, 1, 2, 1, 16, parity)

    private fun timetable(vararg courses: TimetableCourse) =
        Timetable("2025-2026学年第一学期", courses.toList(), 1_700_000_000_000L)

    @Test fun aSavedTimetableSurvivesARestart() {
        val storage = MemoryStorage()
        val saved = timetable(course())
        TimetableStore(storage).save(saved)
        assertEquals(saved, TimetableStore(storage).load())
    }

    @Test fun oddWeekParitySurvivesTheRoundTrip() {
        val storage = MemoryStorage()
        TimetableStore(storage).save(timetable(course(name = "课程A-fixture", parity = WeekParity.ODD)))
        assertEquals(WeekParity.ODD, TimetableStore(storage).load()!!.courses.single().parity)
    }

    @Test fun savingAgainReplacesTheEarlierTimetable() {
        val storage = MemoryStorage()
        val store = TimetableStore(storage)
        store.save(timetable(course(name = "旧课表-fixture")))
        val replacement = timetable(course(name = "新课表-fixture"), course(name = "第二门-fixture", weekday = 3))
        store.save(replacement)
        val loaded = store.load()!!
        assertEquals(2, loaded.courseCount)
        assertTrue(loaded.courses.none { it.name == "旧课表-fixture" })
        assertTrue(storage.value!!.contains("新课表-fixture"))
        assertFalse(storage.value!!.contains("旧课表-fixture"))
    }

    @Test fun deletingRemovesTheStoredTimetable() {
        val storage = MemoryStorage()
        val store = TimetableStore(storage)
        store.save(timetable(course()))
        store.clear()
        assertNull(storage.value)
        assertNull(store.load())
    }

    @Test fun anEmptyStoreReadsAsNoTimetable() {
        assertNull(TimetableStore(MemoryStorage()).load())
    }

    @Test fun aCorruptedPayloadReadsAsNoTimetable() {
        for (payload in listOf(
            "",
            "not json at all",
            """{"version":1,"courses":""",
            """{"version":2,"term":"","recognized_at":0,"courses":[]}""",
            """{"version":1,"term":"2025","recognized_at":0,"courses":[{"name":"课程-fixture","weekday":99,"start_period":1,"end_period":2,"start_week":1,"end_week":16}]}"""
        )) {
            val storage = MemoryStorage().apply { value = payload }
            assertNull("payload should be refused: $payload", TimetableStore(storage).load())
        }
    }

    @Test fun aFailedWriteIsReportedToTheCaller() {
        val store = TimetableStore(MemoryStorage().apply { failure = IllegalStateException("fixture disk full") })
        val failure = try {
            store.save(timetable(course()))
            throw AssertionError("expected the write to be reported")
        } catch (failure: TimetableStorageException) {
            failure
        }
        assertFalse(failure.message.isNullOrBlank())
    }

    @Test fun aFailedReadDoesNotThrowAtTheCaller() {
        assertNull(TimetableStore(MemoryStorage().apply { failure = IllegalStateException("fixture unreadable") }).load())
    }
}
