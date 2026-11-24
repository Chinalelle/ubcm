package com.kang.ubcm

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 空间预约（研讨间 / 静音仓 / 朗读亭）的接口与解析（docs/25 §4）。
 *
 * 🔴 **只读**：这里没有任何提交预约的代码。提交接口未实测，按红线必须先抓真实请求 + 三段式，
 * 本轮只做"看哪间空着"。
 *
 * 结构差异（2026-11-24 实测）：空间**没有楼层/区域**，接口直接「类型 → 房间」。
 */
object SpaceApi {

    private const val BASE = "https://kjyy.bucm.edu.cn"

    data class Kind(val id: Int, val name: String)

    data class OpenTime(val start: String, val end: String)

    data class ResvInfo(
        val begin: LocalDateTime?,
        val end: LocalDateTime?,
        /** 1093=已预约；1027/1029 疑似使用中/已签到（待确认） */
        val status: Int,
    )

    data class Room(
        val devId: Int,
        val name: String,
        val minResvTime: Int,
        val openTimes: List<OpenTime>,
        val resvInfos: List<ResvInfo>,
        /** 所属类型（研讨间/静音仓/朗读亭），接口没给时按 kindName 兜底 */
        val kindId: Int,
    )

    /** 类型列表。返回 null 表示请求失败（与"空列表"区分开）。 */
    fun kinds(cookie: String): Pair<List<Kind>, String?> {
        val r = try {
            SeatApi.get("roomMenu", cookie)
        } catch (e: Exception) {
            return emptyList<Kind>() to "${e.javaClass.simpleName}: ${e.message}"
        }
        val parsed = parseKinds(r.resp.body)
        val err = if (parsed.isEmpty()) SeatApi.code(r.resp.body).let { c ->
            if (c == 0 || c == 200) "空间类型为空" else "code=$c ${SeatApi.message(r.resp.body)}"
        } else null
        return parsed to err
    }

    /** 全部房间（接口无参数，只返回当天预约）。 */
    fun rooms(cookie: String): Pair<List<Room>, String?> {
        val r = try {
            SeatApi.get("roomDevice/roomInfos", cookie)
        } catch (e: Exception) {
            return emptyList<Room>() to "${e.javaClass.simpleName}: ${e.message}"
        }
        val parsed = parseRooms(r.resp.body)
        val err = if (parsed.isEmpty()) SeatApi.code(r.resp.body).let { c ->
            if (c == 0 || c == 200) "房间列表为空" else "code=$c ${SeatApi.message(r.resp.body)}"
        } else null
        return parsed to err
    }

    // ---------- 解析（防御式：字段名在不同响应形态下可能不同） ----------

    private fun arrOf(o: JSONObject): JSONArray? {
        o.optJSONArray("data")?.let { return it }
        o.optJSONObject("data")?.let { d ->
            d.optJSONArray("list")?.let { return it }
            d.optJSONArray("rows")?.let { return it }
        }
        o.optJSONArray("list")?.let { return it }
        return null
    }

    fun parseKinds(json: String): List<Kind> = try {
        val o = JSONObject(json)
        val arr = arrOf(o) ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val k = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = k.optInt("kindId", k.optInt("kind_id", k.optInt("id", -1)))
            val name = k.optString("kindName")
                .ifBlank { k.optString("kind_name") }
                .ifBlank { k.optString("name") }
                .let(::clean)
            if (id < 0 && name.isBlank()) null else Kind(if (id < 0) 0 else id, name.ifBlank { "类型$id" })
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun parseRooms(json: String): List<Room> = try {
        val o = JSONObject(json)
        val arr = arrOf(o) ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = r.optString("devName").ifBlank { r.optString("dev_name") }.let(::clean)
            if (name.isBlank()) return@mapNotNull null
            val opens = r.optJSONArray("openTimes")
            val openTimes = if (opens == null) emptyList() else (0 until opens.length()).mapNotNull { j ->
                val t = opens.optJSONObject(j) ?: return@mapNotNull null
                OpenTime(t.optString("openStartTime"), t.optString("openEndTime"))
            }
            val infos = r.optJSONArray("resvInfos")
            val resvInfos = if (infos == null) emptyList() else (0 until infos.length()).mapNotNull { j ->
                val t = infos.optJSONObject(j) ?: return@mapNotNull null
                ResvInfo(
                    begin = parseDateTime(t.optString("resvBeginTime")),
                    end = parseDateTime(t.optString("resvEndTime")),
                    status = t.optInt("resvStatus", -1),
                )
            }
            Room(
                devId = r.optInt("devId", r.optInt("dev_id", -1)),
                name = name,
                minResvTime = r.optInt("minResvTime", 60),
                openTimes = openTimes,
                resvInfos = resvInfos,
                kindId = r.optInt("kindId", r.optInt("kind_id", r.optInt("sysKind", -1))),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun parseDateTime(raw: String?): LocalDateTime? = try {
        val t = pushClean(raw)
        if (t.isBlank()) null else LocalDateTime.parse(t, FMT)
    } catch (e: Exception) {
        null
    }

    /**
     * 占用判定：房间的任一段预约与所选时段**重叠**即占用。
     *
     * `resvStatus` 1093/1027/1029 本轮统一当占用处理（后两个的确切语义未确认，见 DEVLOG）。
     */
    fun isOccupied(room: Room, start: LocalDateTime, end: LocalDateTime): Boolean =
        room.resvInfos.any { info ->
            val b = info.begin ?: return@any false
            val e = info.end ?: return@any false
            b < end && start < e
        }

    /** 房间是否在所选时段的可预约时间窗内（openTimes 任一覆盖即可；没有 openTimes 视为开放）。 */
    fun isOpen(room: Room, start: LocalDateTime, end: LocalDateTime): Boolean {
        if (room.openTimes.isEmpty()) return true
        return room.openTimes.any { o ->
            val s = parseHHmm(o.start) ?: return@any true
            val e = parseHHmm(o.end) ?: return@any true
            start.hour * 60 + start.minute >= s && end.hour * 60 + end.minute <= e
        }
    }

    private fun parseHHmm(raw: String?): Int? = try {
        val t = pushClean(raw)
        if (t.length < 5) null else t.substring(0, 2).toInt() * 60 + t.substring(3, 5).toInt()
    } catch (e: Exception) {
        null
    }

    private fun pushClean(raw: String?): String {
        val t = raw?.trim().orEmpty()
        return if (t == "null") "" else t
    }

    private fun clean(raw: String?): String = pushClean(raw)

    /** 一组房间按名称排序（301/302/…/414），保证网格顺序稳定。 */
    fun sorted(rooms: List<Room>): List<Room> = rooms.sortedBy { it.name }
}
