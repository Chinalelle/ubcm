package com.kang.ubcm

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 待办的纯规则（docs/21 §6）：DDL 分级、排序、子待办进度、倒计时格式。
 *
 * 界面层**不许自己排序**——一律走 [TodoRules.sorted]，这样首页和 P7 的完整页永远同一个顺序。
 * 持久化在 [TodoStore]；这里不碰 Android / org.json，可 JVM 单测。
 */
enum class DueLevel {
    /** 无 DDL */
    NONE,

    /** > 3 天 */
    FAR,

    /** 3 天内 */
    NEAR,

    /** ≤ 24 小时（含已过期） */
    URGENT,
}

data class SubTodo(val id: String, val text: String, val done: Boolean)

data class TodoItem(
    val id: String,
    val title: String,
    val note: String? = null,
    /** null = 不设 DDL */
    val dueAt: Long? = null,
    val done: Boolean = false,
    val subs: List<SubTodo> = emptyList(),
    val createdAt: Long = 0L,
)

object TodoRules {
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 24 * HOUR_MS

    private val DAY_FMT = DateTimeFormatter.ofPattern("MM/dd")
    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

    fun levelOf(dueAt: Long?, now: Long): DueLevel {
        if (dueAt == null) return DueLevel.NONE
        val left = dueAt - now
        return when {
            // 已过期也算 URGENT：它比"还有 23 小时"更该被看见
            left <= DAY_MS -> DueLevel.URGENT
            left <= 3 * DAY_MS -> DueLevel.NEAR
            else -> DueLevel.FAR
        }
    }

    /**
     * 排序（docs/21 §6.7）：
     * 1. 24h 内（含已过期）置顶，按 DDL 升序
     * 2. 无 DDL 其次，按创建时间升序
     * 3. 其余按 DDL 由近到远
     * 已完成的排最后（首页只用未完成，但完整页会拿到全部）。
     */
    fun sorted(items: List<TodoItem>, now: Long): List<TodoItem> {
        val (done, active) = items.partition { it.done }
        val urgent = active
            .filter { it.dueAt != null && levelOf(it.dueAt, now) == DueLevel.URGENT }
            .sortedBy { it.dueAt }
        val noDue = active.filter { it.dueAt == null }.sortedBy { it.createdAt }
        val rest = active
            .filter { it.dueAt != null && levelOf(it.dueAt, now) != DueLevel.URGENT }
            .sortedBy { it.dueAt }
        return urgent + noDue + rest + done.sortedByDescending { it.createdAt }
    }

    /** 首页只显示未完成的前 [limit] 条。 */
    fun topOpen(items: List<TodoItem>, now: Long, limit: Int = 3): List<TodoItem> =
        sorted(items.filterNot { it.done }, now).take(limit)

    fun subProgress(item: TodoItem): Pair<Int, Int> =
        item.subs.count { it.done } to item.subs.size

    /** 子待办全完成 = 整条待办完成（docs/21 §6.6） */
    fun allSubsDone(item: TodoItem): Boolean =
        item.subs.isNotEmpty() && item.subs.all { it.done }

    /**
     * DDL 倒计时 `HH:mm:ss`。超过 24 小时返回 null（首页只在 24h 内显示红色倒计时）。
     */
    fun countdown(dueAt: Long?, now: Long): String? {
        if (dueAt == null) return null
        val left = (dueAt - now).coerceAtLeast(0L)
        if (left > DAY_MS) return null
        val totalSec = left / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    /** 卡片右侧的月日（`11/24`）。 */
    fun dueDayText(dueAt: Long?): String? {
        dueAt ?: return null
        return java.time.Instant.ofEpochMilli(dueAt).atZone(Agenda.ZONE).toLocalDate().format(DAY_FMT)
    }

    /** 卡片右侧的时分（`23:50`）。 */
    fun dueTimeText(dueAt: Long?): String? {
        dueAt ?: return null
        return java.time.Instant.ofEpochMilli(dueAt).atZone(Agenda.ZONE).toLocalTime().format(TIME_FMT)
    }

    /**
     * 把「日期 + 时分」拼成 dueAt 毫秒。
     * 只填日期不填时分 → 当天 23:59:59（docs/21 §6.8）。
     */
    fun dueAtOf(date: LocalDate, time: LocalTime?): Long =
        if (time != null) {
            date.atTime(time).atZone(Agenda.ZONE).toInstant().toEpochMilli()
        } else {
            date.atTime(23, 59, 59).atZone(Agenda.ZONE).toInstant().toEpochMilli()
        }
}
