package com.kang.ubcm

/**
 * 课表周视图的**纯逻辑**：把「整学期的排课」切成「某一周的网格」。
 *
 * 刻意不依赖 Android / Compose，只用 java/kotlin 标准库 —— 这样能被 JVM 单测直接覆盖。
 *
 * 为什么要有这一层（而不是直接在 Compose 里遍历 sessions 画）：
 *
 * 1. **跨节不能按「两节一组」分组**。真实的课有 1 节、1–2 节、1–3 节、5–7 节、1–4 节
 *    （见 TestTimetable：正常人体学Ⅰ 是周二 1–4 节，中医基础学是周一 1–3 节）。
 *    按大节分组会把 1–3 节切成「1–2 / 3」两块，视觉上和实际上都错。
 *    正确做法是**按节次高度绝对排版**：一个块占 `end - start + 1` 个行高。
 *
 * 2. **同一时段真有可能撞课**（同门课不同周次的不同地点、调课补课）。
 *    撞了就要横向分栏，不能重叠着画。分栏是纯几何计算，必须可测。
 *
 * 3. 周次过滤、周末是否显示，都是「少一个 if 就整片错」的地方。
 */
object WeekGrid {

    /** 一个排课在网格里的一格。坐标是「第几个节次」，像素换算见 [WeekGeometry]。 */
    data class Slot(
        val session: CourseSession,
        /** 1=周一 … 7=周日（= session.dayOfWeek，冗余一份便于分组） */
        val dayOfWeek: Int,
        val startPeriod: Int,
        val endPeriod: Int,
        /** 该时段内横向第几栏，从 0 开始 */
        val lane: Int = 0,
        /** 该时段共占几栏（同组共用同一个值，才能对齐宽度） */
        val laneCount: Int = 1,
    ) {
        /** 占几节 */
        val span: Int get() = endPeriod - startPeriod + 1

        /** 与另一格在「同一天且节次区间相交」时算冲突 */
        fun overlaps(other: Slot): Boolean =
            dayOfWeek == other.dayOfWeek &&
                startPeriod <= other.endPeriod && other.startPeriod <= endPeriod
    }

    /** 一周的完整网格 */
    data class Grid(
        val week: Int,
        /** 要显示的星期几，升序。周末整学期无课时只有 1..5 */
        val days: List<Int>,
        /** 要画多少行节次（至少 12，课表里出现更大的节次会跟随） */
        val periods: Int,
        val slots: List<Slot>,
    ) {
        val isEmpty: Boolean get() = slots.isEmpty()

        fun slotsOn(day: Int): List<Slot> =
            slots.filter { it.dayOfWeek == day }.sortedBy { it.startPeriod }
    }

    /** 全部排课覆盖到的周次范围（用于周切换器的边界）。没有课返回 null。 */
    fun weekRange(sessions: List<CourseSession>): IntRange? {
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (s in sessions) {
            for (w in s.weeks) {
                if (w <= 0) continue
                if (w < lo) lo = w
                if (w > hi) hi = w
            }
        }
        return if (lo > hi) null else lo..hi
    }

    /**
     * 整学期是否在周末（6/7）有课。
     * 用**整学期**判断而不是当前周：否则翻到有课的那周会突然从 5 列变 7 列，列宽跳动。
     */
    fun hasWeekend(sessions: List<CourseSession>): Boolean =
        sessions.any { it.dayOfWeek >= 6 }

    /** 课表里出现过的最大节次；用于决定画几行 */
    fun maxPeriod(sessions: List<CourseSession>): Int =
        sessions.maxOfOrNull { it.endPeriod } ?: 0

    /** 画多少行：至少 12（完整作息），有更晚的课就跟到那节 */
    fun periodCount(sessions: List<CourseSession>): Int =
        maxOf(MIN_PERIODS, maxPeriod(sessions))

    /**
     * 构建某一周的网格。
     *
     * @param sessions 整个学期的排课（未过滤周次）
     * @param week     要看的周次
     */
    fun build(sessions: List<CourseSession>, week: Int): Grid {
        val days = if (hasWeekend(sessions)) (1..7).toList() else (1..5).toList()
        val periods = periodCount(sessions)

        // 按周次过滤
        val active = sessions.filter { it.isOn(week) && it.dayOfWeek in 1..7 }

        // 逐天分栏：不同天的课不可能冲突，各算各的（跨天一起算会把周一和周三的课误判成冲突）
        val slots = ArrayList<Slot>(active.size)
        for (d in 1..7) {
            val onDay = active.filter { it.dayOfWeek == d }
                .sortedWith(compareBy({ it.startPeriod }, { it.endPeriod }))
            slots += assignLanes(onDay)
        }
        return Grid(week = week, days = days, periods = periods, slots = slots)
    }

