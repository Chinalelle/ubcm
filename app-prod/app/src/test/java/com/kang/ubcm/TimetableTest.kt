package com.kang.ubcm

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 「下一节课」算错一次，用户就会不再信任首页。所以这里用**真实课表**当夹具断言。
 *
 * 参考事实（2026–2027 学年第 1 学期，第 1 周 = 2026-09-07）：
 *  - 2026-11-20 是周五，第 11 周
 *  - 当天两门课：中医基础学 1–2 节 08:30–10:00（良东317教，4–13 周）
 *               古汉语基础   3–4 节 10:30–12:00（良东166教，8–11 周）
 *  - 同一天第 3–4 节还有「大学生心理健康教育」但只在 4–7 周，第 11 周不该出现（周次过滤的关键用例）
 */
class TimetableTest {

    private val first = LocalDate.parse(TestTimetable.FIRST_WEEK_MONDAY)

    private fun next(now: String) =
        Timetable.nextCourse(TestTimetable.SESSIONS, TestTimetable.SLOTS, LocalDateTime.parse(now), first)
            ?: fail("$now 应该有下一节课").let { null }

    // ---------- 周次 ----------

    @Test
    fun `首周周一就是第1周`() {
        assertEquals(1, Timetable.weekOf(LocalDate.parse("2026-09-07"), first))
    }

    @Test
    fun `首周周日仍是第1周`() {
        assertEquals(1, Timetable.weekOf(LocalDate.parse("2026-09-13"), first))
    }

    @Test
    fun `次周周一进入第2周`() {
        assertEquals(2, Timetable.weekOf(LocalDate.parse("2026-09-14"), first))
    }

    @Test
    fun `2026年11月20日是第11周周五`() {
        val d = LocalDate.parse("2026-11-20")
        assertEquals(11, Timetable.weekOf(d, first))
        assertEquals(5, d.dayOfWeek.value)
    }

    @Test
    fun `开学前周次为0或负`() {
        assertTrue(Timetable.weekOf(LocalDate.parse("2026-09-01"), first) < 1)
    }

    @Test
    fun `周次与周一的日期互为逆运算`() {
        for (w in 1..18) {
            assertEquals(w, Timetable.weekOf(Timetable.mondayOf(w, first), first))
        }
    }

    // ---------- 节次时间推算 ----------

    @Test
    fun `教务大节表能推算出12个小节且与官方作息一致`() {
        // 教务 lstJx0415zb 的真实形态（大节 + jc1/jc2）
        val periods = listOf(
            JwApi.Period(1, "08:30", "10:00", 1, 2),
            JwApi.Period(2, "10:30", "12:00", 3, 4),
            JwApi.Period(3, "13:30", "15:00", 5, 6),
            JwApi.Period(4, "15:30", "17:00", 7, 8),
            JwApi.Period(5, "18:00", "19:30", 9, 10),
            JwApi.Period(6, "19:40", "20:25", 11, 11),
            JwApi.Period(7, "20:25", "21:10", 12, 12),
        )
        val slots = Timetable.slotsFromPeriods(periods)
        assertEquals(12, slots.size)
        assertEquals(TestTimetable.SLOTS, slots)   // 推算结果必须与官方导出作息逐条一致
    }

    @Test
    fun `大节表为空时调用方应能用兜底表`() {
        assertEquals(12, Timetable.FALLBACK_SLOTS.size)
        assertEquals(8 * 60 + 30, Timetable.FALLBACK_SLOTS.first().startMin)
    }

    @Test
    fun `时间解析容忍多种写法`() {
        assertEquals(510, Timetable.localTimeToMinutes("08:30"))
        assertEquals(510, Timetable.localTimeToMinutes("8:30"))
        assertEquals(510, Timetable.localTimeToMinutes("08:30:00"))
        assertNull(Timetable.localTimeToMinutes(""))
        assertNull(Timetable.localTimeToMinutes(null))
        assertNull(Timetable.localTimeToMinutes("abc"))
    }

    // ---------- 某天的课 / 周次过滤 ----------

    @Test
    fun `第11周周五只有两门课且心理健康教育被周次过滤掉`() {
        val date = LocalDate.parse("2026-11-20")
        val list = Timetable.sessionsOn(TestTimetable.SESSIONS, date, 11)
        assertEquals(2, list.size)   // 第 11 周周五应只有中医基础学与古汉语基础
        val names = list.map { it.name }
        assertEquals(listOf("中医基础学", "古汉语基础"), names)
        assertTrue(list.none { it.name.contains("心理健康") })   // 心理健康教育只在 4-7 周
    }

