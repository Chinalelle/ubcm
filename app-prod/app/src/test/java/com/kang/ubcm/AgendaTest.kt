package com.kang.ubcm

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * 日程派生 / 三态 / 进度 / 倒计时的纯逻辑单测。
 *
 * 用固定日期，避免"今天恰好是周几"影响结果：
 * 2026-11-23 是周一，2026-11-24 是周二。
 */
class AgendaTest {

    private val firstMonday = LocalDate.of(2026, 11, 23)
    private val slots = Timetable.FALLBACK_SLOTS

    /** 周二的课：第 1–2 节（08:30–10:00） */
    private fun tuesdayCourse() = CourseSession(
        name = "中医基础学",
        teacher = "李老师",
        location = "教学楼 305",
        dayOfWeek = 2,
        startPeriod = 1,
        endPeriod = 2,
        weeks = (1..20).toList(),
    )

    private fun wednesdayCourse() = CourseSession(
        name = "正常人体学",
        teacher = "王老师",
        location = "解剖实验室1",
        dayOfWeek = 3,
        startPeriod = 1,
        endPeriod = 2,
        weeks = (1..20).toList(),
    )

    private fun at(hour: Int, minute: Int = 0, day: Int = 24) =
        LocalDateTime.of(2026, 11, day, hour, minute)

    @Test
    fun `课表派生只包含当前周次的课`() {
        val now = at(7)
        val items = Agenda.fromTimetable(listOf(tuesdayCourse()), slots, now, firstMonday)
        assertEquals(1, items.size)
        assertEquals("中医基础学", items[0].title)
        assertEquals(AgendaSource.TIMETABLE, items[0].source)
    }

    @Test
    fun `正在进行的那条是 ONGOING`() {
        val now = at(9)
        val cards = Agenda.nearest(
            Agenda.fromTimetable(listOf(tuesdayCourse()), slots, now, firstMonday),
            Agenda.millisOf(now),
        )
        assertEquals(1, cards.size)
        assertEquals(AgendaPhase.ONGOING, cards[0].phase)
    }

    @Test
    fun `未开始且最近的一条是 UPCOMING`() {
        val now = at(7)
        val cards = Agenda.nearest(
            Agenda.fromTimetable(listOf(tuesdayCourse()), slots, now, firstMonday),
            Agenda.millisOf(now),
        )
        assertEquals(1, cards.size)
        assertEquals(AgendaPhase.UPCOMING, cards[0].phase)
    }

    @Test
    fun `已结束的课不显示`() {
        val now = at(11)
        val cards = Agenda.nearest(
            Agenda.fromTimetable(listOf(tuesdayCourse()), slots, now, firstMonday),
            Agenda.millisOf(now),
        )
        assertTrue(cards.isEmpty())
    }

    @Test
    fun `今天课上完后明天的课成为 UPCOMING`() {
        val now = at(23)
        val items = Agenda.fromTimetable(
            listOf(tuesdayCourse(), wednesdayCourse()), slots, now, firstMonday,
        )
        val cards = Agenda.nearest(items, Agenda.millisOf(now))
        assertEquals(1, cards.size)
        assertEquals("正常人体学", cards[0].item.title)
        assertEquals(AgendaPhase.UPCOMING, cards[0].phase)
    }

    @Test
    fun `最多只取三条`() {
        val now = at(7)
        val items = Agenda.fromTimetable(
            listOf(tuesdayCourse(), wednesdayCourse()), slots, now, firstMonday, horizonDays = 7,
        )
        val cards = Agenda.nearest(items, Agenda.millisOf(now), limit = 1)
        assertEquals(1, cards.size)
    }

    @Test
    fun `进度百分比向下取整`() {
        val item = Agenda.fromTimetable(listOf(tuesdayCourse()), slots, at(7), firstMonday).first()
        // 08:30–10:00 共 90 分钟；09:15 正好一半
        assertEquals(50, Agenda.progressPercent(item, Agenda.millisOf(at(9, 15))))
        assertEquals(0, Agenda.progressPercent(item, Agenda.millisOf(at(8))))
        assertEquals(100, Agenda.progressPercent(item, Agenda.millisOf(at(12))))
    }

    @Test
    fun `无结束时间的自建日程不显示进度`() {
        val item = AgendaItem(
            id = "u1", title = "复习", startMillis = Agenda.millisOf(at(9)),
            endMillis = null, location = null, note = null, source = AgendaSource.USER,
        )
        assertEquals(0, Agenda.progressPercent(item, Agenda.millisOf(at(10))))
        assertTrue(Agenda.isOngoing(item, Agenda.millisOf(at(10))))
    }

    @Test
    fun `倒计时补零且不为负`() {
        val start = Agenda.millisOf(at(8, 30))
        assertEquals("00:45:00", Agenda.countdown(start + 45 * 60 * 1000L, start))
        assertEquals("00:00:00", Agenda.countdown(start - 10, start))
        assertEquals("02:03:04", Agenda.countdown(start + (2 * 3600 + 3 * 60 + 4) * 1000L, start))
    }

    @Test
    fun `自建与课表合并后按开始时间排序`() {
        val now = at(7)
        val tt = Agenda.fromTimetable(listOf(tuesdayCourse()), slots, now, firstMonday)
        val user = listOf(
            AgendaItem(
                id = "u1", title = "评教说明会",
                startMillis = Agenda.millisOf(at(10, 30)),
                endMillis = null, location = "行政楼 201", note = null,
                source = AgendaSource.USER,
            )
        )
        val merged = Agenda.merge(tt, user)
        assertEquals(2, merged.size)
        assertEquals("中医基础学", merged[0].title)
        assertEquals("评教说明会", merged[1].title)
    }

    @Test
    fun `已经进行中的自建项也算 ONGOING`() {
        val now = Agenda.millisOf(at(10))
        val user = AgendaItem(
            id = "u1", title = "评教说明会",
            startMillis = Agenda.millisOf(at(9, 45)),
            endMillis = Agenda.millisOf(at(11)),
            location = "行政楼 201", note = null, source = AgendaSource.USER,
        )
        val cards = Agenda.nearest(listOf(user), now)
        assertEquals(1, cards.size)
        assertEquals(AgendaPhase.ONGOING, cards[0].phase)
        assertNotNull(cards[0].item.location)
    }
}
