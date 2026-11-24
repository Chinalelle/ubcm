package com.kang.ubcm

import org.json.JSONArray
import org.json.JSONObject

/**
 * 门户 API 的防御式解析。
 * 实测后端有 4 套响应包装（见 docs/08-API字段格式规范.md），所以这里不能强转。
 */
object Envelope {
    data class Parsed(
        val code: Int,
        val msg: String,
        val data: Any?,          // JSONObject / JSONArray / String / Number / Boolean / null
        val shape: String,       // 便于 UI 展示"返回了什么形态"
        val raw: String,
    ) {
        val ok get() = code == 200
        val noAuth get() = code == 0
    }

    fun parse(body: String): Parsed {
        val t = body.trim()
        if (t.isEmpty()) return Parsed(-1, "空响应", null, "empty", body)
        // 形态 F：裸返回（haveRedPoint 返回 0、getToken 返回字符串）
        if (!t.startsWith("{")) {
            val shape = when {
                t == "true" || t == "false" -> "boolean(裸)"
                t == "null" -> "null(裸)"
                t.startsWith("\"") -> "string(裸)"
                else -> "number(裸)"
            }
            return Parsed(200, "成功(裸返回)", t, shape, body)
        }
        val o = try { JSONObject(t) } catch (e: Exception) {
            return Parsed(-1, "非 JSON: ${e.javaClass.simpleName}", null, "unknown", body)
        }
        val code = o.optInt("code", -1)
        // 形态 C：{code, message}（键名是 message 不是 msg）
        val msg = o.optString("msg").ifBlank { o.optString("message") }
        // 形态 E：Spring 默认错误体
        if (code == -1 && o.has("timestamp")) {
            return Parsed(o.optInt("status", -1), "框架错误 ${o.optString("error")}", null, "spring-404", body)
        }
        val data: Any? = when {
            o.has("data") -> {
                val d = o.get("data")
                if (d == JSONObject.NULL) null else d
            }
            // 形态 F：PageHelper 裸对象（没有 data 字段，直接 total/list）
            o.has("list") -> o
            else -> null
        }
        val shape = when (data) {
            null -> "null"
            is JSONObject -> if (o.has("data")) "object" else "pagehelper(裸)"
            is JSONArray -> "array(${data.length()})"
            is String -> "string"
            is Boolean -> "boolean"
            is Number -> "number"
            else -> data.javaClass.simpleName
        }
        return Parsed(code, msg, data, shape, body)
    }
}

object PortalApi {

    fun apps(token: String): Envelope.Parsed = Envelope.parse(
        Net.postJson(
            "/bucm/appmarket/getMySubscribeApp",
            """{"BELONG_UNIT_ID":"","APP_NAME":"","DIMENSIONS":"","LETTER":"","PROJECT_NAME":""}""",
            token,
            timeoutMs = 30000,
        ).body
    )

    fun messages(
        token: String,
        tab: String = "message_remind",
        page: Int = 1,
        size: Int = 10,
        /** ""=全部 / "0"=未读 / "1"=已读（语义待前端代码确认） */
        isRead: String = "",
    ): Envelope.Parsed =
        Envelope.parse(
            Net.postJson(
                "/task/getTaskInfo",
                """{"pageNum":$page,"pageSize":$size,"start_date":"","end_date":"","msg_content":"","tab_type":"$tab","type_id":"","APP_ID":"","is_read":"$isRead"}""",
                token,
            ).body
        )

    /**
     * 未读计数（首页角标用，最轻的接口：几十字节）。
     *
     * 来源：门户 H5 首页 `resource/defaults/js/h5/home.js:207` 就是这么取的，
     * 返回 `{code:200, data:{CNT: n}}`。
     *
     * tab_type：`"5"` = 待办提醒未读 / `"3"` = 系统提醒未读 / 空 = 总未读
     */
    fun unreadCount(token: String, tabType: String = "5"): Envelope.Parsed =
        Envelope.parse(
            Net.postJson(
                "/weChat/getCardCenterUnReadInfo",
                if (tabType.isBlank()) "{}" else """{"tab_type":"$tabType"}""",
                token,
                timeoutMs = 8000,
            ).body
        )

    /** 待办总数（降级用）。data.ALL_NUM + data.SERVICE_LIST[].NUM */
    fun todoServiceNum(token: String, msgType: String = "1"): Envelope.Parsed =
        Envelope.parse(
            Net.postJson(
                "/task/getServiceNum",
                """{"MSG_TYPE":"$msgType","msg_content":"","start_date":"","end_date":""}""",
                token,
            ).body
        )

