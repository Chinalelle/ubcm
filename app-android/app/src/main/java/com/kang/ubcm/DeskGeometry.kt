package com.kang.ubcm

/**
 * 座位 → 「桌子矩形」的几何推导。
 *
 * ## 为什么需要它
 *
 * 学校接口只返回**座位点**（`coordinate` 是百分比坐标），**不返回桌子**：
 * `pointProperty` / `msideCoordinate` 全空，`devProp=0` 那几条只是「未在图纸上定位的座位」。
 * 但用户看的是官方座位图——图上画着棕色的桌子，座位是围着桌子的圆点。
 * 所以桌子位置只能从座位反推。
 *
 * ## 判定规则（对齐官方图）
 *
 * 座位图有两种摆法（见 [DeskFinder] 的说明）：
 * - **垂直桌**：座位分左右两列，桌子夹在中间 → 矩形 x 取两列之间
 * - **水平长桌**：座位排成一行，桌子贴在座位行的下方
 *
 * ## 参数是估的，但估得有依据
 *
 * `SEAT_SIZE` 取 1.2（百分比）。依据：二层北区两列间距 3.73，而官方图上座位点直径
 * 约占两列间距的三分之一，即 ~1.2；桌宽 = 3.73 − 1.2 ≈ 2.5，与图上比例一致。
 *
 * ⚠️ **水平长桌的桌子画在座位下方**是推断的（数据里看不出在上一侧还是下一侧）。
 * 官方图上大多数长桌确实在座位下方，个别在上的会偏一格，肉眼能认出是桌子。
 */
object DeskGeometry {

    /** 座位边长（百分比坐标单位） */
    const val SEAT_SIZE = 1.65f

    /** 桌子在垂直桌方向上的厚度下限，避免长条桌被压成一条线 */
    const val MIN_TABLE_THICKNESS = 1.0f

    enum class Shape {
        /** 单人座：没有独立桌面，只画一个座位方块 */
        SINGLE,

        /** 垂直桌：座位左右两列，桌子在中间（2 列 × N 行） */
        VERTICAL,

        /** 水平长桌：座位一排，桌子在下方（1 行 × N 列） */
        HORIZONTAL,
    }

    /** 一个矩形（百分比坐标，左上 + 右下） */
    data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        fun contains(x: Float, y: Float): Boolean =
            x >= left && x <= right && y >= top && y <= bottom
    }

    /** 一张桌的几何信息：形状 + 桌面矩形（SINGLE 时为 null） */
    data class DeskShape(val shape: Shape, val table: Box?)

    /** 判断形状：1 座 = 单人；1 行多列 = 水平长桌；2 列多行 = 垂直桌 */
    fun shapeOf(desk: DeskFinder.Desk): Shape {
        val seats = desk.seats
        if (seats.size <= 1) return Shape.SINGLE
        val cols = seats.map { r100(it.x) }.toSet().size
        val rows = seats.map { r100(it.y) }.toSet().size
        return when {
            rows == 1 && cols >= 2 -> Shape.HORIZONTAL
            cols == 2 && rows >= 2 -> Shape.VERTICAL
            // 不规整的组（不该出现，DeskFinder 已过滤）按座位数的形状给个兜底
            cols >= 2 && rows >= 2 -> Shape.VERTICAL
            else -> Shape.SINGLE
        }
    }

    private fun r100(v: Double): Int = Math.round(v * 100).toInt()

    /**
     * 算出一张桌的矩形。
     *
     * @return 单人座返回 `table = null`（没有桌面可画）
     */
    fun boxOf(desk: DeskFinder.Desk): DeskShape {
        val seats = desk.seats
        val shape = shapeOf(desk)
        if (shape == Shape.SINGLE || seats.isEmpty()) return DeskShape(shape, null)

        val xs = seats.map { it.x.toFloat() }
        val ys = seats.map { it.y.toFloat() }
        val half = SEAT_SIZE / 2f

        return when (shape) {
            Shape.VERTICAL -> {
                // 两列之间夹着桌面
                val x1 = xs.min()
                val x2 = xs.max()
                val left = x1 + half * 0.9f
                val right = x2 - half * 0.9f
                if (right - left < MIN_TABLE_THICKNESS) {
                    // 两列贴太近（数据异常），退化成一条居中细条
                    val cx = (x1 + x2) / 2f
                    DeskShape(
                        shape,
                        Box(cx - MIN_TABLE_THICKNESS / 2, ys.min() - half * 0.15f, cx + MIN_TABLE_THICKNESS / 2, ys.max() + half * 0.15f),
                    )
                } else {
                    DeskShape(shape, Box(left, ys.min() - half * 0.15f, right, ys.max() + half * 0.15f))
                }
            }

            Shape.HORIZONTAL -> {
                // 座位排成一行，桌面画在这行下方
                val y = ys.min()
                DeskShape(
                    shape,
                    Box(xs.min() - half, y + half * 0.9f, xs.max() + half, y + half * 0.9f + SEAT_SIZE * 0.95f),
                )
            }

            Shape.SINGLE -> DeskShape(shape, null)
        }
    }

    /**
     * **整张桌的外接矩形：桌子 + 所有座位**。
     *
     * 高亮框和筛选框都要框住这个范围，而不是只框桌面 ——
     * 只框桌面的话，座位会露在框外面，看起来像「框住的不是这张桌」。
     */
    fun outerBoxOf(desk: DeskFinder.Desk): Box? {
        if (desk.seats.isEmpty()) return null
        val table = boxOf(desk).table
        val xs = desk.seats.map { it.x.toFloat() }
        val ys = desk.seats.map { it.y.toFloat() }
        val half = SEAT_SIZE / 2f
        var left = xs.min() - half
        var top = ys.min() - half
        var right = xs.max() + half
        var bottom = ys.max() + half
        if (table != null) {
            left = minOf(left, table.left)
            top = minOf(top, table.top)
            right = maxOf(right, table.right)
            bottom = maxOf(bottom, table.bottom)
        }
        return Box(left, top, right, bottom)
    }

    /** 座位方块（百分比坐标） */
    fun seatBox(seat: SeatApi.Seat): Box {
        val half = SEAT_SIZE / 2f
        val x = seat.x.toFloat()
        val y = seat.y.toFloat()
        return Box(x - half, y - half, x + half, y + half)
    }

    /**
     * 整个区域的包围盒（渲染时用来定画布比例）。
     *
     * ⚠️ **必须剔除坐标 (0,0) 的幽灵座位**：学校数据里 `devProp=0` 的那几条座位
     * 是「未在图纸上定位」的，坐标就是 0,0。如果把它们算进包围盒，
     * 整个区域的 x/y 范围会被拉成从 0 开始 —— 结果所有座位被压到画布左上角一小块。
     * （2026-11-21 真机上就是这么翻车的：座位图只看得见底部一条。）
     *
     * 没有任何有效座位时返回 null。
     */
    fun boundsOf(seats: List<SeatApi.Seat>): Box? {
        val valid = seats.filter { !(it.x == 0.0 && it.y == 0.0) }
        if (valid.isEmpty()) return null
        val xs = valid.map { it.x.toFloat() }
        val ys = valid.map { it.y.toFloat() }
        val pad = SEAT_SIZE * 2
        return Box(
            xs.min() - pad, ys.min() - pad,
            xs.max() + pad, ys.max() + pad,
        )
    }
}