    @Test
    fun `第6周周五心理健康教育会出现`() {
        // 第 6 周周一 = 2026-09-07 + 35 = 2026-10-12，周五 = 2026-10-16
        val date = LocalDate.parse("2026-10-16")
        val names = Timetable.sessionsOn(TestTimetable.SESSIONS, date, 6).map { it.name }
        assertTrue(names.any { it.contains("心理健康") })       // 第 6 周应该有心理健康教育
        assertTrue(names.none { it == "古汉语基础" })      // 古汉语基础周五场次只在 8-11 周
    }

    // ---------- 下一节课 ----------

    @Test
    fun `周五早上8点下一节课是中医基础学且还有30分钟`() {
        val now = LocalDateTime.parse("2026-11-20T08:00")
        val n = next("2026-11-20T08:00")!!
        assertEquals(CoursePhase.UPCOMING, n.phase)
        assertEquals("中医基础学", n.time.session.name)
        assertEquals("良东317教", n.time.session.location)
        assertEquals(30L, n.time.minutesUntilStart(now))
    }

    @Test
    fun `周五11点正在上古汉语基础`() {
        val now = LocalDateTime.parse("2026-11-20T11:00")
        val n = next("2026-11-20T11:00")!!
        assertEquals(CoursePhase.ONGOING, n.phase)
        assertEquals("古汉语基础", n.time.session.name)
        assertEquals("良东166教", n.time.session.location)
        assertEquals(60L, n.time.minutesUntilEnd(now))   // 11:00 距 12:00 下课还有 60 分钟
    }

    @Test
    fun `周五23点36分下一节课跳到下周一早八`() {
        val n = next("2026-11-20T23:36")!!
        assertEquals(CoursePhase.UPCOMING, n.phase)
        assertEquals("中医基础学", n.time.session.name)
        assertEquals("良东408教", n.time.session.location)
        assertEquals(LocalDate.parse("2026-11-23"), n.time.date)   // 应落到下周一
        assertEquals("08:30", n.time.start.toLocalTime().toString().take(5))
    }

    @Test
    fun `课间十分钟也能给出下一节`() {
        val now = LocalDateTime.parse("2026-11-20T10:00")   // 中医基础学刚下课，10:30 古汉语基础上课
        val n = next("2026-11-20T10:00")!!
        assertEquals("古汉语基础", n.time.session.name)
        assertEquals(30L, n.time.minutesUntilStart(now))
    }

    @Test
    fun `第14周周五是同教室的单节课`() {
        // 第 14 周周一 = 2026-12-07，周五 = 2026-12-11
        val n = next("2026-12-11T07:00")!!
        assertEquals("中医基础学", n.time.session.name)
        assertEquals("良东316教", n.time.session.location)
        assertEquals(1, n.time.session.startPeriod)
        assertEquals(1, n.time.session.endPeriod)   // 第 14 周只有第 1 节
    }

    @Test
    fun `学期结束后返回null`() {
        // 第 20 周周一 = 2027-01-18
        assertNull(Timetable.nextCourse(
            TestTimetable.SESSIONS, TestTimetable.SLOTS, LocalDateTime.parse("2027-01-22T09:00"), first))
    }

    @Test
    fun `空课表不崩`() {
        assertNull(Timetable.nextCourse(
            emptyList(), TestTimetable.SLOTS, LocalDateTime.parse("2026-11-20T09:00"), first))
    }

    // ---------- 今日课程列表 ----------

    @Test
    fun `周五11点今天的课一节结束一节在场`() {
        val now = LocalDateTime.parse("2026-11-20T11:00")
        val today = Timetable.todayCourses(TestTimetable.SESSIONS, TestTimetable.SLOTS, now, first)
        assertEquals(2, today.size)
        assertEquals(CoursePhase.ENDED, today[0].phase)     // 中医基础学 08:30-10:00 已结束
        assertEquals(CoursePhase.ONGOING, today[1].phase)   // 古汉语基础正在上
    }

    @Test
    fun `周末没有课`() {
        val now = LocalDateTime.parse("2026-11-21T10:00")   // 周六，第 11 周
        assertTrue(Timetable.todayCourses(TestTimetable.SESSIONS, TestTimetable.SLOTS, now, first).isEmpty())
    }
}
