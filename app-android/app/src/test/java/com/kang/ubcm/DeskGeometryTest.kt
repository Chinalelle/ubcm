package com.kang.ubcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌子几何 + 三维筛选。
 *
 * 夹具是真实数据（[TestDesks]）：三层南区的 8 座垂直桌、13 座长桌，
 * 二层北区的 6 座 / 10 座垂直桌。
 */
class DeskGeometryTest {

    private fun desks(seats: List<SeatApi.Seat>, minSize: Int = 1) =
        DeskFinder.findFreeDesks(seats, minSize = minSize)

    private fun deskOf(ranges: String, seats: List<SeatApi.Seat> = TestDesks.BOTH): DeskFinder.Desk =
        desks(seats).first { it.nameRange == ranges }

    // ---------- 形状判定 ----------

    @Test
    fun `两列多行是垂直桌`() {
        // 3S062–069 是 8 座 = 2 列 × 4 行
        val d = deskOf("3S062–3S069")
        assertEquals(DeskGeometry.Shape.VERTICAL, DeskGeometry.shapeOf(d))
    }

    @Test
    fun `一行多列是水平长桌`() {
        // 3S049–061 是 13 座 = 1 行 13 列
        val d = deskOf("3S049–3S061")
        assertEquals(DeskGeometry.Shape.HORIZONTAL, DeskGeometry.shapeOf(d))
    }

    @Test
    fun `单人座没有桌面`() {
        val one = TestDesks.SAN_CENG_NAN.first().copy(name = "3S900", x = 50.0, y = 50.0)
        val d = DeskFinder.findFreeDesks(listOf(one), minSize = 1).first()
        assertEquals(DeskGeometry.Shape.SINGLE, DeskGeometry.shapeOf(d))
        assertNull("单人座不该画桌面", DeskGeometry.boxOf(d).table)
    }

    // ---------- 桌面矩形 ----------

    @Test
    fun `垂直桌的桌面夹在两列座位之间`() {
        val d = deskOf("3S062–3S069")
        val box = DeskGeometry.boxOf(d).table!!
        val xs = d.seats.map { it.x.toFloat() }
        val half = DeskGeometry.SEAT_SIZE / 2f
        // 左右边界必须落在两列的座位内侧，不能压住座位（留 10% 缝，视觉上才像"座位贴着桌边"）
        assertEquals(xs.min() + half * 0.9f, box.left, 0.01f)
        assertEquals(xs.max() - half * 0.9f, box.right, 0.01f)
        assertTrue("桌面要有宽度", box.width > 0f)
    }

    @Test
    fun `垂直桌的桌面高度覆盖所有行`() {
        val d = deskOf("2N167–2N176")
        val box = DeskGeometry.boxOf(d).table!!
        val ys = d.seats.map { it.y.toFloat() }
        assertTrue(box.top <= ys.min())
        assertTrue(box.bottom >= ys.max())
    }

    @Test
    fun `水平长桌的桌面在座位行下方`() {
        val d = deskOf("3S049–3S061")
        val box = DeskGeometry.boxOf(d).table!!
        val y = d.seats.minOf { it.y }.toFloat()
        assertTrue("桌面顶边应在座位行下方（或紧贴）", box.top >= y)
        assertTrue("桌面要有厚度", box.height > 0.5f)
        // 横向铺满整排座位
        assertTrue(box.width >= d.seats.map { it.x.toFloat() }.max() - d.seats.map { it.x.toFloat() }.min())
    }

    @Test
    fun `六座桌的桌面是两列之间的窄条`() {
        val d = deskOf("2N001–2N006")
        val box = DeskGeometry.boxOf(d).table!!
        val xs = d.seats.map { it.x.toFloat() }
        assertEquals(2, d.seats.map { Math.round(it.x * 100) }.toSet().size)
        // 桌宽 = 列间距 − 两侧各留 10% 座宽的缝
        assertEquals(
            xs.max() - xs.min() - DeskGeometry.SEAT_SIZE * 0.9f,
            box.width, 0.05f,
        )
    }

    // ---------- 座位方块 ----------

    @Test
    fun `座位方块以坐标为中心`() {
        val s = TestDesks.SAN_CENG_NAN.first()
        val b = DeskGeometry.seatBox(s)
        assertEquals(s.x.toFloat(), b.centerX, 0.001f)
        assertEquals(s.y.toFloat(), b.centerY, 0.001f)
        assertEquals(DeskGeometry.SEAT_SIZE, b.width, 0.001f)
        assertEquals(DeskGeometry.SEAT_SIZE, b.height, 0.001f)
    }

    @Test
    fun `区域包围盒能包住所有座位`() {
        val seats = TestDesks.SAN_CENG_NAN
        val b = DeskGeometry.boundsOf(seats)!!
        for (s in seats) {
            assertTrue("${s.name} 超出包围盒", b.contains(s.x.toFloat(), s.y.toFloat()))
        }
    }

    @Test
    fun `空区域没有包围盒`() {
        assertNull(DeskGeometry.boundsOf(emptyList()))
    }

