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

    @Test fun bracketedEmptyCellsAreDroppedFromTeacherAndRoom() {
        val payload = """
            {"is_timetable":true,"courses":[
              {"name":"深度学习1班","teacher":"朱红艳","room":"[]","weekday":1,
               "start_period":1,"end_period":2,"start_week":2,"end_week":18},
              {"name":"矩阵理论1班","teacher":"（ ）","room":"[ ]","weekday":2,
               "start_period":3,"end_period":4,"start_week":2,"end_week":18},
              {"name":"马克思主义1班","teacher":"郭友兵","room":"无","weekday":3,
               "start_period":5,"end_period":6,"start_week":2,"end_week":18}
            ]}
        """.trimIndent()
        val byName = TimetableResponseReader.read(envelopeOf(payload), TIME).courses.associateBy { it.name }
        // The room column is blank on the sheet, so the model copies the empty cell's brackets out.
        assertEquals("", byName.getValue("深度学习1班").room)
        assertEquals("朱红艳", byName.getValue("深度学习1班").teacher)
        assertEquals("", byName.getValue("矩阵理论1班").teacher)
        assertEquals("", byName.getValue("矩阵理论1班").room)
        assertEquals("", byName.getValue("马克思主义1班").room)
        // Nothing is left to join, so the detail line must not become a stray separator.
        assertEquals("", byName.getValue("矩阵理论1班").detailLabel)
    }

    @Test fun aDashAndAWideSpaceAreDroppedAsWell() {
        val payload = """{"is_timetable":true,"courses":[
              {"name":"工程计算方法1班","teacher":"-","room":"　","weekday":1,
               "start_period":1,"end_period":2,"start_week":2,"end_week":18}]}"""
        val course = TimetableResponseReader.read(envelopeOf(payload), TIME).courses.single()
        assertEquals("", course.teacher)
        assertEquals("", course.room)
    }

    @Test fun aRoomThatMerelyContainsBracketsKeepsItsText() {
        val payload = """{"is_timetable":true,"courses":[
              {"name":"高等数学1班","teacher":"教师甲","room":"文理楼（东）301","weekday":1,
               "start_period":1,"end_period":2,"start_week":2,"end_week":18}]}"""
        val course = TimetableResponseReader.read(envelopeOf(payload), TIME).courses.single()
        assertEquals("文理楼（东）301", course.room)
        assertEquals("教师甲", course.teacher)
    }

    @Test fun numbersAreReadFromEitherJsonNumbersOrText() {
        val payload = """{"is_timetable":true,"courses":[
              {"name":"数字课-fixture","weekday":1,"start_period":"1","end_period":2,
               "start_week":"2","end_week":18}]}"""
        val course = TimetableResponseReader.read(envelopeOf(payload), TIME).courses.single()
        assertEquals(1, course.weekday)
        assertEquals(1, course.startPeriod)
        assertEquals(2, course.endPeriod)
        assertEquals(2, course.startWeek)
        assertEquals(18, course.endWeek)
    }

    @Test fun weekdayWordsAreMappedOntoTheOneToSevenContract() {
        val payload = """{"is_timetable":true,"courses":[
              {"name":"周一课-fixture","weekday":"星期一","start_period":1,"end_period":2,"start_week":1,"end_week":2},
              {"name":"周三课-fixture","weekday":"Wednesday","start_period":1,"end_period":2,"start_week":1,"end_week":2},
              {"name":"周五课-fixture","weekday":"周五","start_period":5,"end_period":6,"start_week":1,"end_week":2},
              {"name":"周六课-fixture","weekday":"Saturday","start_period":7,"end_period":8,"start_week":1,"end_week":2},
              {"name":"周日课-fixture","weekday":"星期日","start_period":3,"end_period":4,"start_week":1,"end_week":2}
            ]}"""
        val byName = TimetableResponseReader.read(envelopeOf(payload), TIME).courses.associateBy { it.name }
        assertEquals(1, byName.getValue("周一课-fixture").weekday)
        assertEquals(3, byName.getValue("周三课-fixture").weekday)
        assertEquals(5, byName.getValue("周五课-fixture").weekday)
        assertEquals(6, byName.getValue("周六课-fixture").weekday)
        assertEquals(7, byName.getValue("周日课-fixture").weekday)
    }

    @Test fun aWeekRangeWrittenIntoOneFieldIsSplitIntoItsEnds() {
        val payload = """{"is_timetable":true,"courses":[
              {"name":"区间课-fixture","weekday":1,"start_period":1,"end_period":2,"start_week":"2-18周"},
              {"name":"前缀区间课-fixture","weekday":2,"start_period":1,"end_period":2,"start_week":"第3-11周"},
              {"name":"分开写的课-fixture","weekday":3,"start_period":1,"end_period":2,"start_week":"4","end_week":"9"}]}"""
        val byName = TimetableResponseReader.read(envelopeOf(payload), TIME).courses.associateBy { it.name }
        assertEquals(2, byName.getValue("区间课-fixture").startWeek)
        assertEquals(18, byName.getValue("区间课-fixture").endWeek)
        assertEquals(3, byName.getValue("前缀区间课-fixture").startWeek)
        assertEquals(11, byName.getValue("前缀区间课-fixture").endWeek)
        // A properly separated pair is never second-guessed.
        assertEquals(4, byName.getValue("分开写的课-fixture").startWeek)
        assertEquals(9, byName.getValue("分开写的课-fixture").endWeek)
    }

    @Test fun aRangeInsideASinglePeriodFieldIsSplitToo() {
        val payload = """{"is_timetable":true,"courses":[
              {"name":"连堂课-fixture","weekday":1,"start_period":"第5-6节",
               "start_week":2,"end_week":18}]}"""
        val course = TimetableResponseReader.read(envelopeOf(payload), TIME).courses.single()
        assertEquals(5, course.startPeriod)
        assertEquals(6, course.endPeriod)
    }

    @Test fun aLonePeriodEndpointIsMirroredRatherThanDiscarded() {
        val payload = """{"is_timetable":true,"courses":[
              {"name":"只给一个节次-fixture","weekday":1,"end_period":13,
               "start_week":2,"end_week":18}]}"""
        val course = TimetableResponseReader.read(envelopeOf(payload), TIME).courses.single()
        assertEquals(13, course.startPeriod)
        assertEquals(13, course.endPeriod)
    }

    @Test fun aBlankFieldFallsThroughToItsAlternateKeyInsteadOfMaskingIt() {
        val payload = """{"is_timetable":true,"courses":[
              {"name":"","course_name":"高数-fixture","teacher":"教师甲","weekday":1,
               "start_period":1,"end_period":2,"start_week":"2","end_week":"","week_end":"16"}]}"""
        val course = TimetableResponseReader.read(envelopeOf(payload), TIME).courses.single()
        // An empty string is the model saying nothing, so it must not hide the spelling it did send.
        assertEquals("高数-fixture", course.name)
        assertEquals(2, course.startWeek)
        assertEquals(16, course.endWeek)
    }

    @Test fun aRangeWrittenIntoTheEndFieldStillSuppliesBothEnds() {
        val payload = """{"is_timetable":true,"courses":[
              {"name":"端点课-fixture","weekday":1,"start_period":"3","end_period":"第3-4节",
               "start_week":2,"end_week":18}]}"""
        val course = TimetableResponseReader.read(envelopeOf(payload), TIME).courses.single()
        assertEquals(3, course.startPeriod)
        assertEquals(4, course.endPeriod)
    }

    @Test fun parityWordsAreUnderstood() {
        val payload = """{"is_timetable":true,"courses":[
              {"name":"每周课-fixture","weekday":1,"start_period":1,"end_period":2,"start_week":1,"end_week":16,"parity":"每周"},
              {"name":"单周课-fixture","weekday":2,"start_period":1,"end_period":2,"start_week":1,"end_week":16,"parity":"单周"},
              {"name":"双周课-fixture","weekday":3,"start_period":1,"end_period":2,"start_week":1,"end_week":16,"parity":"双周"},
              {"name":"全周课-fixture","weekday":4,"start_period":1,"end_period":2,"start_week":1,"end_week":16,"parity":"全周"},
              {"name":"每周都上课-fixture","weekday":5,"start_period":1,"end_period":2,"start_week":1,"end_week":16,"parity":"每周都上"}]}"""
        val byName = TimetableResponseReader.read(envelopeOf(payload), TIME).courses.associateBy { it.name }
        assertEquals(WeekParity.ALL, byName.getValue("每周课-fixture").parity)
        assertEquals(WeekParity.ODD, byName.getValue("单周课-fixture").parity)
        assertEquals(WeekParity.EVEN, byName.getValue("双周课-fixture").parity)
        assertEquals(WeekParity.ALL, byName.getValue("全周课-fixture").parity)
        assertEquals(WeekParity.ALL, byName.getValue("每周都上课-fixture").parity)
    }

    @Test fun aCourseWithoutPeriodsKeepsItsZeroesInsteadOfLosingThem() {
        val payload = """{"is_timetable":true,"courses":[
              {"name":"无节次课-fixture","teacher":"教师丙","room":"","weekday":1,
               "start_period":0,"end_period":0,"start_week":2,"end_week":18,"parity":"all"}]}"""
        val course = TimetableResponseReader.read(envelopeOf(payload), TIME).courses.single()
        // 0 is 无节次, an answer the timetable really gives — never a missing value.
        assertEquals(0, course.startPeriod)
        assertEquals(0, course.endPeriod)
        assertEquals("无节次", course.periodLabel)
    }

    @Test fun theRealCapturedResponseStillYieldsEveryCourse() {
        val timetable = TimetableResponseReader.read(envelopeOf(REAL_CAPTURED_RESPONSE), TIME)
        assertEquals("2026-2027秋季学期", timetable.term)
        assertEquals(8, timetable.courseCount)
        // 无节次: periods 0-0 with a real week range, printed above 上午1 on the sheet.
        val withoutPeriods = timetable.courses.single { it.teacher == "朱红艳" }
        assertEquals("深度学习1班", withoutPeriods.name)
        assertEquals(1, withoutPeriods.weekday)
        assertEquals(0, withoutPeriods.startPeriod)
        assertEquals(0, withoutPeriods.endPeriod)
        assertEquals(2, withoutPeriods.startWeek)
        assertEquals(18, withoutPeriods.endWeek)
        assertEquals(WeekParity.ALL, withoutPeriods.parity)
        assertEquals("", withoutPeriods.room)
        val secondDeepLearning = timetable.courses.single { it.teacher == "夏海英" }
        assertEquals("深度学习1班", secondDeepLearning.name)
        assertEquals(5, secondDeepLearning.weekday)
        assertEquals(10, secondDeepLearning.startPeriod)
        assertEquals(13, secondDeepLearning.endPeriod)
    }

    private companion object {
        const val TIME = 1_700_000_000_000L

        /**
         * The model's payload over a real timetable photo, captured and kept byte for byte so that a
         * prompt or schema drift shows up here as a failing test rather than on a user's screen.
         */
        val REAL_CAPTURED_RESPONSE = """{
  "is_timetable": true,
  "term": "2026-2027秋季学期",
  "courses": [
    {
      "name": "深度学习1班",
      "teacher": "朱红艳",
      "room": "",
      "weekday": 1,
      "start_period": 0,
      "end_period": 0,
      "start_week": 2,
      "end_week": 18,
      "parity": "all"
    },
    {
      "name": "中国式现代化的理论与实践（电子1班）",
      "teacher": "马希",
      "room": "",
      "weekday": 2,
      "start_period": 3,
      "end_period": 5,
      "start_week": 3,
      "end_week": 11,
      "parity": "all"
    },
    {
      "name": "非线性系统与混沌1班",
      "teacher": "韦笃取",
      "room": "",
      "weekday": 1,
      "start_period": 6,
      "end_period": 7,
      "start_week": 2,
      "end_week": 17,
      "parity": "all"
    },
    {
      "name": "矩阵理论1班",
      "teacher": "邬艳丽",
      "room": "",
      "weekday": 2,
      "start_period": 6,
      "end_period": 7,
      "start_week": 2,
      "end_week": 18,
      "parity": "all"
    },
    {
      "name": "马克思主义与当代科技（电子1班）",
      "teacher": "郭友兵",
      "room": "",
      "weekday": 3,
      "start_period": 6,
      "end_period": 7,
      "start_week": 12,
      "end_week": 17,
      "parity": "all"
    },
    {
      "name": "工程计算方法随机过程1班",
      "teacher": "李自立",
      "room": "",
      "weekday": 4,
      "start_period": 6,
      "end_period": 9,
      "start_week": 2,
      "end_week": 10,
      "parity": "all"
    },
    {
      "name": "工程计算方法随机过程1班",
      "teacher": "曾上游",
      "room": "",
      "weekday": 3,
      "start_period": 10,
      "end_period": 13,
      "start_week": 11,
      "end_week": 18,
      "parity": "all"
    },
    {
      "name": "深度学习1班",
      "teacher": "夏海英",
      "room": "",
      "weekday": 5,
      "start_period": 10,
      "end_period": 13,
      "start_week": 3,
      "end_week": 17,
      "parity": "all"
    }
  ]
}"""
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
