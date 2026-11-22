package com.kang.ubcm

/**
 * 座位图几何：把「一张桌的真实座位坐标」重新排成**规整的几何**。
 *
 * ## 为什么要重排，而不是直接用真实坐标
 *
 * 学校返回的 `coordinate` 是**画图用的百分比**，间距很不规整：同一个区域里
 * 行距可能是 4.02 也可能是 5.42，列距 3.73 但不同桌还不一样。
 * 直接映射出来会出现「座位大小不一、桌子被撑得歪七扭八」。
 *
 * 用户给了一套**比例标准**：
 * - 座位小正方形 = 1 单位
 * - 座位与桌子 / 座位与座位之间 = 0.5 单位
 * - 桌子长度 = 座位总长（六座桌每边 3 座 → `1×3 + 0.5×2 = 4` 单位）
 * - 桌子宽度 = 2 单位
 * - 圆角半径 = 0.25 单位
 *
 * 所以这里**只保留真实坐标的「桌中心」和「朝向」**，桌内一律按比例排。
 * 这样每张桌都规整，桌与桌的相对位置仍然和官方图一致。
 *
 * ## 单位换算
 *
 * 两列座位中心距 = 桌半宽(1) + 间距(0.5) + 座位半宽(0.5) = 2 单位，两列共 **4 单位**。
 * 实测二层北区两列中心距 3.73 → **1 单位 = 3.73 / 4 ≈ 0.93**（百分比坐标）。
 */
object DeskGeometry {

    /** 1 单位 ≈ 0.746 百分比坐标（由「一桌总宽 5 单位 = 相邻桌中心距 3.73」反推） */
    const val UNIT = 0.746f

    /** 座位边长 = 1 单位 */
    const val SEAT_SIZE = UNIT

    /** 列方向（座位 ↔ 桌子）的缝 = 0.5 单位 */
    const val GAP = UNIT * 0.5f

    /**
     * 行方向（座位 ↔ 座位）的缝。
     *
     * 用户先定的是 0.5，但那会把桌子压得很扁：三层南区真实行距 3.9，
     * 4 行桌按 0.5 排只有 4.1%，而真实跨度 11.7% —— 桌子只占 35%，看着又短又空。
     * 所以行缝放大到 1.5 单位，桌长变成 `N + (N-1)×1.5`：
     * 4 行 = 8.5 单位（原来的 1.55 倍），上下桌间距也随之收紧。
     *
     * 列缝仍是 0.5：水平方向一桌总宽已经等于相邻桌中心距（3.73），再放就会互相压住。
     */
    const val ROW_GAP = UNIT * 1.5f

    /** 桌子圆角半径 = 0.25 单位 */
    const val TABLE_CORNER = UNIT * 0.25f

    /** 高亮框在最小外接矩形之外，**四边各扩展 0.25 单位**（即总共半单位） */
    const val HIGHLIGHT_PAD = UNIT * 0.25f

    /** 桌子宽度 = 2 单位 */
    const val TABLE_WIDTH = UNIT * 2f

    enum class Shape {
        /** 单人座：没有独立桌面 */
        SINGLE,
        /** 垂直桌：座位左右两列，桌子在中间 */
        VERTICAL,
        /** 水平长桌：座位一排，桌子在下面 */
        HORIZONTAL,
    }

