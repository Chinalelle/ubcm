package com.kang.ubcm

import org.json.JSONObject

/**
 * 图书馆预约服务平台（kjyy.bucm.edu.cn，Information Commons / 新开普）
 *
 * 认证：CAS 单点 → 发 ic-cookie
 * 接口前缀：/ic-web
 * 响应：{"code":0,"message":"查询成功","data":...}
 *   code 0=成功 1=参数缺失 302=权限不足
 */
object SeatApi {

    const val BASE = "https://kjyy.bucm.edu.cn"

    data class Login(val ok: Boolean, val cookie: String? = null, val log: String = "")

    /** 用 CASTGC 走完 CAS，拿 ic-cookie */
    fun login(castgc: String): Login {
        val log = StringBuilder()
        var url = "$BASE/"
        var cookie = "CASTGC=$castgc"
        var ic: String? = null
        repeat(6) { hop ->
            val r = Net.get(url, cookie = cookie, timeoutMs = 15000)
            log.append("[$hop] ${r.http} ${url.take(80)}\n")
            r.setCookie.forEach { c ->
                val n = c.substringBefore("=")
                if (n == "ic-cookie") ic = c.substringAfter("=").substringBefore(";")
            }
            val loc = r.location
            if (loc.isNullOrBlank() || r.http !in 300..399) {
                return Login(ic != null, ic, log.toString())
            }
            url = if (loc.startsWith("http")) loc else "$BASE$loc"
        }
        return Login(ic != null, ic, log.append("跳转过多").toString())
    }

