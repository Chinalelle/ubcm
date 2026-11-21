package com.kang.ubcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面聚类 —— 二层北区 / 三层南区（**垂直桌**摆法）。
 *
 * 背景（2026-11-21，用户对照学校座位图指出的规则）：
 * > 一个绿色矩形算一张桌子；与桌子相邻的圆点算一个座位。
 * > 三层南区红框那种是「两个四座大桌拼成的八座大桌」，二层北区红框那种是「六座大桌」。
 *
 * 而学校数据里**只有座位点、没有矩形**，所以只能从座位推。旧算法（同排 + 间距 ≤2.4）
 * 在这两个区域完全错了：同桌两个座位的 x 间距是 3.73 / 4.0–4.4（跨过中间那张桌子），
 * 而相邻两桌只有 2.15 —— 桌间距离比同桌还小。
 *
 * 新算法靠**座位编号连续性**定桌界，实测对得上座位图。
 */
class DeskFinderVerticalTest {

    private val san = TestDesks.SAN_CENG_NAN
    private val er = TestDesks.ER_CENG_BEI

    private fun ranges(seats: List<SeatApi.Seat>) =
        DeskFinder.findFreeDesks(seats, minSize = 4).map { it.nameRange }

    // ---------- 三层南区：八座大桌 ----------

    @Test
    fun `三层南区能切出八座大桌`() {
        // 3S062–069 / 070–077 / 078–085 都是「两个四座拼成」的八座大桌
        val r = ranges(san)
        assertTrue("实际=$r", r.contains("3S062–3S069"))
        assertTrue("实际=$r", r.contains("3S070–3S077"))
        assertTrue("实际=$r", r.contains("3S078–3S085"))
    }

    @Test
    fun `三张八座桌各自独立不能被编号串联`() {
        // 它们编号是连着的（069→070、077→078），如果只按编号分组会并成 24 座
        val desks = DeskFinder.findFreeDesks(san, minSize = 8)
        val eight = desks.filter { it.size == 8 }
        assertEquals(3, eight.size)
        assertFalse("不能出现 24 座这种合并结果", desks.any { it.size >= 16 })
    }

    @Test
    fun `八座桌是两列四行`() {
        val d = DeskFinder.findFreeDesks(san, minSize = 8).first { it.nameRange == "3S062–3S069" }
        assertEquals(8, d.size)
        assertEquals(2, d.seats.map { it.x }.toSet().size)
        assertEquals(4, d.seats.map { it.y }.toSet().size)
    }

    @Test
    fun `三层南区的十三座长桌`() {
        // 3S049–061 是「水平长桌」摆法：一行 13 个椅子
        val d = DeskFinder.findFreeDesks(san, minSize = 10).firstOrNull { it.size == 13 }
        assertTrue("要有 13 座长桌", d != null)
        assertEquals(1, d!!.seats.map { it.y }.toSet().size)
    }

    @Test
    fun `三层南区十二座是两列六行`() {
        val d = DeskFinder.findFreeDesks(san, minSize = 12).first { it.nameRange == "3S118–3S129" }
        assertEquals(12, d.size)
        assertEquals(2, d.seats.map { it.x }.toSet().size)
        assertEquals(6, d.seats.map { it.y }.toSet().size)
    }

    // ---------- 二层北区：六座大桌 ----------

    @Test
    fun `二层北区能切出六座大桌`() {
        // 2N001–006 就是用户圈的那种六座大桌（三行两列）
        val d = DeskFinder.findFreeDesks(er, minSize = 6).first { it.nameRange == "2N001–2N006" }
        assertEquals(6, d.size)
        assertEquals(2, d.seats.map { it.x }.toSet().size)
        assertEquals(3, d.seats.map { it.y }.toSet().size)
    }

    @Test
    fun `二层北区的十座桌`() {
        val d = DeskFinder.findFreeDesks(er, minSize = 10).first { it.nameRange == "2N167–2N176" }
        assertEquals(10, d.size)
        assertEquals(2, d.seats.map { it.x }.toSet().size)
        assertEquals(5, d.seats.map { it.y }.toSet().size)
    }

    @Test
    fun `二层北区的四座桌`() {
        val d = DeskFinder.findFreeDesks(er, minSize = 4).first { it.nameRange == "2N095–2N098" }
        assertEquals(4, d.size)
    }

    @Test
    fun `二层北区两列三行的桌不会被拆成两半`() {
        // 这是旧算法的核心错误：同桌间距 3.73 > 桌间 2.15，按距离切必然拆错
        val desks = DeskFinder.findFreeDesks(er, minSize = 6)
        assertTrue("六座桌应该存在（而不是被拆成两个三座）", desks.any { it.size == 6 })
        assertFalse("不应该出现只有 3 座的组", desks.any { it.size == 3 })
    }

    // ---------- 跨区域 ----------

    @Test
    fun `两个区域合在一起也不会串桌`() {
        // 每个区域的 coordinate 是各自独立的坐标系，混算会切出「3S062–2N001」这种假桌子
        val r = ranges(TestDesks.BOTH)
        assertFalse("出现跨区域的假桌子：$r", r.any { it.contains("3S") && it.contains("2N") })
    }

    @Test
    fun `合在一起时桌数等于两个区域之和`() {
        val a = DeskFinder.findFreeDesks(san, minSize = 4).size
        val b = DeskFinder.findFreeDesks(er, minSize = 4).size
        val both = DeskFinder.findFreeDesks(TestDesks.BOTH, minSize = 4).size
        assertEquals(a + b, both)
    }

    // ---------- 编号解析 ----------

    @Test
    fun `编号取末尾数字`() {
        assertEquals(167, DeskFinder.seatNumber("2N167"))
        assertEquals(62, DeskFinder.seatNumber("3S062"))
        assertEquals(267, DeskFinder.seatNumber("5WB267"))
        assertEquals(Int.MAX_VALUE, DeskFinder.seatNumber("无编号"))
        assertEquals(Int.MAX_VALUE, DeskFinder.seatNumber(""))
    }

    @Test
    fun `编号连续才并桌`() {
        val a = TestDesks.ER_CENG_BEI.first { it.name == "2N167" }
        val b = TestDesks.ER_CENG_BEI.first { it.name == "2N168" }
        val far = TestDesks.ER_CENG_BEI.first { it.name == "2N176" }
        assertTrue(DeskFinder.canJoin(listOf(a), b))
        // 176 与 167 编号不连续（中间隔着别人），不能并
        assertFalse(DeskFinder.canJoin(listOf(a), far))
    }

    @Test
    fun `坐标 0,0 的未放置座位会被丢掉`() {
        val ghost = san.first().copy(name = "3S999", x = 0.0, y = 0.0)
        val r = DeskFinder.findFreeDesks(san + ghost, minSize = 4).map { it.nameRange }
        assertFalse("幽灵座位不该进任何桌：$r", r.any { it.contains("3S999") })
    }
}