    /**
     * 同一天内给互相冲突的课分栏。
     *
     * 算法（区间图贪心着色，按开始节次升序）：
     *  - 依次扫，能放进已有 lane（该 lane 上最后一个块的 endPeriod < 本次 startPeriod）就复用
     *  - 否则开新 lane
     *  - 一组**互相连通的冲突块**共享同一个 laneCount（组内最大的 lane+1），
     *    这样同组的块宽度一致，看起来才是"并排"而不是"错落"
     *
     * 不冲突的块（绝大多数情况）得到 lane=0、laneCount=1，占满整列宽度。
     */
    internal fun assignLanes(onDay: List<CourseSession>): List<Slot> {
        if (onDay.isEmpty()) return emptyList()

        // 先转成 Slot（lane 待定）
        val pending = onDay.map {
            Slot(
                session = it,
                dayOfWeek = it.dayOfWeek,
                startPeriod = it.startPeriod,
                endPeriod = it.endPeriod,
            )
        }

        val out = ArrayList<Slot>(pending.size)
        // 一个"组"= 一串首尾相连的冲突块。组结束时统一回填 laneCount。
        val group = ArrayList<MutableSlot>()
        // 每组里每个 lane 当前占用到第几节
        val laneEnd = HashMap<Int, Int>()

        fun flush() {
            if (group.isEmpty()) return
            val lanes = group.maxOf { it.lane } + 1
            for (s in group) out += s.toSlot(lanes)
            group.clear()
            laneEnd.clear()
        }

        for (s in pending) {
            // 与当前组里任何一块都不冲突 → 当前组已经封闭，可以结算
            if (group.isNotEmpty() && group.none { it.endPeriod >= s.startPeriod }) {
                flush()
            }
            // 找一个能复用的 lane：该 lane 上最后一块已经结束（不重叠）
            var lane = -1
            for (i in 0 until (laneEnd.keys.maxOrNull() ?: -1) + 1) {
                if ((laneEnd[i] ?: -1) < s.startPeriod) { lane = i; break }
            }
            if (lane < 0) lane = (laneEnd.keys.maxOrNull() ?: -1) + 1
            laneEnd[lane] = s.endPeriod
            group += MutableSlot(s, lane)
        }
        flush()
        return out
    }

    private class MutableSlot(val src: Slot, val lane: Int) {
        val startPeriod get() = src.startPeriod
        val endPeriod get() = src.endPeriod
        fun toSlot(laneCount: Int) = src.copy(lane = lane, laneCount = laneCount)
    }

    /** 完整作息是 12 小节（良乡）。周视图至少画这么多行。 */
    const val MIN_PERIODS = 12
}

/**
 * 节次 → 像素（dp）的换算。
 *
 * 设计选择：**按节次等高等距**，不按真实时间比例。
 * 真实作息里 2→3 节空 30 分钟、4→5 节空 90 分钟（午休）、8→9 节空 60 分钟（晚饭）、
 * 10→11 节只空 10 分钟。如果按时间比例画，午休会吞掉三分之一屏、晚饭再吞一块，
 * 每天晚上那两节课会被压成一条线。等距才是课表该有的样子。
 */
object WeekGeometry {

    /** 第 p 节那一格的顶部偏移（dp）。p 从 1 开始。 */
    fun topOf(period: Int, rowDp: Float, gapDp: Float): Float =
        (period - 1) * (rowDp + gapDp)

    /**
     * 从 start 到 end 节的一个块有多高（dp）。
     * 中间的节间 gap 要算进去：3 节 = 3 个行高 + 2 个 gap。
     */
    fun heightOf(startPeriod: Int, endPeriod: Int, rowDp: Float, gapDp: Float): Float {
        val span = (endPeriod - startPeriod + 1).coerceAtLeast(1)
        return span * rowDp + (span - 1) * gapDp
    }

    /** 画 periods 行总共要多高（dp） */
    fun totalHeight(periods: Int, rowDp: Float, gapDp: Float): Float =
        if (periods <= 0) 0f else periods * rowDp + (periods - 1) * gapDp
}

/**
 * 把 `[4,5,6,7,8,10,11]` 压成 `"4–8, 10–11"`。
 *
 * 课表里周次动辄十几条（如 4–15 周每周都有一条），原样列出来一行放不下。
 * 放在这个纯逻辑文件里而不是 UI 文件里，是为了能被 JVM 单测直接覆盖 ——
 * 边界（空、单条、全连续、首尾重复、0 和负数）都是真会遇到的脏数据。
 */
fun compressWeeks(weeks: List<Int>): String {
    val sorted = weeks.filter { it > 0 }.distinct().sorted()
    if (sorted.isEmpty()) return "—"
    val out = StringBuilder()
    var i = 0
    while (i < sorted.size) {
        var j = i
        while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
        if (out.isNotEmpty()) out.append(", ")
        out.append(if (j == i) "${sorted[i]}" else "${sorted[i]}–${sorted[j]}")
        i = j + 1
    }
    return out.toString()
}
