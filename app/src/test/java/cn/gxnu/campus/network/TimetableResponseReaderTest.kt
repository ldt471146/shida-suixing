package cn.gxnu.campus.network

import cn.gxnu.campus.core.WeekParity
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of the model's own json payload, exercised without any HTTP layer. */
class TimetableResponseReaderTest {

    private fun envelopeOf(content: String, finishReason: String = "stop"): String = JsonObject().apply {
        add("choices", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("finish_reason", finishReason)
                add("message", JsonObject().apply {
                    addProperty("role", "assistant")
                    addProperty("content", content)
                })
            })
        })
    }.toString()

    private fun failureOf(content: String, finishReason: String = "stop"): TimetableVisionException = try {
        TimetableResponseReader.read(envelopeOf(content, finishReason), TIME)
        throw AssertionError("expected the payload to be refused")
    } catch (failure: TimetableVisionException) {
        failure
    }

    @Test fun aWellFormedPayloadIsAccepted() {
        val timetable = TimetableResponseReader.read(envelopeOf(PAYLOAD), TIME)
        assertEquals(TIME, timetable.recognizedAtMillis)
        assertEquals(1, timetable.courseCount)
        assertEquals("高等数学-fixture", timetable.courses.single().name)
    }

    @Test fun aFencedAnswerIsStillAccepted() {
        val timetable = TimetableResponseReader.read(envelopeOf("```json\n$PAYLOAD\n```"), TIME)
        assertEquals(1, timetable.courseCount)
    }

    @Test fun alternateKeySpellingsAndTextFormsAreAccepted() {
        val payload = """
            {"is_timetable":"true","courses":[
              {"course_name":"课程-fixture","instructor":"教师丙","classroom":"综合楼101",
               "day":"周三","start_section":"第5-6节","week_start":"2","week_end":"16"}
            ]}
        """.trimIndent()
        val course = TimetableResponseReader.read(envelopeOf(payload), TIME).courses.single()
        assertEquals("课程-fixture", course.name)
        assertEquals("教师丙", course.teacher)
        assertEquals(3, course.weekday)
        assertEquals(5, course.startPeriod)
        assertEquals(6, course.endPeriod)
        assertEquals(2, course.startWeek)
    }

    @Test fun aTruthyFlagWithNoCourseMeansNothingWasRecognised() {
        val failure = failureOf("""{"is_timetable":true,"term":"","courses":[]}""")
        assertEquals(TimetableVisionFailure.NOT_A_TIMETABLE, failure.failure)
        assertTrue(failure.message!!.contains("没有识别到课表"))
    }

    @Test fun theDeclaredParityIsKeptForEachCourse() {
        val payload = """
            {"is_timetable":true,"term":"","courses":[
              {"name":"单周课-fixture","weekday":1,"start_period":1,"end_period":2,
               "start_week":1,"end_week":16,"parity":"odd"},
              {"name":"双周课-fixture","weekday":1,"start_period":1,"end_period":2,
               "start_week":1,"end_week":16,"parity":"even"}
            ]}
        """.trimIndent()
        // 单周 and 双周 subjects sharing one slot is normal on a real timetable, not a conflict.
        val timetable = TimetableResponseReader.read(envelopeOf(payload), TIME)
        assertEquals(2, timetable.courseCount)
        assertEquals(WeekParity.ODD, timetable.courses.first { it.name == "单周课-fixture" }.parity)
        assertEquals(WeekParity.EVEN, timetable.courses.first { it.name == "双周课-fixture" }.parity)
        assertTrue(timetable.courses.first { it.name == "单周课-fixture" }.runsInWeek(3))
        assertFalse(timetable.courses.first { it.name == "单周课-fixture" }.runsInWeek(4))
    }

    @Test fun parityPrintedInsideTheWeekRangeIsStillUnderstood() {
        val payload = """
            {"is_timetable":true,"term":"","courses":[
              {"name":"单周课-fixture","weekday":2,"start_period":3,"end_period":4,
               "start_week":"1-16周(单)","end_week":""}
            ]}
        """.trimIndent()
        val course = TimetableResponseReader.read(envelopeOf(payload), TIME).courses.single()
        assertEquals(WeekParity.ODD, course.parity)
        assertEquals(16, course.endWeek)
    }

    @Test fun aMissingFlagIsJudgedByThePresenceOfCourses() {
        assertEquals(1, TimetableResponseReader.read(envelopeOf(PAYLOAD_WITHOUT_FLAG), TIME).courseCount)
        assertEquals(TimetableVisionFailure.NOT_A_TIMETABLE, failureOf("""{"term":"","courses":[]}""").failure)
        assertEquals(TimetableVisionFailure.NOT_A_TIMETABLE, failureOf("""{"term":""}""").failure)
    }

    @Test fun aNonTimetableImageNeverYieldsCourses() {
        val failure = failureOf("""{"is_timetable":false,"term":"","courses":[],"reason":"这是一张风景照"}""")
        assertEquals(TimetableVisionFailure.NOT_A_TIMETABLE, failure.failure)
        assertTrue(failure.message!!.contains("没有识别到课表"))
        // The model's free text is not shown, so it cannot smuggle invented course data into the UI.
        assertTrue(!failure.message!!.contains("风景照"))
    }

    @Test fun anEmptyAnswerAsksForARetry() {
        assertEquals(TimetableVisionFailure.EMPTY_RESPONSE, failureOf("").failure)
        assertEquals(TimetableVisionFailure.EMPTY_RESPONSE, failureOf("   ").failure)
    }

    @Test fun aTruncatedAnswerIsRefused() {
        assertEquals(TimetableVisionFailure.MALFORMED_RESPONSE, failureOf(PAYLOAD, finishReason = "length").failure)
        assertEquals(TimetableVisionFailure.MALFORMED_RESPONSE, failureOf(PAYLOAD, finishReason = "content_filter").failure)
    }

    @Test fun proseInsteadOfJsonIsRefused() {
        assertEquals(TimetableVisionFailure.MALFORMED_RESPONSE, failureOf("我无法识别这张图片").failure)
    }

    @Test fun aPayloadThatIsNotAnObjectIsRefused() {
        assertEquals(TimetableVisionFailure.MALFORMED_RESPONSE, failureOf("""["周一","周二"]""").failure)
        // A course list of the wrong shape degrades to "nothing recognised" rather than a crash.
        assertEquals(TimetableVisionFailure.NOT_A_TIMETABLE, failureOf("""{"is_timetable":true,"courses":{}}""").failure)
        assertEquals(TimetableVisionFailure.NOT_A_TIMETABLE, failureOf("""{"is_timetable":true,"courses":[1,2,3]}""").failure)
    }

    @Test fun anEnvelopeWithoutChoicesIsRefused() {
        val broken = try {
            TimetableResponseReader.read("""{"choices":[]}""", TIME)
            throw AssertionError("expected an empty choices array to be refused")
        } catch (failure: TimetableVisionException) {
            failure
        }
        assertEquals(TimetableVisionFailure.MALFORMED_RESPONSE, broken.failure)
        val trailing = try {
            TimetableResponseReader.read("""{"choices":[]} {"choices":[]}""", TIME)
            throw AssertionError("expected trailing content to be refused")
        } catch (failure: TimetableVisionException) {
            failure
        }
        assertEquals(TimetableVisionFailure.MALFORMED_RESPONSE, trailing.failure)
    }

    private companion object {
        const val TIME = 1_700_000_000_000L
        val PAYLOAD = """
            {"is_timetable":true,"term":"2025-2026学年第一学期","courses":[
              {"name":"高等数学-fixture","teacher":"教师甲","room":"文理楼201",
               "weekday":1,"start_period":1,"end_period":2,"start_week":1,"end_week":16}
            ]}
        """.trimIndent()
        val PAYLOAD_WITHOUT_FLAG = """
            {"term":"2025-2026学年第一学期","courses":[
              {"name":"高等数学-fixture","teacher":"教师甲","room":"文理楼201",
               "weekday":1,"start_period":1,"end_period":2,"start_week":1,"end_week":16}
            ]}
        """.trimIndent()
    }
}
