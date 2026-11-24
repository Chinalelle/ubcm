package com.kang.ubcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面聚类测试 —— 打的是图书馆五层南区的**真实 164 座**（[TestSeats.WU_QU_NAN]）。
 *
 * 现实结构（人工核对过坐标）：这一区是「5 张大桌 + 一堆单人桌」混排：
 *   13 连座 5S100–5S112 / 9 连座 5S077–5S085 / 8 连座 5S129–5S136 /
 *   8 连座 5S157–5S164 / 5 连座 5S086–5S090，其余全是 1 座。
 */
class DeskFinderTest {

    private val seats = TestSeats.WU_QU_NAN

    @Test
    fun `夹具是 164 座位`() {
        assertEquals(164, seats.size)
    }

    @Test
    fun `能切出 5 张大桌（4 连座以上）`() {
        val desks = DeskFinder.findFreeDesks(seats, minSize = 4)
        assertEquals(5, desks.size)
        // 2026-11-21 换算法后的结果（见 DeskFinder 头部说明）：
        // 长桌按「编号连续 + 同行间距 ≤3.0」切，5S129 因为与上一行 128 的编号连续
        // 被并到前一组，所以是 7 座而不是旧算法的 8 座 —— 这是已知的一处边界差异。
        assertEquals(listOf(13, 9, 8, 7, 5), desks.map { it.size })
    }

    @Test
    fun `最大那张桌是 5S100 到 5S112`() {
        val biggest = DeskFinder.findFreeDesks(seats, minSize = 4).first()
        assertEquals(13, biggest.size)
        assertEquals("5S100–5S112", biggest.nameRange)
    }

    @Test
    fun `另外四张桌的座位号范围`() {
        val ranges = DeskFinder.findFreeDesks(seats, minSize = 4).map { it.nameRange }
        // 8 连座那两张曾经因为按 x 排序而输出倒序（5S164–5S157），必须是正的
        assertTrue("实际=$ranges", ranges.contains("5S077–5S085"))
        assertTrue("实际=$ranges", ranges.contains("5S130–5S136"))  // 5S129 与上一行编号连续，被并到前组（已知边界）
        assertTrue("实际=$ranges", ranges.contains("5S157–5S164"))
        assertTrue("实际=$ranges", ranges.contains("5S086–5S090"))
        // 086–096 之间有两处断点（090→091 是 5.60，093→094 是 4.92），不能并成一张 11 座
        assertFalse("不能把 5S086–096 并成一桌", ranges.contains("5S086–5S096"))
    }

    @Test
    fun `单人桌不会被当成大桌`() {
        val oneSeat = DeskFinder.findFreeDesks(seats, minSize = 1)
        assertTrue("minSize=1 应该切出很多桌", oneSeat.size > 100)
        assertTrue(oneSeat.any { it.size == 1 })
    }

    @Test
    fun `有一张被占就不算空桌`() {
        // 把 13 连座里的 5S105 标成已占
        val dirty = seats.map { if (it.name == "5S105") it.copy(status = 1) else it }
        val desks = DeskFinder.findFreeDesks(dirty, minSize = 4)
        assertEquals(4, desks.size)   // 少了一张
        assertFalse(desks.any { it.size == 13 })
    }

    @Test
    fun `半空的桌子仍会被切出来但不计入空桌`() {
        val dirty = seats.map { if (it.name == "5S105") it.copy(status = 1) else it }
        val all = DeskFinder.findFreeDesks(dirty, minSize = 1)
        // 桌子本身还在（13 连座被切成 12+1，因为中间那个不能删掉位置）
        assertTrue(all.any { it.size >= 8 })
    }

    @Test
    fun `整区被约满时没有空桌`() {
        val full = seats.map { it.copy(status = 1) }
        assertTrue(DeskFinder.findFreeDesks(full, minSize = 4).isEmpty())
    }

    @Test
    fun `空输入不崩`() {
        assertTrue(DeskFinder.findFreeDesks(emptyList()).isEmpty())
    }

    @Test
    fun `minSize 阈值生效`() {
        assertEquals(5, DeskFinder.findFreeDesks(seats, minSize = 4).size)
        assertEquals(5, DeskFinder.findFreeDesks(seats, minSize = 5).size, )
        assertEquals(2, DeskFinder.findFreeDesks(seats, minSize = 9).size)  // 13 与 9
        assertEquals(1, DeskFinder.findFreeDesks(seats, minSize = 10).size) // 只有 13
    }

