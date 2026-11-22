package com.kang.ubcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 座位图几何 + 三维筛选。
 *
 * 几何部分打的是**用户给的比例标准**：
 * - 座位边长 = 1 单位
 * - 座位与桌子 / 座位与座位 = 0.5 单位
 * - 桌子长度 = 座位总长（N 座 → `1×N + 0.5×(N-1)` 单位）
 * - 桌子宽度 = 2 单位
 * - 圆角 = 0.25 单位
 * - 高亮框 = 最小外接矩形 + **四边各** 0.25 单位（总共半单位）
 *
 * 1 单位 = [DeskGeometry.UNIT] = 0.93 百分比坐标（由「两列中心距 3.73 = 4 单位」反推）。
 */
class DeskGeometryTest {

    private val U = DeskGeometry.UNIT

    private fun desks(seats: List<SeatApi.Seat>, minSize: Int = 1) =
        DeskFinder.findFreeDesks(seats, minSize = minSize)

    private fun deskOf(ranges: String, seats: List<SeatApi.Seat> = TestDesks.BOTH): DeskFinder.Desk =
        desks(seats).first { it.nameRange == ranges }

    private fun placed(ranges: String, seats: List<SeatApi.Seat> = TestDesks.BOTH) =
        DeskGeometry.layout(deskOf(ranges, seats))

    // ---------- 形状判定 ----------

    @Test
    fun `两列多行是垂直桌`() {
        assertEquals(DeskGeometry.Shape.VERTICAL, placed("3S062–3S069").shape)
    }

    @Test
    fun `一行多列是水平长桌`() {
        assertEquals(DeskGeometry.Shape.HORIZONTAL, placed("3S049–3S061").shape)
    }

    @Test
    fun `单人座没有桌面`() {
        val one = TestDesks.SAN_CENG_NAN.first().copy(name = "3S900", x = 50.0, y = 50.0)
        val p = DeskGeometry.layout(DeskFinder.findFreeDesks(listOf(one), minSize = 1).first())
        assertEquals(DeskGeometry.Shape.SINGLE, p.shape)
        assertNull("单人座不该画桌面", p.table)
        assertEquals(1, p.seats.size)
    }

    // ---------- 比例标准 ----------

    @Test
    fun `座位是边长 1 单位的小正方形`() {
        val p = placed("3S062–3S069")
        for (ps in p.seats) {
            assertEquals("座位宽", U, ps.box.width, 0.01f)
            assertEquals("座位高", U, ps.box.height, 0.01f)
        }
    }

    @Test
    fun `桌子宽度固定是 2 单位`() {
        for (r in listOf("3S062–3S069", "2N167–2N176", "2N001–2N006")) {
            val p = placed(r)
            assertEquals("$r 桌宽", U * 2, p.table!!.width, 0.01f)
        }
    }

    @Test
    fun `垂直桌的长度等于座位总长`() {
        // 八座 = 每边 4 行 → 4×1 + 3×0.5 = 5.5 单位
        assertEquals(U * 5.5f, placed("3S062–3S069").table!!.height, 0.02f)
        // 十座 = 每边 5 行 → 5×1 + 4×0.5 = 7 单位
        assertEquals(U * 7f, placed("2N167–2N176").table!!.height, 0.02f)
        // 六座 = 每边 3 行 → 3×1 + 2×0.5 = 4 单位（用户举的例子）
        assertEquals(U * 4f, placed("2N001–2N006").table!!.height, 0.02f)
    }

    @Test
    fun `水平长桌的长度等于座位总长`() {
        // 13 座一排 → 13×1 + 12×0.5 = 19 单位
        assertEquals(U * 19f, placed("3S049–3S061").table!!.width, 0.03f)
    }

    @Test
    fun `同一列上下两个座位的间距是 0_5 单位`() {
        val p = placed("3S062–3S069")
        val leftCol = p.seats.filter { it.box.centerX < p.table!!.centerX }.sortedBy { it.box.centerY }
        assertTrue("八座桌左列应有 4 个座位", leftCol.size == 4)
        for (i in 1 until leftCol.size) {
            val gap = leftCol[i].box.top - leftCol[i - 1].box.bottom
            assertEquals("第 $i 个行间缝", U * 0.5f, gap, 0.01f)
        }
    }

