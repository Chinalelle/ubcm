package com.kang.ubcm

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * 4×2 小组件布局的纯逻辑单测（不碰 Android / org.json）。
 */
class WidgetLayoutTest {

    private fun w(id: String, type: WidgetType, col: Int, row: Int) = HomeWidget(id, type, col, row)

    @Test
    fun `默认布局合法且占 8 格`() {
        val layout = WidgetLayout.defaultLayout()
        assertEquals(5, layout.size)
        assertTrue(WidgetLayout.isValid(layout))
        assertEquals(8, WidgetLayout.occupied(layout).size)
        assertEquals(0, WidgetLayout.freeCells(layout).size)  // 8 格正好占满
    }

    @Test
    fun `默认布局各组件位置符合设计稿`() {
        val layout = WidgetLayout.defaultLayout().associateBy { it.type }
        val balance = layout.getValue(WidgetType.BALANCE)
        assertEquals(1, balance.col)
        assertEquals(1, balance.row)
        assertEquals(WidgetSize.S2x2, balance.size)

        val library = layout.getValue(WidgetType.LIBRARY)
        assertEquals(3, library.col)
        assertEquals(1, library.row)

        val room = layout.getValue(WidgetType.EMPTY_ROOM)
        assertEquals(4, room.col)
        assertEquals(1, room.row)

        val logistics = layout.getValue(WidgetType.LOGISTICS)
        assertEquals(3, logistics.col)
        assertEquals(2, logistics.row)

        val jw = layout.getValue(WidgetType.JW)
        assertEquals(4, jw.col)
        assertEquals(2, jw.row)
    }

    @Test
    fun `越界一律放不下`() {
        assertFalse(WidgetLayout.fits(w("a", WidgetType.LIBRARY, 5, 1)))
        assertFalse(WidgetLayout.fits(w("a", WidgetType.LIBRARY, 1, 3)))
        // 2×2 放在第 4 列会越界
        assertFalse(WidgetLayout.fits(w("a", WidgetType.BALANCE, 4, 1)))
        assertTrue(WidgetLayout.fits(w("a", WidgetType.LIBRARY, 4, 2)))
    }

    @Test
    fun `重叠的格子放不下`() {
        val layout = WidgetLayout.defaultLayout()
        // (2,2) 是余额的右下角，1×1 组件不能放
        assertFalse(WidgetLayout.canPlace(layout, w("x", WidgetType.LIBRARY, 2, 2)))
        // (1,2) 也是余额的
        assertFalse(WidgetLayout.canPlace(layout, w("x", WidgetType.LIBRARY, 1, 2)))
    }

    @Test
    fun `同尺寸交换位置`() {
        val layout = WidgetLayout.defaultLayout()
        // 图书馆(3,1) ↔ 后勤(3,2)：都是 1×1
        val moved = WidgetLayout.moveOrSwap(layout, "w_library", 3, 2)
        assertTrue(WidgetLayout.isValid(moved))
        assertEquals(3, moved.first { it.id == "w_library" }.col)
        assertEquals(2, moved.first { it.id == "w_library" }.row)
        assertEquals(3, moved.first { it.id == "w_logistics" }.col)
        assertEquals(1, moved.first { it.id == "w_logistics" }.row)
    }

    @Test
    fun `移到空位不产生交换`() {
        val layout = WidgetLayout.defaultLayout()
        // (1,2) 被余额占着，换成 (4,2) 有教务… 构造一个真正空的：先删掉空教室
        val without = WidgetLayout.remove(layout, "w_empty_room")
        val moved = WidgetLayout.moveOrSwap(without, "w_library", 4, 1)
        assertEquals(4, moved.first { it.id == "w_library" }.col)
        assertEquals(1, moved.first { it.id == "w_library" }.row)
        assertEquals(without.size, moved.size)
    }

    @Test
    fun `大组件压住多个小组件时拒绝移动`() {
        val layout = WidgetLayout.defaultLayout()
        // 余额(2×2) 移到 (3,1) 会同时压住 图书馆/空教室/后勤/教务 → 不动
        val moved = WidgetLayout.moveOrSwap(layout, "w_balance", 3, 1)
        assertEquals(layout, moved)
    }

    @Test
    fun `删除后添加回到空位`() {
        val layout = WidgetLayout.remove(WidgetLayout.defaultLayout(), "w_library")
        val added = WidgetLayout.add(layout, WidgetType.LIBRARY)!!
        assertTrue(WidgetLayout.isValid(added))
        assertEquals(5, added.size)
        assertEquals(1, added.count { it.type == WidgetType.LIBRARY })
    }

    @Test
    fun `同类不重复添加`() {
        val layout = WidgetLayout.defaultLayout()
        assertNull(WidgetLayout.add(layout, WidgetType.LIBRARY))
    }

    @Test
    fun `重叠布局被判非法`() {
        val broken = listOf(
            w("a", WidgetType.BALANCE, 1, 1),
            w("b", WidgetType.LIBRARY, 2, 2),
        )
        assertFalse(WidgetLayout.isValid(broken))
    }

    @Test
    fun `删空后布局合法且可再添加`() {
        var layout = WidgetLayout.defaultLayout()
        layout.forEach { layout = WidgetLayout.remove(layout, it.id) }
        assertTrue(layout.isEmpty())
        assertTrue(WidgetLayout.isValid(layout))
        val added = WidgetLayout.add(layout, WidgetType.BALANCE)!!
        assertEquals(1, added.size)
    }
}
