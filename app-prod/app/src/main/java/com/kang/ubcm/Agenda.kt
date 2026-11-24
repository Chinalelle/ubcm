package com.kang.ubcm

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 日程（docs/21 §5）：**课程实时派生 + 用户自建**，两份数据不重复存储。
 *
 * 这里是纯计算，不碰 Android / org.json，所以能被 JVM 单测直接覆盖。
 * 持久化在 [AgendaStore]。
 */
enum class AgendaSource { TIMETABLE, USER }

data class AgendaItem(
    val id: String,
    val title: String,
    /** 绝对时间（毫秒，Asia/Shanghai） */
    val startMillis: Long,
    /** null = 没设结束时间（自建日程允许） */
    val endMillis: Long?,
    val location: String?,
    val note: String?,
    val source: AgendaSource,
)

enum class AgendaPhase {
    /** 正在进行：金框 + 淡黄底 + 距结束 + 进度 */
    ONGOING,

    /** 最近的一条未来日程：蓝框 + 浅蓝底 + 距开始 */
    UPCOMING,

    /** 其余：白卡 */
    NORMAL,
}

data class AgendaCard(val item: AgendaItem, val phase: AgendaPhase)

object Agenda {
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    const val UPCOMING_SOON_MS = 10 * 60 * 1000L

    fun millisOf(dt: LocalDateTime): Long = dt.atZone(ZONE).toInstant().toEpochMilli()

    fun dateTimeOf(millis: Long): LocalDateTime =
        java.time.Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDateTime()

    /**
     * 从课表派生未来 [horizonDays] 天的课（含今天）。
     *
     * 幂等：同一份课表 + 同一个 now 永远得到同样的结果，所以不需要持久化。
     * id 里带日期与节次，保证跨天同名课不会互相覆盖。
     */
    fun fromTimetable(
        sessions: List<CourseSession>,
        slots: List<PeriodSlot>,
        now: LocalDateTime,
        firstWeekMonday: LocalDate,
        horizonDays: Int = 3,
    ): List<AgendaItem> {
        val today = now.toLocalDate()
        val out = mutableListOf<AgendaItem>()
        for (offset in 0..horizonDays) {
            val date = today.plusDays(offset.toLong())
            val week = Timetable.weekOf(date, firstWeekMonday)
            if (week < 1) continue
            for (s in Timetable.sessionsOn(sessions, date, week)) {
                val t = Timetable.place(s, date, slots) ?: continue
                out += AgendaItem(
                    id = "tt-${date}-${s.startPeriod}-${s.name}",
                    title = s.name,
                    startMillis = millisOf(t.start),
                    endMillis = millisOf(t.end),
                    location = s.location.ifBlank { null },
                    note = null,
                    source = AgendaSource.TIMETABLE,
                )
            }
        }
        return out
    }

    /** 课表派生 + 用户自建合并（按开始时间排序，不修改入参）。 */
    fun merge(timetable: List<AgendaItem>, user: List<AgendaItem>): List<AgendaItem> =
        (timetable + user).sortedBy { it.startMillis }

    /**
     * 取首页要显示的前 [limit] 条，并标出三态。
     *
     * 规则（docs/21 §5.3）：
     *  - 已结束（end < now）不显示
     *  - 正在进行的那条 → ONGOING（金框）
     *  - 若没有正在进行，最早的一条未来日程 → UPCOMING（蓝框）
     *  - 其余 NORMAL
     *  - 高亮最多各一条
     */
    fun nearest(items: List<AgendaItem>, nowMillis: Long, limit: Int = 3): List<AgendaCard> {
        val picked = items
            .filter { endOf(it) >= nowMillis }
            .sortedBy { it.startMillis }
            .take(limit)

        var upcomingAssigned = false
        return picked.map { item ->
            val phase = when {
                isOngoing(item, nowMillis) -> AgendaPhase.ONGOING
                item.startMillis > nowMillis && !upcomingAssigned -> {
                    upcomingAssigned = true
                    AgendaPhase.UPCOMING
                }
                else -> AgendaPhase.NORMAL
            }
            AgendaCard(item, phase)
        }
    }

    fun endOf(item: AgendaItem): Long = item.endMillis ?: item.startMillis

    fun isOngoing(item: AgendaItem, nowMillis: Long): Boolean =
        item.startMillis <= nowMillis && (item.endMillis == null || nowMillis < item.endMillis)

    /** 进行中进度百分比（0..100，向下取整）。没有结束时间或时长为 0 → 0。 */
    fun progressPercent(item: AgendaItem, nowMillis: Long): Int {
        val end = item.endMillis ?: return 0
        val span = end - item.startMillis
        if (span <= 0) return 0
        val done = (nowMillis - item.startMillis).coerceIn(0L, span)
        return ((done * 100) / span).toInt().coerceIn(0, 100)
    }

    /** 距开始 / 距结束：`HH:mm:ss`（补零，永不返回负数）。 */
    fun countdown(targetMillis: Long, nowMillis: Long): String {
        val left = (targetMillis - nowMillis).coerceAtLeast(0L)
        val totalSec = left / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