    @Test
    fun `座位与桌子之间的间距是 0_5 单位`() {
        val p = placed("3S062–3S069")
        val table = p.table!!
        for (ps in p.seats) {
            val gap = if (ps.box.centerX < table.centerX) {
                table.left - ps.box.right
            } else {
                ps.box.left - table.right
            }
            assertEquals("座位 ${ps.seat.name} 到桌边的缝", U * 0.5f, gap, 0.01f)
        }
    }

    @Test
    fun `高亮外扩是四边各 0_25 单位`() {
        assertEquals(U * 0.25f, DeskGeometry.HIGHLIGHT_PAD, 0.001f)
        val p = placed("3S062–3S069")
        val outer = p.outer!!
        val expanded = outer.expand(DeskGeometry.HIGHLIGHT_PAD)
        assertEquals(U * 0.25f, outer.left - expanded.left, 0.001f)
        assertEquals(U * 0.25f, expanded.right - outer.right, 0.001f)
        assertEquals(U * 0.25f, outer.top - expanded.top, 0.001f)
        assertEquals(U * 0.25f, expanded.bottom - outer.bottom, 0.001f)
    }

    @Test
    fun `桌子圆角是 0_25 单位`() {
        assertEquals(U * 0.25f, DeskGeometry.TABLE_CORNER, 0.001f)
    }

    // ---------- 外接矩形 ----------

    @Test
    fun `外接矩形包住所有座位和桌面`() {
        val p = placed("3S062–3S069")
        val outer = p.outer!!
        for (ps in p.seats) {
            assertTrue("座位 ${ps.seat.name} 超出外接矩形", outer.contains(ps.box.centerX, ps.box.centerY))
        }
        val t = p.table!!
        assertTrue(outer.left <= t.left + 0.001f)
        assertTrue(outer.right >= t.right - 0.001f)
    }

    @Test
    fun `外接矩形比桌面宽（含两侧座位）`() {
        val p = placed("3S062–3S069")
        assertTrue(p.outer!!.width > p.table!!.width)
    }

    // ---------- 布局一致性 ----------

