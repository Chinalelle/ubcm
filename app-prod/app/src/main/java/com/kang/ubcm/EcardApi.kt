package com.kang.ubcm

import org.json.JSONObject

/**
 * 一卡通（新开普「一卡通服务大厅」）
 *
 * 链路：
 *   CAS(带 CASTGC) → ykt SSO → Set-Cookie: servicehall  → 后续接口
 *
 * 关键点：APP 只需要一个 CASTGC（与门户同一个 CAS），
 * 其余跳转与换票全部在客户端完成，不需要浏览器。
 */
object EcardApi {

    const val BASE = "https://ykt.bucm.edu.cn"
    private const val YKT_SERVICE = "https://ykt.bucm.edu.cn/servicehall//sso/login"

    data class LoginResult(
        val ok: Boolean,
        val session: String? = null,
        val openid: String? = null,
        val log: String = "",
    )

    /**
     * 用 CASTGC 走完 CAS 单点，拿到 servicehall 会话与 openid。
     * CAS 会连续跳 3 次（/cas → /cas/login → ykt?ticket → ykt），这里手动跟随。
     */
    fun login(castgc: String): LoginResult {
        val log = StringBuilder()
        var url = "https://cas.bucm.edu.cn/cas?service=${java.net.URLEncoder.encode(YKT_SERVICE, "UTF-8")}"
        val cookieJar = StringBuilder("CASTGC=$castgc")
        var session: String? = null

        repeat(6) { hop ->
            val r = Net.get(url, cookie = cookieJar.toString(), timeoutMs = 15000)
            log.append("[$hop] http=${r.http} url=${url.take(90)}\n")
            // 收集 Set-Cookie
            r.setCookie.forEach { c ->
                val name = c.substringBefore("=")
                if (name == "servicehall") session = c.substringAfter("=").substringBefore(";")
                if (name == "CASTGC" || name == "JSESSIONID" || name == "servicehall") {
                    val v = c.substringAfter("=").substringBefore(";")
                    cookieJar.append("; $name=$v")
                }
            }
            val loc = r.location
            if (loc.isNullOrBlank() || r.http !in 300..399) {
                // 落地页，找 openid
                val openid = extractOpenid(r.body)
                log.append("落地 http=${r.http} len=${r.body.length} openid=${openid ?: "未找到"}\n")
                return LoginResult(
                    ok = session != null,
                    session = session,
                    openid = openid,
                    log = log.toString(),
                )
            }
            url = if (loc.startsWith("http")) loc else "https://cas.bucm.edu.cn$loc"
        }
        return LoginResult(false, session, null, log.append("重定向过多").toString())
    }

    /**
     * openid 在页面的隐藏字段里（dkywwachat.js 用 $("#openid").val() 取）：
     *   <input id="openid" value="07A7C30A...">
     * 兼容 value 在前/在后的两种写法，以及 URL 参数形式。
     */
    fun extractOpenid(html: String): String? {
        val pats = listOf(
            Regex("""id=["']openid["'][^>]*value=["']([0-9A-Fa-f]{8,})["']"""),
            Regex("""value=["']([0-9A-Fa-f]{8,})["'][^>]*id=["']openid["']"""),
            Regex("""openid=([0-9A-Fa-f]{8,})"""),
        )
        for (p in pats) p.find(html)?.groupValues?.get(1)?.let { return it }
        return null
    }

    /**
     * 流水查询（含余额）。
     * tradetype: -1 全部 / 1 消费 / 2 充值 / 3 补助 / 4 转账
     */
    fun trades(
        session: String,
        openid: String,
        idserial: String,
        start: String,
        end: String,
        page: Int = 0,
        size: Int = 20,
    ): Net.Resp {
        val body = """{"starttime":"$start","endtime":"$end","tradetype":"-1","datetype":"1","pageNumber":$page,"pageSize":$size,"idserial":"$idserial"}"""
        return Net.postJsonAbs(
            "$BASE/servicehall/business/thirdQuerSelfTrade?openid=$openid",
            body,
            cookie = "servicehall=$session; openid=$openid",
        )
    }

    // ---------- 解析 ----------

    data class Trade(
        val merchant: String,
        val amountYuan: String,
        val balanceYuan: String,
        val type: String,
        val timeMs: Long,
    )

    data class TradePage(val total: Int, val pages: Int, val trades: List<Trade>)

    /**
     * 平台响应是加密的：{"data":"<前16位是key><密文>"}。
     * 先解一次拿到明文 JSON，再按解析。
     */
    fun unwrap(raw: String): String {
        val t = raw.trim()
        if (!t.startsWith("{")) return t
        return try {
            val o = JSONObject(t)
            val d = o.optString("data", "")
            if (d.length > 17) AesEcb.decryptSelfContained(d) ?: t else t
        } catch (e: Exception) {
            t
        }
    }

    fun parseTrades(json: String): TradePage? = try {
        val o = JSONObject(json)
        val rd = o.optJSONObject("resultData") ?: return null
        val rows = rd.optJSONArray("rows") ?: return TradePage(0, 0, emptyList())
        val list = (0 until rows.length()).mapNotNull { i ->
            val r = rows.optJSONObject(i) ?: return@mapNotNull null
            Trade(
                merchant = r.optString("mername"),
                amountYuan = fen2yuan(r.optLong("txamt", 0)),
                balanceYuan = fen2yuan(r.optLong("balance", 0)),
                type = r.optString("txname"),
                timeMs = r.optLong("txdate", 0),
            )
        }
        TradePage(rd.optInt("total", 0), rd.optInt("totalpage", 0), list)
    } catch (e: Exception) {
        null
    }

    /** 分（整数）→ 元（两位小数），负号保留 */
    fun fen2yuan(fen: Long): String {
        val neg = fen < 0
        val abs = kotlin.math.abs(fen)
        val s = "${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
        return if (neg) "-$s" else s
    }
}
