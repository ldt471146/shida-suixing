package cn.gxnu.campus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 用户报的故障：「输入上课地点虽然可以显示，但是我关闭软件再打开就没有了」。
 *
 * 地点并没有存坏 —— `TimetableStore` 的 `room` 写读是对称的。真正的原因是**重新登录/刷新会按
 * 服务器返回把每一门课重建一遍**，而这个账号的 `skdd`（上课地点）是空的，于是用户手填的地点被
 * 空字符串覆盖。这一层在取回链路上，不在保存链路上，所以只测编辑保存是发现不了的。
 */
class CampusTimetableMapperTest {

    private val recognizedAt = 1_700_000_000_000L

    private fun course(
        name: String = "矩阵理论1班",
        teacher: String = "邹艳丽",
        room: String = "",
        weekday: Int = 2,
        startPeriod: Int = 6,
        endPeriod: Int = 9,
        weeks: String = "2-18周"
    ) = CampusTimetableApi.CampusCourse(
        name = name, teacher = teacher, room = room, weekday = weekday,
        startPeriod = startPeriod, endPeriod = endPeriod, weeks = weeks,
        startClock = "14:00", endClock = "17:00"
    )

    @Test fun aLocallyTypedRoomSurvivesARefetchThatCarriesNoRoom() {
        // 第一趟：服务器没给地点，用户自己补了一个。
        val first = CampusTimetableMapper.build(listOf(course()), term = "", recognizedAtMillis = recognizedAt)
        val edited = TimetableValidator.build(
            first.term,
            listOf(
                TimetableCourseDraft(
                    name = "矩阵理论1班", teacher = "邹艳丽", room = "理科楼 305",
                    weekday = "2", startPeriod = "6", endPeriod = "9",
                    startWeek = "2", endWeek = "18", parity = WeekParity.ALL.name
                )
            ),
            first.recognizedAtMillis,
            first.periodTimes
        )

        // 第二趟：重新登录，服务器照旧不给地点 —— 用户填的那个必须还在。
        val second = CampusTimetableMapper.build(
            listOf(course()), term = "", recognizedAtMillis = recognizedAt, previous = edited
        )

        assertEquals("理科楼 305", second.courses.single().room)
    }

    @Test fun theServersOwnRoomStillWinsWhenItHasOne() {
        val previous = CampusTimetableMapper.build(listOf(course()), term = "", recognizedAtMillis = recognizedAt)
        val serverHasRoom = listOf(course(room = "文科楼 101"))

        val rebuilt = CampusTimetableMapper.build(
            serverHasRoom, term = "", recognizedAtMillis = recognizedAt, previous = previous
        )

        assertEquals("文科楼 101", rebuilt.courses.single().room)
    }

    @Test fun aRoomIsNotBorrowedByADifferentCourse() {
        val previous = CampusTimetableMapper.build(listOf(course()), term = "", recognizedAtMillis = recognizedAt)
        // A different course on the same weekday and periods：地点属于那一门，不能顺手挪过来。
        val other = listOf(course(name = "深度学习1班", teacher = "夏海英"))

        val rebuilt = CampusTimetableMapper.build(
            other, term = "", recognizedAtMillis = recognizedAt, previous = previous
        )

        assertEquals("", rebuilt.courses.single().room)
        assertNotEquals(previous.courses.single().name, rebuilt.courses.single().name)
    }

    @Test fun noPreviousTimetableSimplyMeansNoRoom() {
        val built = CampusTimetableMapper.build(listOf(course()), term = "", recognizedAtMillis = recognizedAt)
        assertEquals("", built.courses.single().room)
    }
}