    /**
     * 待办列表。tab_type：`"1"` 待受理 / `"3"` 系统消息 / `""` 已办理。
     * ⚠️ 不要用 `message_remind`：那是全校消息量级（实测 118353 条），不是个人待办。
     */
    fun todoList(token: String, tabType: String = "1", page: Int = 1, size: Int = 10): Envelope.Parsed =
        Envelope.parse(
            Net.postJson(
                "/task/getTaskInfo",
                """{"pageNum":$page,"pageSize":$size,"tab_type":"$tabType","type_id":"","msg_content":"","start_date":"","end_date":""}""",
                token,
            ).body
        )

    /** 未读待办汇总，首页用 */
    data class TodoSummary(
        val ok: Boolean,
        val noAuth: Boolean = false,
        val msg: String = "",
        val unread: Int? = null,
        val total: Int? = null,
        val latestTitle: String? = null,
        /** 未读数来自哪个接口，便于排查 */
        val source: String = "",
    )

    fun todoSummary(token: String): TodoSummary {
        // 1) 主路径：专门的未读计数接口
        val r = unreadCount(token, "5")
        var unread: Int? = null
        var source = ""
        if (r.ok) {
            unread = (r.data as? JSONObject)?.optInt("CNT", -1)?.takeIf { it >= 0 }
            if (unread != null) source = "getCardCenterUnReadInfo"
        } else if (r.noAuth) {
            return TodoSummary(ok = false, noAuth = true, msg = "登录已过期")
        }

        // 2) 降级：待办服务总数
        var total: Int? = null
        if (unread == null) {
            val sn = todoServiceNum(token)
            if (sn.ok) {
                total = (sn.data as? JSONObject)?.optInt("ALL_NUM", -1)?.takeIf { it >= 0 }
                unread = total
                if (total != null) source = "getServiceNum(降级)"
            }
        }

        // 3) 最近一条待办标题（拿不到不影响未读数）
        val latest = try {
            val list = todoList(token, tabType = "1", page = 1, size = 1)
            val d = list.data as? JSONObject
            val first = d?.optJSONArray("list")?.optJSONObject(0)
            first?.optString("MSG_TITLE")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }

        return TodoSummary(
            ok = unread != null,
            msg = if (unread == null) r.msg.ifBlank { "code=${r.code}" } else "",
            unread = unread,
            total = total,
            latestTitle = latest,
            source = source,
        )
    }

    fun campusInfo(token: String, page: Int = 1, size: Int = 5): Envelope.Parsed = Envelope.parse(
        Net.postJson(
            "/bucm/campusBucm/getBnuCampusPimPageInfo",
            """{"pageNum":$page,"pageSize":$size}""", token,
        ).body
    )

    fun weather(token: String): Envelope.Parsed =
        Envelope.parse(Net.postJson("/card/weather/getWeatherAndDate", "{}", token).body)

    fun services(token: String): Envelope.Parsed =
        Envelope.parse(Net.postJson("/bucm/fw/getLevelOneData", """{"type":"1"}""", token, 30000).body)

    fun askList(token: String, page: Int = 1, size: Int = 5): Envelope.Parsed =
        Envelope.parse(Net.postJson("/bucm/ask/getTotalObjList", """{"pageNum":$page,"pageSize":$size}""", token).body)

    fun units(token: String): Envelope.Parsed =
        Envelope.parse(Net.postJson("/bucm/fw/getUnitType", "{}", token).body)
}

/**
 * 校园卡相关地址探测。
 * 已知结论：门户里没有自助入口，唯一入口是微信企业号 e卡通。
 * 这里逐个试探公开端点，把真实结果记录下来（而不是假设"没有"）。
 */
object CardProbe {
    val candidates = listOf(
        "https://ykt.bucm.edu.cn/",
        "http://ykt.bucm.edu.cn/",
        "https://card.bucm.edu.cn/",
        "https://ecard.bucm.edu.cn/",
        "https://jiaofei.bucm.edu.cn/",
    )

    fun probe(): List<Triple<String, String, String>> =
        candidates.map { u ->
            val r = try { Net.get(u, timeoutMs = 8000) } catch (e: Exception) {
                Net.Resp(-1, e.message ?: "err", 0)
            }
            Triple(u, "HTTP ${r.http}", if (r.body.isBlank()) "(空)" else r.body.replace("\n", " ").take(110))
        }
}
