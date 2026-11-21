package com.kang.ubcm

/**
 * 「找一张没人的大桌子」——图书馆座位图的空间聚类。
 *
 * 背景：图书馆的座位是**大桌**（4/6 连座）和**单人桌**混排的，一般人少时想找一张
 * 整桌空着的坐下来。学校页面只画点位，不给「哪几个座位属于同一张桌」，
 * 所以这里用**几何**推：[SeatApi.Seat] 自带 `coordinate`（图上百分比坐标），
 * 同一水平排 + 间距接近的一组座位，就是一张桌。
 *
 * 实测（五层南区 164 座）：
 *  - 同一排的 y 值完全相同，x 间距稳定在 1.94–2.17
 *  - 桌子之间的 x 间距会明显变大（2.6–3.3）
 * 所以「同排 + 间距 ≤ [GAP_TOLERANCE]」切分出来的组，就是一张桌。
 *
 * 这是**近似**：如果两张桌子紧挨着摆（间距仍是 2.0），会被并成一组。
 * 但这种情况下整组都空，用户直接坐也行，不会误导。
 */
object DeskFinder {

    /** 同一排的 y 容差（坐标是百分比，实测同行 y 完全相等，给 1.0 足够稳） */
    const val ROW_TOLERANCE = 1.0

    /** 同一张桌内相邻座位的 x 间距上限（实测 1.94–2.17，桌间 2.6+） */
    const val GAP_TOLERANCE = 2.4

    /** 用户要用的时段（当天分钟数） */
    data class TimeSlot(val startMin: Int, val endMin: Int) {
        val valid: Boolean get() = startMin in 0..1439 && endMin in 0..1440 && startMin < endMin
        fun label(): String = "%02d:%02d–%02d:%02d".format(
            startMin / 60, startMin % 60, endMin / 60, endMin % 60,
        )
    }

    data class Desk(
        val seats: List<SeatApi.Seat>,
        val roomId: Int,
        val roomName: String,
        val labName: String,
    ) {
        val size: Int get() = seats.size

        /** 整桌当天都没被预约 */
        val allFree: Boolean get() = seats.all { it.free }

        /** 已被占的座位数（整天口径） */
        val taken: Int get() = seats.count { !it.free }

        /** 在指定时段内整桌都空 */
        fun allFreeDuring(slot: TimeSlot): Boolean =
            seats.all { it.freeDuring(slot.startMin, slot.endMin) }

        /**
         * "5S077–5S084" 这种范围；单个座位就直接给名字。
         * 按**座位号**排序取首尾，不按 x —— 因为有些区域 x 增长方向和编号相反
         * （五层南区 5S157–5S164 就是这样，按 x 排会输出倒序）。
         */
        val nameRange: String
            get() {
                if (seats.isEmpty()) return ""
                val sorted = seats.sortedBy { it.name }
                val a = sorted.first().name
                val b = sorted.last().name
                return if (a == b) a else "$a–$b"
            }

            /** 按坐标排好序的座位名（方便画出来） */
        val namesInOrder: List<String> get() = seats.sortedBy { it.x }.map { it.name }
    }

    /**
     * 把一堆座位切成「桌」，只返回**够大**且**全空**的。
     *
     * ⚠️ **必须按区域分组后再聚类**：每个区域的 `coordinate` 是各自独立的坐标系，
     * 把多个区域的座位混在一起按 y/x 聚类，会切出「5S077–5WB267」这种跨区域的假桌子（真踩过）。
     *
     * @param minSize 多少连座算"大桌"（默认 4；单人桌/双人桌会被过滤掉）
     */
    fun findFreeDesks(
        seats: List<SeatApi.Seat>,
        minSize: Int = 4,
        /** 给了就按这个时段判断是否空闲；不给就按「当天完全没人约」 */
        slot: TimeSlot? = null,
    ): List<Desk> {
        if (seats.isEmpty()) return emptyList()
        return seats.groupBy { it.roomId }.values
            .flatMap { findInRoom(it, minSize, slot) }
            .sortedWith(compareByDescending<Desk> { it.size }.thenBy { it.nameRange })
    }

    private fun freeAt(seat: SeatApi.Seat, slot: TimeSlot?): Boolean =
        if (slot != null && slot.valid) seat.freeDuring(slot.startMin, slot.endMin) else seat.free

    private fun findInRoom(seats: List<SeatApi.Seat>, minSize: Int, slot: TimeSlot?): List<Desk> {
        if (seats.isEmpty()) return emptyList()

        // 1) 按 y 分行
        val rows = LinkedHashMap<Int, MutableList<SeatApi.Seat>>()
        for (s in seats) {
            val key = Math.round(s.y / ROW_TOLERANCE).toInt()
            // 相邻 key 可能是同一行，合并到最近的已有行
            val near = rows.keys.firstOrNull { Math.abs(it - key) <= 1 }
            rows.getOrPut(near ?: key) { mutableListOf() }.add(s)
        }

        // 2) 每行内按 x 排序，间距超限就断开
        val desks = ArrayList<Desk>()
        for ((_, rowSeats) in rows) {
            val sorted = rowSeats.sortedBy { it.x }
            var current = ArrayList<SeatApi.Seat>()
            for (s in sorted) {
                if (current.isEmpty()) {
                    current.add(s)
                } else {
                    val gap = s.x - current.last().x
                    if (gap <= GAP_TOLERANCE) {
                        current.add(s)
                    } else {
                        flush(desks, current, minSize, slot)
                        current = arrayListOf(s)
                    }
                }
            }
            flush(desks, current, minSize, slot)
        }

        return desks
    }

    private fun flush(into: MutableList<Desk>, group: List<SeatApi.Seat>, minSize: Int, slot: TimeSlot?) {
        if (group.size < minSize) return
        if (!group.all { freeAt(it, slot) }) return
        val first = group.first()
        into.add(Desk(group.toList(), first.roomId, first.roomName, first.labName))
    }

    /** 区域摘要：总座 / 空座 / 空桌数（4 连座以上） */
    data class Summary(
        val total: Int,
        val free: Int,
        val freeDeskCount: Int,
        val biggestDesk: Int,
    )

    fun summarize(seats: List<SeatApi.Seat>, minSize: Int = 4, slot: TimeSlot? = null): Summary = Summary(
        total = seats.size,
        free = if (slot != null && slot.valid) seats.count { freeAt(it, slot) } else seats.count { it.free },
        freeDeskCount = findFreeDesks(seats, minSize, slot).size,
        biggestDesk = findFreeDesks(seats, minSize, slot).maxOfOrNull { it.size } ?: 0,
    )
}
