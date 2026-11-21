package com.kang.ubcm

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 课表领域模型 + 纯计算。
 *
 * 这里刻意不依赖 Android（只用 java.time），这样能被 JVM 单元测试直接覆盖。
 * 「下一节课」是首页最重要的一个数，算错一次用户就会失去信任，所以必须可测。
 */

/** 一次排课（一个 session）。同门课一周可能有多条，周次/地点/节次各不相同。 */
data class CourseSession(
    val name: String,
    val teacher: String,
    val location: String,
    /** 1=周一 … 7=周日 */
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    /** 上课周次，如 [4,5,…,15] */
    val weeks: List<Int>,
    val className: String = "",
) {
    fun isOn(week: Int): Boolean = week in weeks
}

/** 第 n 小节的起止（当天 0 点起的分钟数，便于直接比较） */
data class PeriodSlot(val period: Int, val startMin: Int, val endMin: Int)

/** 某节课在某个具体日期上的时间位置 */
data class SessionTime(
    val session: CourseSession,
    val date: LocalDate,
    val start: LocalDateTime,
    val end: LocalDateTime,
) {
    fun minutesUntilStart(now: LocalDateTime): Long =
        ChronoUnit.MINUTES.between(now, start)

    fun minutesUntilEnd(now: LocalDateTime): Long =
        ChronoUnit.MINUTES.between(now, end)
}

enum class CoursePhase {
    /** 还没开始 */
    UPCOMING,

    /** 正在上 */
    ONGOING,

    /** 今天已上完 */
    ENDED,
}

data class NextCourse(val time: SessionTime, val phase: CoursePhase)

object Timetable {

    /**
     * 本学期首周的**周一**。
     *
     * 来源：教务系统 bzyXskbList 在线课表 + 官方导出表交叉确认（2026-09-07）。
     * ⚠️ **每个新学期必须更新这个值**，否则周次全错、下一节课会算到不存在的课。
     * App 里把它存进 Session prefs，这里只是首次启动的默认值。
     */
    const val DEFAULT_FIRST_WEEK_MONDAY = "2026-09-07"

    /**
     * 良乡校区作息兜底表（12 小节）。
     * 联网时会用教务返回的 `lstJx0415zb`（大节起止）推算，这张表只在拿不到时用。
     * 依据：docs + 知识库「同学·北中医课表与作息」，与教务大节表推算结果一致。
     */
    val FALLBACK_SLOTS: List<PeriodSlot> = listOf(
        PeriodSlot(1, 8 * 60 + 30, 9 * 60 + 15),
        PeriodSlot(2, 9 * 60 + 15, 10 * 60),
        PeriodSlot(3, 10 * 60 + 30, 11 * 60 + 15),
        PeriodSlot(4, 11 * 60 + 15, 12 * 60),
        PeriodSlot(5, 13 * 60 + 30, 14 * 60 + 15),
        PeriodSlot(6, 14 * 60 + 15, 15 * 60),
        PeriodSlot(7, 15 * 60 + 30, 16 * 60 + 15),
        PeriodSlot(8, 16 * 60 + 15, 17 * 60),
        PeriodSlot(9, 18 * 60, 18 * 60 + 45),
        PeriodSlot(10, 18 * 60 + 45, 19 * 60 + 30),
        PeriodSlot(11, 19 * 60 + 40, 20 * 60 + 25),
        PeriodSlot(12, 20 * 60 + 25, 21 * 60 + 10),
    )

    // ---------- 周次 ----------

