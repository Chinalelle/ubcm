package com.kang.ubcm

/**
 * 「找一张没人的大桌子」——图书馆座位图的空间聚类。
 *
 * ## 为什么不能用「同排 + 间距」切（旧算法的错误）
 *
 * 旧版假设「同一排的座位 x 间距小、桌与桌之间间距大」，于是按 y 分行 + 间距 > 2.4 断开。
 * 拿真实数据一验，这个假设在**大部分区域是反的**：
 *
 * | 区域 | 同桌两个座位的间距 | 相邻两桌的间距 |
 * |---|---|---|
 * | 二层北区 | **3.73**（跨过中间那张桌子） | **2.15** |
 * | 三层南区 | **4.0–4.4** | 其它 |
 * | 五层南区（长桌） | 1.94–2.17（一排椅子） | 断点更远 |
 *
 * 二层北区「桌间距离比同桌距离还小」，任何单一距离阈值都必然切错：
 * 阈值 <3.7 会把一桌拆成两半，>2.15 又会把两桌并成一桌。
 *
 * ## 真正可靠的判据：座位编号
 *
 * 学校给座位编号时，**同一张桌的编号是连续的**。实测（二层北区 x=8.26/11.99 那一列）：
 *
 * ```
 * y=17.67  2N171  2N172
 * y=23.09  2N170  2N173      ← 编号从中间向两边展开
 * y=27.11  2N169  2N174
 * y=31.93  2N168  2N175
 * y=36.55  2N167  2N176      ← 167–176 连续 10 个 = 一张 10 座桌
 * y=46.18  2N105  2N106      ← 跳到 103–108 = 另一张 6 座桌（用户圈的那种）
 * ```
 *
 * 而相邻两桌之间编号会跳（172 → 181 跳 9）。
 *
 * ## 座位图的两种摆法（都要支持）
 *
 * - **垂直桌**：桌面竖着，座位分左右两列（二层北区、三层南区）→ 2 列 × N 行
 * - **水平长桌**：桌面横着，一排椅子（五层南区）→ 1 行 × N 列
 *
 * 判据里用「当前组是否还只有一行」来区分：只有一行时按长桌往后接，
 * 已经多行时按 2 列垂直桌约束。
 *
 * ## 单座列要拆开
 *
 * 五层南区 x=95.20 那一竖排是 17 个**独立单座**（编号连续、y 也连续），
 * 按上面规则会被并成「17 座桌」。所以成组后加一道规整性检查：
 * 多行的组必须是规整的「列 × 行」网格，否则拆回单座（交给 `minSize` 过滤）。
 */
object DeskFinder {

    /** 同一排的 y 容差（实测同排 y 完全相等或差 ≤0.4，给 1.0 足够稳） */
    const val ROW_TOLERANCE = 1.0

    /**
     * 水平长桌：相邻座位（同一行）的最大距离。
     *
     * 实测五层南区一排椅子的间距是 1.94–2.17，最长到 2.86；
     * 而「两张长桌之间的断点」是 4.92 和 5.60。
     * 阈值取 3.0 正好切在中间 —— 曾经用 6.0，把 5S086–090 和 091–096 并成了 11 座（错）。
     */
    const val HORIZONTAL_MAX_DIST = 3.0

    /** 垂直桌：新座位到组内已有座位的最大距离 */
    const val VERTICAL_MAX_DIST = 6.5

    /** 跨行合并时，两行之间的最大 y 间距（实测桌内 4–5.8，桌间 8.8+） */
    const val ROW_JOIN_TOLERANCE = 7.0

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
     * 把多个区域的座位混在一起聚类会切出「5S077–5WB267」这种跨区域的假桌子（真踩过）。
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

    /**
     * 从 `devName` 里取末尾数字，作为「座位编号」。
     * 取不到的排到最后（不会因此参与任何连续段）。
     */
    internal fun seatNumber(name: String): Int {
        val digits = Regex("(\\d+)\\s*$").find(name)?.groupValues?.get(1) ?: return Int.MAX_VALUE
        return digits.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun dist(a: SeatApi.Seat, b: SeatApi.Seat): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return Math.sqrt(dx * dx + dy * dy)
    }

    /**
     * 这个座位能不能并入当前桌。
     *
     * 三条硬约束：
     * 1. **编号必须紧接上一位**（这是切桌的主要依据）
     * 2. 空间上要挨着（不同模式用不同距离上限）
     * 3. 已经形成 2 列的垂直桌，不允许再出现第 3 列
     */
    internal fun canJoin(cur: List<SeatApi.Seat>, p: SeatApi.Seat): Boolean {
        if (cur.isEmpty()) return true
        if (seatNumber(p.name) != seatNumber(cur.last().name) + 1) return false

        val xs = cur.map { it.x }.toSet()
        val ys = cur.map { it.y }.toSet()
        val near = cur.minOf { dist(p, it) }
        val sameRow = ys.any { Math.abs(p.y - it) <= ROW_TOLERANCE }

        return when {
            // 还只有一行：水平长桌，往后接
            ys.size == 1 && sameRow -> near <= HORIZONTAL_MAX_DIST
            // 已经多行：垂直桌，最多 2 列
            xs.size <= 2 -> {
                if (near > VERTICAL_MAX_DIST) return false
                if (ys.none { Math.abs(p.y - it) <= ROW_JOIN_TOLERANCE }) return false
                // 前两列可以来自「配对」，第三列一律不行
                if (p.x !in xs && xs.size >= 2) return false
                true
            }
            else -> false
        }
    }

    private fun findInRoom(seats: List<SeatApi.Seat>, minSize: Int, slot: TimeSlot?): List<Desk> {
        // 去掉图上没定位的座位（学校数据里 devProp=0/坐标 0,0 的"未放置座位"）
        val valid = seats.filter { !(it.x == 0.0 && it.y == 0.0) }
        if (valid.isEmpty()) return emptyList()

        // 按座位编号排序 —— 编号顺序就是学校的排座顺序
        val sorted = valid.sortedBy { seatNumber(it.name) }

        // 贪心切组
        val groups = ArrayList<MutableList<SeatApi.Seat>>()
        var cur = mutableListOf(sorted.first())
        for (i in 1 until sorted.size) {
            val p = sorted[i]
            if (canJoin(cur, p)) {
                cur.add(p)
            } else {
                groups.add(cur)
                cur = mutableListOf(p)
            }
        }
        groups.add(cur)

        // 规整性检查 + 收集
        val desks = ArrayList<Desk>()
        for (g in groups) {
            val cols = g.map { it.x }.toSet().size
            val rows = g.map { it.y }.toSet().size
            val neat = rows == 1 || (cols == 2 && g.size == cols * rows)
            if (neat) {
                flush(desks, g, minSize, slot)
            } else {
                // 单座列 / 不规整 → 拆回单座，交给 minSize 过滤
                for (s in g) flush(desks, listOf(s), minSize, slot)
            }
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
