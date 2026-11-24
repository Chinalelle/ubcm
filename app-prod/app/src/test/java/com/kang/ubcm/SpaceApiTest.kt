package com.kang.ubcm

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空间预约的占用判定（纯逻辑）。
 *
 * 判定规则：房间任一段预约与所选时段**重叠**即占用。
 * 边界（首尾相接）不算重叠 —— 14:00–16:00 的预约不占用 16:00–18:00 的时段。
 */
class SpaceApiTest {

    private val date = java.time.LocalDate.of(2026, 11, 24)

    private fun room(vararg ranges: Pair<String, String>) = SpaceApi.Room(
        devId = 1,
        name = "301",
        minResvTime = 60,
        openTimes = listOf(SpaceApi.OpenTime("08:30", "22:00")),
        resvInfos = ranges.map { (b, e) ->
            SpaceApi.ResvInfo(
                begin = SpaceApi.parseDateTime(b),
                end = SpaceApi.parseDateTime(e),
                status = 1093,
            )
        },
        kindId = 1,
    )

    private fun t(h: Int, m: Int = 0): LocalDateTime = date.atTime(h, m)

    @Test
    fun `完全重叠算占用`() {
        val r = room("2026-11-24 14:00:00" to "2026-11-24 16:00:00")
        assertTrue(SpaceApi.isOccupied(r, t(14), t(16)))
    }

    @Test
    fun `部分重叠算占用`() {
        val r = room("2026-11-24 15:00:00" to "2026-11-24 17:00:00")
        assertTrue(SpaceApi.isOccupied(r, t(14), t(16)))
    }

    @Test
    fun `不重叠不算占用`() {
        val r = room("2026-11-24 18:00:00" to "2026-11-24 20:00:00")
        assertFalse(SpaceApi.isOccupied(r, t(14), t(16)))
    }

    @Test
    fun `首尾相接不算占用`() {
        val r = room("2026-11-24 14:00:00" to "2026-11-24 16:00:00")
        assertFalse(SpaceApi.isOccupied(r, t(16), t(18)))
        assertFalse(SpaceApi.isOccupied(r, t(12), t(14)))
    }

    @Test
    fun `没有预约的房间不占用`() {
        assertFalse(SpaceApi.isOccupied(room(), t(14), t(16)))
    }

    @Test
    fun `多段预约中任一段命中就算占用`() {
        val r = room(
            "2026-11-24 08:30:00" to "2026-11-24 10:00:00",
            "2026-11-24 19:00:00" to "2026-11-24 21:00:00",
        )
        assertFalse(SpaceApi.isOccupied(r, t(14), t(16)))
        assertTrue(SpaceApi.isOccupied(r, t(19, 30), t(20, 30)))
    }

    // 注意：SpaceApi.parseKinds/parseRooms 走 org.json，而 JVM 单测里 org.json 是 Android stub
    // （调用直接抛异常），所以解析逻辑不在单测里断言 —— 由真机截图验证（P4 已验证研讨间 24 间网格）。
}