    /** 把 "2026-09-07" 解析成日期；失败返回 null（调用方回退到默认值） */
    fun parseFirstWeekMonday(raw: String?): LocalDate? = try {
        raw?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }
    } catch (e: Exception) {
        null
    }

    /**
     * 今天第几周。首周周一起算，第 1 周 = 首周，第 0 或负数 = 还没开学。
     * 用 days/7 而不是 ISO week，因为教务的"周"就是首周周一开始的 7 天块。
     *
     * 注意必须用 floorDiv：Kotlin 的 `/` 向零取整，开学前 6 天会被算成第 1 周（测试抓到过）。
     */
    fun weekOf(date: LocalDate, firstWeekMonday: LocalDate): Int =
        Math.floorDiv(ChronoUnit.DAYS.between(firstWeekMonday, date), 7L).toInt() + 1

    /** 某周周一的日期 */
    fun mondayOf(week: Int, firstWeekMonday: LocalDate): LocalDate =
        firstWeekMonday.plusDays((week - 1).toLong() * 7)

    // ---------- 节次时间 ----------

    /**
     * 从教务 `lstJx0415zb` 推小节时间。
     *
     * 教务只给**大节**（如「第 1-2 节 08:30–10:00」），但课可以是单节或跨大节
     * （如「第 1-3 节」= 大节 1-2 + 第 3 节）。所以把每个大节的时长**平均分配**给它覆盖的小节。
     *
     * 实测校验：大节 1-2 = 08:30–10:00 共 90 分钟 → 每节 45 分钟
     *   → 第 1 节 08:30–09:15、第 2 节 09:15–10:00，与官方导出表完全一致。
     */
    fun slotsFromPeriods(periods: List<JwApi.Period>): List<PeriodSlot> {
        if (periods.isEmpty()) return emptyList()
        val out = LinkedHashMap<Int, PeriodSlot>()
        for (p in periods) {
            val from = if (p.from > 0) p.from else continue
            val to = if (p.to >= from) p.to else from
            val startMin = localTimeToMinutes(p.start) ?: continue
            val endMin = localTimeToMinutes(p.end) ?: continue
            val span = endMin - startMin
            val count = to - from + 1
            if (span <= 0 || count <= 0) continue
            val each = span / count
            for (i in 0 until count) {
                val s = startMin + each * i
                val e = if (i == count - 1) endMin else startMin + each * (i + 1)
                out[from + i] = PeriodSlot(from + i, s, e)
            }
        }
        return out.values.sortedBy { it.period }
    }

    /** "08:30" → 510。容忍 "8:30" / "08:30:00" / 空白 */
    fun localTimeToMinutes(raw: String?): Int? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        val parts = t.split(":")
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun minutesToLocalTime(min: Int): String {
        val h = (min / 60) % 24
        val m = min % 60
        return "%02d:%02d".format(h, m)
    }

    /** 某节的开始/结束时间；缺失时回退到兜底表 */
    fun slotOf(period: Int, slots: List<PeriodSlot>): PeriodSlot? {
        slots.firstOrNull { it.period == period }?.let { return it }
        return FALLBACK_SLOTS.firstOrNull { it.period == period }
    }

    // ---------- 日程 ----------

    /** 某天的课（已按周次过滤，按开始节次排序） */
    fun sessionsOn(
        sessions: List<CourseSession>,
        date: LocalDate,
        week: Int,
    ): List<CourseSession> =
        sessions.filter { it.dayOfWeek == date.dayOfWeek.value && it.isOn(week) }
            .sortedBy { it.startPeriod }

    /** 把 session 放到具体日期上，得到绝对时间 */
    fun place(
        session: CourseSession,
        date: LocalDate,
        slots: List<PeriodSlot>,
    ): SessionTime? {
        val s = slotOf(session.startPeriod, slots) ?: return null
        val e = slotOf(session.endPeriod, slots) ?: s
        return SessionTime(
            session = session,
            date = date,
            start = LocalDateTime.of(date, LocalTime.of(s.startMin / 60, s.startMin % 60)),
            end = LocalDateTime.of(date, LocalTime.of(e.endMin / 60, e.endMin % 60)),
        )
    }

    /**
     * 找「下一节课」：从今天起往后看 7 天（今天 + 明天 … + 下周一）。
     *
     * 规则：
     *  - 正在上的课（now ∈ [start,end)）优先返回，phase=ONGOING
     *  - 否则返回最近一门**还没结束**的课，phase=UPCOMING
     *  - 7 天内都没有 → null（可能学期已结束/周次不匹配）
     */
    fun nextCourse(
        sessions: List<CourseSession>,
        slots: List<PeriodSlot>,
        now: LocalDateTime,
        firstWeekMonday: LocalDate,
        horizonDays: Int = 7,
    ): NextCourse? {
        val today = now.toLocalDate()
        for (offset in 0..horizonDays) {
            val date = today.plusDays(offset.toLong())
            val week = weekOf(date, firstWeekMonday)
            if (week < 1) continue
            val todays = sessionsOn(sessions, date, week)
            for (s in todays) {
                val t = place(s, date, slots) ?: continue
                if (!t.end.isAfter(now)) continue          // 已上完
                val phase = if (!t.start.isAfter(now)) CoursePhase.ONGOING else CoursePhase.UPCOMING
                return NextCourse(t, phase)
            }
        }
        return null
    }

    /** 今天剩下的课 / 今天的全部课 */
    fun todayCourses(
        sessions: List<CourseSession>,
        slots: List<PeriodSlot>,
        now: LocalDateTime,
        firstWeekMonday: LocalDate,
    ): List<NextCourse> {
        val date = now.toLocalDate()
        val week = weekOf(date, firstWeekMonday)
        if (week < 1) return emptyList()
        return sessionsOn(sessions, date, week).mapNotNull { s ->
            val t = place(s, date, slots) ?: return@mapNotNull null
            val phase = when {
                t.end.isBefore(now) || t.end == now -> CoursePhase.ENDED
                !t.start.isAfter(now) -> CoursePhase.ONGOING
                else -> CoursePhase.UPCOMING
            }
            NextCourse(t, phase)
        }
    }
}