    @Test
    fun `坐标0-0的幽灵座位不能污染包围盒`() {
        // 学校数据里 devProp=0 的座位坐标是 0,0（未在图纸上定位）。
        // 2026-11-21 真机翻车：把它算进包围盒 → x/y 从 0 开始 → 整个座位图被压到左上角一小块。
        val ghost = TestDesks.SAN_CENG_NAN.first().copy(name = "3S999", x = 0.0, y = 0.0)
        val withGhost = DeskGeometry.boundsOf(TestDesks.SAN_CENG_NAN + ghost)!!
        val without = DeskGeometry.boundsOf(TestDesks.SAN_CENG_NAN)!!
        assertEquals(without.left, withGhost.left, 0.001f)
        assertEquals(without.top, withGhost.top, 0.001f)
        assertEquals(without.right, withGhost.right, 0.001f)
        assertEquals(without.bottom, withGhost.bottom, 0.001f)
    }

    @Test
    fun `全是幽灵座位时返回 null`() {
        val ghosts = List(3) { TestDesks.SAN_CENG_NAN.first().copy(name = "3S90$it", x = 0.0, y = 0.0) }
        assertNull(DeskGeometry.boundsOf(ghosts))
    }

    // ---------- 筛选：类型 ----------

    @Test
    fun `类型档位覆盖所有座位数`() {
        // 关键：不能有哪张桌在筛选时凭空消失
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
        assertTrue(SeatFilter.DeskType.ALL.accepts(1))
        assertTrue(SeatFilter.DeskType.ALL.accepts(13))
    }

    @Test
    fun `筛选八座桌`() {
        val all = desks(TestDesks.SAN_CENG_NAN, minSize = 1)
        val c = SeatFilter.Criteria(8 * 60, 22 * 60, SeatFilter.DeskType.LONG, onlyFullyFree = true)
        val got = SeatFilter.apply(all, c)
        assertTrue("应该有 8 座的长桌", got.any { it.size == 8 })
        assertTrue("结果都必须是 7 座以上", got.all { it.size >= 7 })
    }

    @Test
    fun `筛选六座桌不会混进四座`() {
        val all = desks(TestDesks.ER_CENG_BEI, minSize = 1)
        val c = SeatFilter.Criteria(8 * 60, 22 * 60, SeatFilter.DeskType.SIX, true)
        val got = SeatFilter.apply(all, c)
        assertTrue("六座桌存在", got.isNotEmpty())
        assertTrue(got.all { it.size in 5..6 })
    }

    // ---------- 筛选：时段 ----------

    @Test
    fun `时段内被占的桌不算全空`() {
        // 注意：findFreeDesks 本来就只返回「全空」的桌，所以这里先拿干净的桌，
        // 再手工给其中一个座位塞一段预约 —— 否则那张桌根本不会出现在结果里。
        val clean = deskOf("2N001–2N006")
        val desk = clean.copy(seats = clean.seats.map {
            if (it.name == "2N002") it.copy(reservations = listOf(SeatApi.SeatResv(1, 14 * 60, 16 * 60)))
            else it
        })

        val morning = SeatFilter.Criteria(9 * 60, 11 * 60)
        val afternoon = SeatFilter.Criteria(14 * 60 + 30, 15 * 60)
        val wholeDay = SeatFilter.Criteria(8 * 60, 22 * 60)

        assertTrue("上午它应该还空着", SeatFilter.matches(desk, morning))
        assertTrue("下午被占了", !SeatFilter.matches(desk, afternoon))
        assertTrue("整天口径也被占", !SeatFilter.matches(desk, wholeDay))
    }

    @Test
    fun `不看全空时只要还有一个空位就算`() {
        val clean = deskOf("2N001–2N006")
        val desk = clean.copy(seats = clean.seats.map {
            if (it.name != "2N001") it.copy(reservations = listOf(SeatApi.SeatResv(1, 14 * 60, 16 * 60)))
            else it
        })
        val c = SeatFilter.Criteria(14 * 60 + 30, 15 * 60, onlyFullyFree = false)
        assertTrue("还有 2N001 空着，应该算命中", SeatFilter.matches(desk, c))

        val strict = c.copy(onlyFullyFree = true)
        assertTrue("要求全空时不该命中", !SeatFilter.matches(desk, strict))
    }

    @Test
    fun `座位空闲按当前时段判断而不是整天`() {
        val seat = TestDesks.ER_CENG_BEI.first { it.name == "2N001" }
            .copy(reservations = listOf(SeatApi.SeatResv(1, 9 * 60, 11 * 60)))
        val morning = SeatFilter.Criteria(9 * 60 + 30, 10 * 60)
        val afternoon = SeatFilter.Criteria(14 * 60, 16 * 60)
        assertTrue("上午 9:30 应该是占用的", !SeatFilter.seatFreeAt(seat, morning))
        assertTrue("下午应该空闲", SeatFilter.seatFreeAt(seat, afternoon))
    }
}