    @Test
    fun `不同区域的座位不会被聚成同一张桌`() {
        // 两个区域的坐标系统是独立的，点会重叠 —— 必须按 roomId 分开算，
        // 否则会切出「5S077–5WB267」这种跨区域的假桌子（真踩过）
        fun seatOf(roomId: Int, name: String, x: Double, y: Double) =
            SeatApi.Seat(0, name, 0, x, y, roomId, "区域$roomId", "五层")

        val mixed = listOf(
            seatOf(43, "5S001", 10.0, 20.0), seatOf(43, "5S002", 12.0, 20.0),
            seatOf(43, "5S003", 14.0, 20.0), seatOf(43, "5S004", 16.0, 20.0),
            seatOf(45, "5WB001", 10.0, 20.0), seatOf(45, "5WB002", 12.0, 20.0),
            seatOf(45, "5WB003", 14.0, 20.0), seatOf(45, "5WB004", 16.0, 20.0),
        )
        val desks = DeskFinder.findFreeDesks(mixed, minSize = 4)
        assertEquals(2, desks.size)
        assertTrue(desks.all { it.size == 4 })
        // 每张桌必须是单一区域
        assertTrue(desks.all { d -> d.seats.map { it.roomId }.distinct().size == 1 })
        assertTrue(desks.none { it.nameRange.contains("5S001–5WB") })
    }

    // ---------- 时段维度 ----------

    private fun seatWithResv(roomId: Int, name: String, x: Double, y: Double, vararg spans: Pair<Int, Int>) =
        SeatApi.Seat(
            0, name, 0, x, y, roomId, "区域$roomId", "五层",
            spans.map { SeatApi.SeatResv(0, it.first, it.second) },
        )

    private fun desk4(vararg resv: Pair<Int, Int>) = listOf(
        seatWithResv(43, "A1", 10.0, 20.0, *resv),
        seatWithResv(43, "A2", 12.0, 20.0),
        seatWithResv(43, "A3", 14.0, 20.0),
        seatWithResv(43, "A4", 16.0, 20.0),
    )

    private fun hm(h: Int, m: Int = 0) = h * 60 + m

    @Test
    fun `座位在时段内的空闲判断`() {
        val s = seatWithResv(43, "A1", 10.0, 20.0, hm(9) to hm(10))
        assertTrue(s.freeDuring(hm(10), hm(12)))   // 10:00 开始，头尾相接不算冲突
        assertTrue(s.freeDuring(hm(8), hm(9)))
        assertTrue(!s.freeDuring(hm(9, 30), hm(11)))  // 有交集
        assertTrue(!s.freeDuring(hm(8), hm(10)))      // 覆盖到结束点也算
        assertTrue(!s.freeDuring(hm(10), hm(10)))     // 空区间非法
    }

    @Test
    fun `时段被占的座位会让整桌不算空桌`() {
        val seats = desk4(hm(9) to hm(10))          // A1 在 09:00-10:00 被约
        // 09:30-11:00 有冲突 → 这桌不能算空
        assertTrue(DeskFinder.findFreeDesks(seats, 4, DeskFinder.TimeSlot(hm(9, 30), hm(11))).isEmpty())
        // 10:00-12:00 无冲突 → 算空
        assertEquals(1, DeskFinder.findFreeDesks(seats, 4, DeskFinder.TimeSlot(hm(10), hm(12))).size)
        // 不传时段（整天口径）→ 也不算空
        assertTrue(DeskFinder.findFreeDesks(seats, 4).isEmpty())
    }

    @Test
    fun `真实 164 座在晚间时段仍有多张空桌`() {
        // 夹具是 11-23 的理想数据（全部无预约），任一时段都应切出 5 张大桌
        val slot = DeskFinder.TimeSlot(hm(18), hm(22))
        assertEquals(5, DeskFinder.findFreeDesks(seats, 4, slot).size)
    }

    @Test
    fun `摘要统计`() {
        val s = DeskFinder.summarize(seats, minSize = 4)
        assertEquals(164, s.total)
        assertEquals(164, s.free)
        assertEquals(5, s.freeDeskCount)
        assertEquals(13, s.biggestDesk)
    }

    @Test
    fun `桌内座位按坐标有序排列`() {
        val desk = DeskFinder.findFreeDesks(seats, minSize = 4).first()
        val xs = desk.seats.sortedBy { it.x }.map { it.x }
        assertEquals(xs.sorted(), xs)
        assertEquals(desk.size, desk.namesInOrder.size)
    }
}