    /** "2026-11-21 08:30:00" → 510（当天分钟数） */
    private fun parseDateTimeMin(s: String?): Int? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return null
        val timePart = t.substringAfter(' ', t)
        val hm = timePart.split(":")
        if (hm.size < 2) return null
        val h = hm[0].toIntOrNull() ?: return null
        val m = hm[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /**
     * 预约座位（写操作）。
     *
     * 从页面真实逻辑（`SeatReserve.handleSubmit` + `sendData`）反推的请求体：
     * ```json
     * { appAccNo, memberKind:1, resvDev:["2553"], resvMember:[appAccNo],
     *   resvProperty:0, sysKind:8, resvBeginTime:"yyyy-MM-dd HH:mm:00",
     *   resvEndTime:"yyyy-MM-dd HH:mm:00", memo:"" }
     * ```
     * ⚠️ `resvDev` 必须是**字符串数组**；传数字会被当成"请求参数错误"。
     * ⚠️ 学校的 `sysConfig.resvCode` 实测为 `"0"`，即**不需要图形/滑块验证码**；
     *    只有 resvCode 为 "1"（图形）或 "2"（滑块）时前端才弹验证。
     */
    fun reserve(
        cookie: String,
        appAccNo: Int,
        devId: Int,
        beginTime: String,
        endTime: String,
        memberKind: Int = 1,
        sysKind: Int = 8,
        memo: String = "",
    ): Net.Resp = Net.postJsonAbs(
        "$BASE/ic-web/reserve/seat",
        """{"appAccNo":$appAccNo,"memberKind":$memberKind,"resvDev":["$devId"],""" +
            """"resvMember":[$appAccNo],"resvProperty":0,"sysKind":$sysKind,""" +
            """"resvBeginTime":"$beginTime","resvEndTime":"$endTime","memo":"$memo"}""",
        cookie = "ic-cookie=$cookie",
    )

    /** 当前账号的 accNo（预约接口要它，和学号不是一回事） */
    /**
     * 这个 ic-cookie **真的能用吗**。
     *
     * 光看 cookie 罐里有没有值是靠不住的：WebView 里可能残留上一次登录留下的
     * `ic-cookie`，长度一样是 36，但它早就失效了（服务端返回 `code:300 用户未登录`）。
     * 2026-11-22 真机就是抓到了这种残留值，存下来一用全废。
     */
    fun validCookie(cookie: String): Boolean = try {
        val r = Net.get("$BASE/ic-web/auth/userInfo", cookie = "ic-cookie=$cookie", timeoutMs = 15000)
        r.body.contains("\"code\":0")
    } catch (e: Exception) {
        false
    }

    fun accountAccNo(cookie: String): Int? = try {
        val r = Net.get("$BASE/ic-web/auth/userInfo", cookie = "ic-cookie=$cookie")
        val o = JSONObject(r.body).optJSONObject("data")
        o?.optInt("accNo")?.takeIf { it > 0 }
            ?: o?.optInt("appAccNo")?.takeIf { it > 0 }
    } catch (e: Exception) {
        null
    }

    /**
     * 某个区域某天的**全部座位**（含图上坐标）。
     *
     * 参数名是复数形式，且是从前端 `SeatReserve.handleGetSeat()` 里读出来的：
     * `{ roomIds, resvDates, sysKind: 8, devIds }`。
     * 实测 `roomIds` 只接受单个值，传逗号会报「请求参数错误」。
     */
    fun seatMap(cookie: String, dateYmd: String, roomId: Int): Net.Resp =
        Net.get(
            "$BASE/ic-web/reserve?resvDates=$dateYmd&roomIds=$roomId&sysKind=8",
            cookie = "ic-cookie=$cookie",
        )

    /** 座位上的某一段已有预约 */
    data class SeatResv(
        val resvId: Int,
        /** 当天分钟数 */
        val startMin: Int,
        val endMin: Int,
    )

    /** 座位。coordinate 是图上百分比坐标（"12.34,84.05"）。 */
    data class Seat(
        val devId: Int,
        val name: String,
        /** 0 = 可约（实测未来日期全是 0）；非 0 视为已被占 */
        val status: Int,
        val x: Double,
        val y: Double,
        val roomId: Int,
        val roomName: String,
        val labName: String,
        /** 当天的已有预约时段（`resvInfo`） */
        val reservations: List<SeatResv> = emptyList(),
    ) {
        /** 当天没人约 */
        val free: Boolean get() = reservations.isEmpty() && status == 0

        /**
         * 在 [startMin, endMin) 这个时段内是否可坐。
         * 只要与任一已有预约**有交集**就不能坐（区间半开，头尾相接不算冲突）。
         */
        fun freeDuring(startMin: Int, endMin: Int): Boolean {
            if (startMin >= endMin) return false
            if (reservations.isEmpty()) return status == 0
            return reservations.none { r -> startMin < r.endMin && r.startMin < endMin }
        }
    }

    fun parseSeats(json: String): List<Seat> = try {
        val arr = JSONObject(json).optJSONArray("data") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val c = (o.optString("coordinate") ?: "").split(",")
            if (c.size < 2) return@mapNotNull null
            val x = c[0].trim().toDoubleOrNull() ?: return@mapNotNull null
            val y = c[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            val resvArr = o.optJSONArray("resvInfo")
            val resvs = if (resvArr == null) emptyList() else (0 until resvArr.length()).mapNotNull { k ->
                val r = resvArr.optJSONObject(k) ?: return@mapNotNull null
                val b = parseDateTimeMin(r.optString("startTime")) ?: return@mapNotNull null
                val e = parseDateTimeMin(r.optString("endTime")) ?: return@mapNotNull null
                SeatResv(r.optInt("resvId"), b, e)
            }
            Seat(
                devId = o.optInt("devId"),
                name = o.optString("devName"),
                status = o.optInt("devStatus", 0),
                x = x,
                y = y,
                roomId = o.optInt("roomId"),
                roomName = o.optString("roomName"),
                labName = o.optString("labName"),
                reservations = resvs,
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * 我的预约记录。
     * 实测参数：`beginDate`/`endDate` 是 yyyy-MM-dd，`pageNum` 在这套接口里当"每页条数"用（999 = 拿全部）。
     * 返回按 resv_begin_time 倒序。
     */
    fun reservations(cookie: String, daysBack: Long = 60, daysAhead: Long = 30): Net.Resp {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val today = java.time.LocalDate.now()
        val b = fmt.format(today.minusDays(daysBack))
        val e = fmt.format(today.plusDays(daysAhead))
        return Net.get(
            "$BASE/ic-web/reserve/resvInfo?beginDate=$b&endDate=$e&page=1&pageNum=999" +
                "&orderKey=resv_begin_time&orderModel=desc",
            cookie = "ic-cookie=$cookie",
        )
    }

    /** 一条预约。时间已转成北京时间字符串。 */
    data class Resv(
        val resvId: Int,
        val date: String,
        val beginAt: java.time.LocalDateTime?,
        val endAt: java.time.LocalDateTime?,
        val checkAt: String?,
        val seatName: String,
        val roomName: String,
        val labName: String,
        val statusCode: Int,
    ) {
        /**
         * 状态文案不靠 1217 之类的码去猜（只有一种样本，猜错就是骗人），
         * 而是用「有没有签到/有没有结束/时间关系」推。
         */
        fun statusText(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): String = when {
            endAt == null -> "未知"
            !endAt.isAfter(now) -> "已结束"
            checkAt != null && beginAt != null && !beginAt.isAfter(now) -> "使用中"
            beginAt != null && beginAt.isAfter(now) -> if (checkAt != null) "已签到" else "待签到"
            else -> "已预约"
        }

        fun isActive(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Boolean =
            endAt != null && endAt.isAfter(now)

        fun timeRange(): String {
            val b = beginAt?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "—"
            val e = endAt?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "—"
            return "$b–$e"
        }

        fun dateText(): String =
            if (date.length == 8) "${date.substring(4, 6)}-${date.substring(6, 8)}" else date
    }

    /** 服务端时间带 +00:00，要转成北京时间再展示 */
    private fun parseUtc(s: String?): java.time.LocalDateTime? = try {
        if (s.isNullOrBlank()) null
        else java.time.OffsetDateTime.parse(s)
            .atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
            .toLocalDateTime()
    } catch (e: Exception) {
        null
    }

    fun parseReservations(json: String): List<Resv> = try {
        val arr = JSONObject(json).optJSONArray("data") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val dev = o.optJSONArray("resvDevInfoList")?.optJSONObject(0)
            Resv(
                resvId = o.optInt("resvId"),
                date = o.optString("resvDate"),
                beginAt = parseUtc(o.optString("resvBeginTime")),
                endAt = parseUtc(o.optString("resvEndTime")),
                checkAt = o.optString("resvCheckTime").takeIf { it.isNotBlank() && it != "null" },
                seatName = dev?.optString("devName").orEmpty(),
                roomName = dev?.optString("roomName").orEmpty(),
                labName = dev?.optString("labName").orEmpty(),
                statusCode = o.optInt("resvStatus"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun get(ep: String, cookie: String, webvpnCookie: String? = null): RouteNet.Result =
        RouteNet.get("$BASE/ic-web/$ep", cookie = "ic-cookie=$cookie", webvpnCookie = webvpnCookie)

    // ---------- 解析 ----------

    data class Area(val id: Int, val name: String, val total: Int, val remain: Int, val children: List<Area>)

    data class Menu(val areas: List<Area>, val available: Int = 0)

    fun parseSeatMenu(json: String): Menu? = try {
        val o = JSONObject(json)
        val arr = o.optJSONArray("data") ?: return null
        val areas = (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            val ch = a.optJSONArray("children")
            Area(
                id = a.optInt("id"),
                name = a.optString("name"),
                total = a.optInt("totalCount"),
                remain = a.optInt("remainCount"),
                children = if (ch == null) emptyList() else (0 until ch.length()).mapNotNull { j ->
                    ch.optJSONObject(j)?.let {
                        Area(
                            it.optInt("id"), it.optString("name"),
                            it.optInt("totalCount"), it.optInt("remainCount"), emptyList(),
                        )
                    }
                },
            )
        }
        Menu(areas)
    } catch (e: Exception) {
        null
    }

    fun parseStatus(json: String): Pair<Int, Int>? = try {
        val d = JSONObject(json).optJSONObject("data") ?: return null
        d.optInt("available") to d.optInt("used")
    } catch (e: Exception) {
        null
    }

    /**
     * 违约记录。实测当前为空数组 `data: []`，字段名未见过（没违约过），
     * 所以这里做防御式解析：能取到描述文字就取，取不到就退化成整条 JSON 摘要。
     */
    fun parsePunish(json: String): List<String> = try {
        val arr = JSONObject(json).optJSONArray("data") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i)
            if (o == null) {
                arr.optString(i).takeIf { it.isNotBlank() }
            } else {
                listOf(
                    "punishReason", "reason", "content", "desc",
                    "violationDesc", "remark", "ruleName",
                ).firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() } }
                    ?: o.toString().take(100)
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun code(json: String): Int = try {
        JSONObject(json).optInt("code", -1)
    } catch (e: Exception) {
        -1
    }
}
