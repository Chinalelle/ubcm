package com.kang.ubcm

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * 待办规则单测：DDL 分级 / 排序 / 倒计时 / 子待办进度。
 */
class TodoRulesTest {

    private val now = 1_800_000_000_000L

    private fun todo(
        id: String,
        dueAt: Long? = null,
        done: Boolean = false,
        createdAt: Long = 0L,
        subs: List<SubTodo> = emptyList(),
    ) = TodoItem(id = id, title = "待办$id", dueAt = dueAt, done = done, createdAt = createdAt, subs = subs)

    @Test
    fun `DDL 分级边界`() {
        assertEquals(DueLevel.NONE, TodoRules.levelOf(null, now))
        assertEquals(DueLevel.URGENT, TodoRules.levelOf(now + 1 * TodoRules.HOUR_MS, now))
        assertEquals(DueLevel.URGENT, TodoRules.levelOf(now + TodoRules.DAY_MS, now))
        assertEquals(DueLevel.URGENT, TodoRules.levelOf(now - 1, now))   // 已过期
        assertEquals(DueLevel.NEAR, TodoRules.levelOf(now + TodoRules.DAY_MS + 1, now))
        assertEquals(DueLevel.NEAR, TodoRules.levelOf(now + 3 * TodoRules.DAY_MS, now))
        assertEquals(DueLevel.FAR, TodoRules.levelOf(now + 3 * TodoRules.DAY_MS + 1, now))
    }

    @Test
    fun `排序：24h内 → 无DDL → 其余 → 已完成`() {
        val items = listOf(
            todo("far", dueAt = now + 10 * TodoRules.DAY_MS),
            todo("none", createdAt = 2),
            todo("urgent2", dueAt = now + 2 * TodoRules.HOUR_MS),
            todo("urgent1", dueAt = now + 1 * TodoRules.HOUR_MS),
            todo("done", done = true),
            todo("near", dueAt = now + 2 * TodoRules.DAY_MS),
        )
        val order = TodoRules.sorted(items, now).map { it.id }
        assertEquals(listOf("urgent1", "urgent2", "none", "near", "far", "done"), order)
    }

    @Test
    fun `topOpen 过滤已完成并限三条`() {
        val items = (1..5).map { todo("t$it", dueAt = now + it * TodoRules.HOUR_MS) } +
            todo("done", done = true)
        val top = TodoRules.topOpen(items, now, limit = 3)
        assertEquals(3, top.size)
        assertEquals(listOf("t1", "t2", "t3"), top.map { it.id })
    }

    @Test
    fun `倒计时只在 24h 内返回`() {
        assertEquals("01:00:00", TodoRules.countdown(now + TodoRules.HOUR_MS, now))
        assertEquals("00:00:00", TodoRules.countdown(now - 1000, now))
        assertNull(TodoRules.countdown(now + 25 * TodoRules.HOUR_MS, now))
        assertNull(TodoRules.countdown(null, now))
    }

    @Test
    fun `子待办进度与全完成判定`() {
        val item = todo(
            "a",
            subs = listOf(
                SubTodo("1", "麻黄汤", true),
                SubTodo("2", "桂枝汤", false),
                SubTodo("3", "小青龙汤", false),
            ),
        )
        assertEquals(1 to 3, TodoRules.subProgress(item))
        assertFalse(TodoRules.allSubsDone(item))
        val allDone = item.copy(subs = item.subs.map { it.copy(done = true) })
        assertTrue(TodoRules.allSubsDone(allDone))
        assertFalse(TodoRules.allSubsDone(todo("empty")))
    }

    @Test
    fun `没设时分的 DDL 落到当天 23点59分59秒`() {
        val date = LocalDate.of(2026, 11, 24)
        val millis = TodoRules.dueAtOf(date, null)
        val back = Agenda.dateTimeOf(millis)
        assertEquals(LocalTime.of(23, 59, 59), back.toLocalTime())
        assertEquals(date, back.toLocalDate())

        val withTime = TodoRules.dueAtOf(date, LocalTime.of(18, 30))
        assertEquals(LocalTime.of(18, 30), Agenda.dateTimeOf(withTime).toLocalTime())
    }

    @Test
    fun `同日 DDL 文本格式`() {
        val millis = TodoRules.dueAtOf(LocalDate.of(2026, 11, 24), LocalTime.of(23, 50))
        assertEquals("11/24", TodoRules.dueDayText(millis))
        assertEquals("23:50", TodoRules.dueTimeText(millis))
        assertNull(TodoRules.dueDayText(null))
    }

    @Test
    fun `解析完整 DDL 文本`() {
        val now = LocalDate.of(2026, 11, 24)
        val ms = TodoRules.parseDdl("2026-11-25 20:30", now)
        assertEquals(LocalTime.of(20, 30), Agenda.dateTimeOf(ms!!).toLocalTime())
        assertEquals(LocalDate.of(2026, 11, 25), Agenda.dateTimeOf(ms).toLocalDate())
    }

    @Test
    fun `解析省略年份的 DDL 文本`() {
        val now = LocalDate.of(2026, 11, 24)
        val ms = TodoRules.parseDdl("12-01 09:05", now)
        assertEquals(LocalDate.of(2026, 12, 1), Agenda.dateTimeOf(ms!!).toLocalDate())
        assertEquals(LocalTime.of(9, 5), Agenda.dateTimeOf(ms).toLocalTime())
    }

    @Test
    fun `空 DDL 合法 非法格式不合法`() {
        val now = LocalDate.of(2026, 11, 24)
        assertTrue(TodoRules.isDdlTextValid("", now))
        assertTrue(TodoRules.isDdlTextValid("   ", now))
        assertTrue(TodoRules.isDdlTextValid("2026-11-24 20:00", now))
        assertTrue(TodoRules.isDdlTextValid("11-24 20:00", now))
        assertFalse(TodoRules.isDdlTextValid("明天下午", now))
        assertFalse(TodoRules.isDdlTextValid("2026-13-40 99:99", now))
        assertNull(TodoRules.parseDdl("", now))
        assertNull(TodoRules.parseDdl("昨天", now))
    }

    @Test
    fun `DDL 回填文本往返一致`() {
        val date = LocalDate.of(2026, 11, 24)
        val ms = TodoRules.wheelToMillis(date, 20, 0)
        assertEquals("2026-11-24 20:00", TodoRules.formatDdl(ms))
        assertEquals(ms, TodoRules.parseDdl(TodoRules.formatDdl(ms), date))
        assertEquals(20 to 0, TodoRules.wheelHourMinute(ms))
        assertEquals("", TodoRules.formatDdl(null))
    }

    @Test
    fun `滚轮时间组装与越界钳制`() {
        val date = LocalDate.of(2026, 11, 24)
        val ms = TodoRules.wheelToMillis(date, 25, 61)
        val dt = Agenda.dateTimeOf(ms)
        assertEquals(23, dt.hour)
        assertEquals(59, dt.minute)
    }

    @Test
    fun `已完成按完成时间倒序`() {
        val a = todo("a").copy(done = true, completedAt = 100)
        val b = todo("b").copy(done = true, completedAt = 300)
        val c = todo("c")
        val order = TodoRules.completedSorted(listOf(a, b, c)).map { it.id }
        assertEquals(listOf("b", "a"), order)
    }
}