    @Test
    fun `重排后座位不重叠`() {
        for (r in listOf("3S062–3S069", "2N167–2N176", "2N001–2N006", "3S049–3S061")) {
            val p = placed(r)
            val boxes = p.seats.map { it.box }
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                val a = boxes[i]; val b = boxes[j]
                val overlap = a.left < b.right - 0.001f && b.left < a.right - 0.001f &&
                    a.top < b.bottom - 0.001f && b.top < a.bottom - 0.001f
                assertTrue("$r 的第 $i 和第 $j 个座位重叠了", !overlap)
            }
        }
    }

    @Test
    fun `桌中心保留真实位置`() {
        // 重排只改桌内布局，桌在图上的位置必须还是原来的质心附近
        val d = deskOf("3S062–3S069")
        val p = DeskGeometry.layout(d)
        val cx = d.seats.map { it.x }.average().toFloat()
        val cy = d.seats.map { it.y }.average().toFloat()
        assertEquals(cx, p.table!!.centerX, 0.5f)
        assertEquals(cy, p.table!!.centerY, 0.5f)
    }

    @Test
    fun `坐标0-0的幽灵座位不能污染包围盒`() {
        val ghost = TestDesks.SAN_CENG_NAN.first().copy(name = "3S999", x = 0.0, y = 0.0)
        val withGhost = DeskGeometry.boundsOfSeats(TestDesks.SAN_CENG_NAN + ghost)!!
        val without = DeskGeometry.boundsOfSeats(TestDesks.SAN_CENG_NAN)!!
        assertEquals(without.left, withGhost.left, 0.001f)
        assertEquals(without.top, withGhost.top, 0.001f)
    }

    @Test
    fun `全是幽灵座位时返回 null`() {
        val ghosts = List(3) { TestDesks.SAN_CENG_NAN.first().copy(name = "3S90$it", x = 0.0, y = 0.0) }
        assertNull(DeskGeometry.boundsOfSeats(ghosts))
    }

    @Test
    fun `空区域没有包围盒`() {
        assertNull(DeskGeometry.boundsOfSeats(emptyList()))
    }

    // ---------- 筛选：类型 ----------

    @Test
    fun `类型档位覆盖所有座位数`() {
        for (size in 1..20) {
            val hit = SeatFilter.DeskType.entries.filter { it != SeatFilter.DeskType.ALL && it.accepts(size) }
            assertEquals("size=$size 应该恰好落在一个档位", 1, hit.size)
        }
    }

    @Test
    fun `各档位边界`() {
        assertTrue(SeatFilter.DeskType.SINGLE.accepts(1))
        assertTrue(SeatFilter.DeskType.FOUR.accepts(2))
        assertTrue(SeatFilter.DeskType.FOUR.accepts(4))
        assertTrue(SeatFilter.DeskType.SIX.accepts(6))
        assertTrue(SeatFilter.DeskType.LONG.accepts(7))
        assertTrue(SeatFilter.DeskType.LONG.accepts(13))
    }

    @Test
    fun `筛选八座桌`() {
        val all = desks(TestDesks.SAN_CENG_NAN, minSize = 1)
        val c = SeatFilter.Criteria(8 * 60, 22 * 60, SeatFilter.DeskType.LONG, onlyFullyFree = true)
        val got = SeatFilter.apply(all, c)
        assertTrue("应该有 8 座的长桌", got.any { it.size == 8 })
        assertTrue(got.all { it.size >= 7 })
    }

    @Test
    fun `筛选六座桌不会混进四座`() {
        val all = desks(TestDesks.ER_CENG_BEI, minSize = 1)
        val c = SeatFilter.Criteria(8 * 60, 22 * 60, SeatFilter.DeskType.SIX, true)
        val got = SeatFilter.apply(all, c)
        assertTrue(got.isNotEmpty())
        assertTrue(got.all { it.size in 5..6 })
    }

    // ---------- 筛选：时段 ----------

    @Test
    fun `时段内被占的桌不算全空`() {
        val clean = deskOf("2N001–2N006")
        val desk = clean.copy(seats = clean.seats.map {
            if (it.name == "2N002") it.copy(reservations = listOf(SeatApi.SeatResv(1, 14 * 60, 16 * 60)))
            else it
        })
        assertTrue("上午它应该还空着", SeatFilter.matches(desk, SeatFilter.Criteria(9 * 60, 11 * 60)))
        assertTrue("下午被占了", !SeatFilter.matches(desk, SeatFilter.Criteria(14 * 60 + 30, 15 * 60)))
        assertTrue("整天口径也被占", !SeatFilter.matches(desk, SeatFilter.Criteria(8 * 60, 22 * 60)))
    }

    @Test
    fun `不看全空时只要还有一个空位就算`() {
        val clean = deskOf("2N001–2N006")
        val desk = clean.copy(seats = clean.seats.map {
            if (it.name != "2N001") it.copy(reservations = listOf(SeatApi.SeatResv(1, 14 * 60, 16 * 60)))
            else it
        })
        val loose = SeatFilter.Criteria(14 * 60 + 30, 15 * 60, onlyFullyFree = false)
        assertTrue("还有 2N001 空着，应该算命中", SeatFilter.matches(desk, loose))
        assertTrue("要求全空时不该命中", !SeatFilter.matches(desk, loose.copy(onlyFullyFree = true)))
    }

    @Test
    fun `座位空闲按当前时段判断而不是整天`() {
        val seat = TestDesks.ER_CENG_BEI.first { it.name == "2N001" }
            .copy(reservations = listOf(SeatApi.SeatResv(1, 9 * 60, 11 * 60)))
        assertTrue("上午 9:30 应该被占", !SeatFilter.seatFreeAt(seat, SeatFilter.Criteria(9 * 60 + 30, 10 * 60)))
        assertTrue("下午应该空闲", SeatFilter.seatFreeAt(seat, SeatFilter.Criteria(14 * 60, 16 * 60)))
    }

    private fun nonNull(v: Any?): Boolean = v != null

    @Test
    fun `每个排版结果都非空`() {
        for (p in TestDesks.BOTH.let { DeskFinder.groupAll(it) }.map { DeskGeometry.layout(it) }) {
            assertNotNull(p.desk)
            assertTrue(nonNull(p.outer))
        }
    }
}