    /** 一个矩形（百分比坐标） */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        fun contains(x: Float, y: Float): Boolean =
            x >= left && x <= right && y >= top && y <= bottom
        fun union(o: Box): Box = Box(
            minOf(left, o.left), minOf(top, o.top),
            maxOf(right, o.right), maxOf(bottom, o.bottom),
        )
        fun expand(p: Float): Box = Box(left - p, top - p, right + p, bottom + p)
    }

    /** 排好位置的座位 */
    data class PlacedSeat(val seat: SeatApi.Seat, val box: Box)

    /** 排好的一张桌 */
    data class Placed(
        val desk: DeskFinder.Desk,
        val shape: Shape,
        /** 桌面矩形；单人座为 null */
        val table: Box?,
        /** 每个座位（位置已按规则重排） */
        val seats: List<PlacedSeat>,
    ) {
        /** 桌子 + 全部座位的**最小外接矩形**（高亮/筛选框的基准） */
        val outer: Box?
            get() {
                var box: Box? = table
                for (s in seats) box = box?.union(s.box) ?: s.box
                return box
            }
    }

    fun shapeOf(desk: DeskFinder.Desk): Shape {
        val n = desk.seats.size
        if (n <= 1) return Shape.SINGLE
        val cols = desk.seats.map { Math.round(it.x * 100) }.toSet().size
        val rows = desk.seats.map { Math.round(it.y * 100) }.toSet().size
        return when {
            rows == 1 && cols >= 2 -> Shape.HORIZONTAL
            cols == 2 -> Shape.VERTICAL
            // 不规整的（数据里少见）：座位多的按垂直桌处理
            n >= 2 && rows >= cols -> Shape.VERTICAL
            else -> Shape.HORIZONTAL
        }
    }

    /**
     * 把一张桌重新排成规整几何。
     *
     * 桌中心取真实座位坐标的质心（保留「这张桌在图上的位置」），
     * 桌内一切按 [UNIT] / [GAP] / [TABLE_WIDTH] 计算。
     */
    fun layout(desk: DeskFinder.Desk): Placed {
        val seats = desk.seats.filter { !(it.x == 0.0 && it.y == 0.0) }
        if (seats.isEmpty()) return Placed(desk, Shape.SINGLE, null, emptyList())

        val shape = shapeOf(desk)
        if (shape == Shape.SINGLE) {
            val s = seats.first()
            val box = seatAround(s.x.toFloat(), s.y.toFloat())
            return Placed(desk, shape, null, listOf(PlacedSeat(s, box)))
        }

        val cx = seats.map { it.x }.average().toFloat()
        val cy = seats.map { it.y }.average().toFloat()

        return when (shape) {
            Shape.VERTICAL -> layoutVertical(desk, seats, cx, cy)
            Shape.HORIZONTAL -> layoutHorizontal(desk, seats, cx, cy)
            Shape.SINGLE -> Placed(desk, Shape.SINGLE, null, emptyList())
        }
    }

    /** 以 (x,y) 为中心、边长为 1 单位的座位方块 */
    private fun seatAround(x: Float, y: Float): Box {
        val h = SEAT_SIZE / 2f
        return Box(x - h, y - h, x + h, y + h)
    }

    /**
     * 垂直桌：座位分左右两列，桌面夹在中间。
     *
     * 尺寸（单位）：宽 2（桌）+ 0.5 + 1（座位）两侧 = 总宽 5 单位；
     * 长度 = 行数 × 1 + (行数-1) × 0.5。
     */
    private fun layoutVertical(
        desk: DeskFinder.Desk,
        seats: List<SeatApi.Seat>,
        cx: Float,
        cy: Float,
    ): Placed {
        // 按 y 分行（真实坐标只用来决定「分几行、每行谁在左谁在右」）
        val sortedByY = seats.sortedBy { it.y }
        val rows = ArrayList<ArrayList<SeatApi.Seat>>()
        for (s in sortedByY) {
            val last = rows.lastOrNull()
            if (last != null && Math.abs(last.first().y - s.y) <= 1.5) last.add(s)
            else rows.add(arrayListOf(s))
        }
        val rowCount = rows.size

        val tableLen = rowCount * UNIT + (rowCount - 1) * ROW_GAP
        val tableTop = cy - tableLen / 2f
        val tableBottom = cy + tableLen / 2f
        val table = Box(cx - TABLE_WIDTH / 2f, tableTop, cx + TABLE_WIDTH / 2f, tableBottom)

        // 座位中心到桌中心的横向距离 = 桌半宽(1) + 间距(0.5) + 座位半宽(0.5) = 2 单位
        val dx = TABLE_WIDTH / 2f + GAP + SEAT_SIZE / 2f

        val placed = ArrayList<PlacedSeat>(seats.size)
        for ((ri, row) in rows.withIndex()) {
            val y = tableTop + SEAT_SIZE / 2f + ri * (SEAT_SIZE + ROW_GAP)
            val sortedRow = row.sortedBy { it.x }
            when (sortedRow.size) {
                1 -> {
                    // 单座的行（数据里偶有）：放左边
                    placed += PlacedSeat(sortedRow[0], seatAround(cx - dx, y))
                }
                else -> {
                    placed += PlacedSeat(sortedRow[0], seatAround(cx - dx, y))
                    placed += PlacedSeat(sortedRow[1], seatAround(cx + dx, y))
                    // 极少数超过 2 列的，多余的忽略（不该出现）
                }
            }
        }
        return Placed(desk, Shape.VERTICAL, table, placed)
    }

    /**
     * 水平长桌：座位排成一行，桌面在下面。
     * 长度 = 座位数 × 1 + (座位数-1) × 0.5，宽 2。
     */
    private fun layoutHorizontal(
        desk: DeskFinder.Desk,
        seats: List<SeatApi.Seat>,
        cx: Float,
        cy: Float,
    ): Placed {
        val sorted = seats.sortedBy { it.x }
        val n = sorted.size
        val tableLen = n * UNIT + (n - 1) * GAP   // 长桌是横向，仍用 0.5 的列缝
        val left = cx - tableLen / 2f
        // 座位行在上，桌面在它下面（间距 0.5）
        val seatY = cy - TABLE_WIDTH / 2f - GAP - SEAT_SIZE / 2f
        val table = Box(left, cy - TABLE_WIDTH / 2f, left + tableLen, cy + TABLE_WIDTH / 2f)

        val placed = ArrayList<PlacedSeat>(n)
        for ((i, s) in sorted.withIndex()) {
            val x = left + SEAT_SIZE / 2f + i * (SEAT_SIZE + GAP)
            placed += PlacedSeat(s, seatAround(x, seatY))
        }
        return Placed(desk, Shape.HORIZONTAL, table, placed)
    }

    /** 整个区域的包围盒（渲染时定画布比例） */
    fun boundsOf(placed: List<Placed>): Box? {
        var box: Box? = null
        for (p in placed) {
            val o = p.outer ?: continue
            box = box?.union(o) ?: o
        }
        return box?.expand(SEAT_SIZE * 2)
    }

    /** 从原始座位列表算包围盒（会先按房间分组 + 排版），给测试和外部调用用 */
    fun boundsOfSeats(seats: List<SeatApi.Seat>): Box? {
        val valid = seats.filter { !(it.x == 0.0 && it.y == 0.0) }
        if (valid.isEmpty()) return null
        return boundsOf(DeskFinder.groupAll(valid).map { layout(it) })
    }
}
