package com.kang.ubcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空教室 HTML 解析（宽松模式）。
 *
 * 真实结构未实测，所以解析器只认「一行一个教室 + 行内有空闲/有课标记」这种最常见排布；
 * 认不出来返回空列表，页面显示"暂无数据"，绝不抛异常。
 */
class EmptyRoomParseTest {

    @Test
    fun `一行一个教室时能识别空闲与占用`() {
        val html = """
            <html><body>
            <table>
              <tr><th>教室</th><th>状态</th></tr>
              <tr><td>良东101</td><td>空闲</td></tr>
              <tr><td>良东102</td><td>中医基础理论</td></tr>
              <tr><td>良西203</td><td>无课</td></tr>
            </table>
            </body></html>
        """.trimIndent()
        val rooms = JwApi.parseEmptyRooms(html)
        assertEquals(3, rooms.size)
        assertTrue(rooms.first { it.room == "良东101" }.free)
        assertFalse(rooms.first { it.room == "良东102" }.free)
        assertTrue(rooms.first { it.room == "良西203" }.free)
        assertEquals("良东", rooms.first { it.room == "良东101" }.building)
    }

    @Test
    fun `登录页或空表格返回空列表`() {
        assertEquals(0, JwApi.parseEmptyRooms("").size)
        assertEquals(0, JwApi.parseEmptyRooms("<html><body>请登录</body></html>").size)
        assertEquals(
            0,
            JwApi.parseEmptyRooms("<table><tr><td>序号</td><td>名称</td></tr></table>").size,
        )
    }

    @Test
    fun `没有状态列时空白视为空闲`() {
        val html = "<table><tr><td>良东301</td><td></td></tr></table>"
        val rooms = JwApi.parseEmptyRooms(html)
        assertEquals(1, rooms.size)
        assertTrue(rooms[0].free)
    }
}
