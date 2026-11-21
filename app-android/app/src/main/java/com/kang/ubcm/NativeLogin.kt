package com.kang.ubcm

/**
 * 用 CASTGC 把其余凭证**纯 native** 换齐 —— 不打开任何网页。
 *
 * 学校四个目标都在同一个 CAS 下，所以整条链路只需一个 CASTGC：
 * ```
 * 门户  i.bucm.edu.cn/tp_nup/   → accessToken（cookie）
 * 教务  jw.bucm.edu.cn/         → JSESSIONID 等（整串）
 * 一卡通 ykt.bucm.edu.cn         → servicehall + openid（EcardApi 已有，这里复用）
 * 座位  kjyy.bucm.edu.cn         → ic-cookie（要先 doAuth 预热，见 docs/13 §3.1）
 * ```
 *
 * 每换一条都顺手落盘，**这样即使用户中途退出，已拿到的不会白扔**。
 */
object NativeLogin {

    const val PORTAL_SERVICE = CasLogin.PORTAL_SERVICE
    const val JW_SERVICE = "https://jw.bucm.edu.cn/"
    const val SEAT_BASE = "https://kjyy.bucm.edu.cn"

    data class Harvest(
        val cookies: Map<String, String>,
        val finalUrl: String,
        val log: String,
    )

    /**
     * 带 CASTGC 走完 CAS 重定向，收集沿途所有 `Set-Cookie`。
     *
     * Cookie 不按域隔离（都发给同一批学校域名）。这不理想但无害：
     * 各系统只认自己域下的 cookie 名，别的域发过去也会被忽略。
     */
    fun follow(castgc: String, service: String, maxHops: Int = 8): Harvest {
        val jar = LinkedHashMap<String, String>()
        jar["CASTGC"] = castgc
        var url = "https://cas.bucm.edu.cn/cas?service=" +
            java.net.URLEncoder.encode(service, "UTF-8")
        val log = StringBuilder()
        var finalUrl = url

        for (hop in 0 until maxHops) {
            val cookieHeader = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val r = Net.get(url, cookie = cookieHeader, timeoutMs = 20000)
            log.append("[$hop] http=${r.http} ${url.take(90)}\n")
            // 收集这一跳下发的 cookie
            for (c in r.setCookie) {
                val name = c.substringBefore("=").trim()
                val value = c.substringAfter("=").substringBefore(";").trim()
                if (name.isNotEmpty() && value.isNotEmpty()) jar[name] = value
            }
            finalUrl = url
            val loc = r.location
            if (loc.isNullOrBlank() || r.http !in 300..399) {
                log.append("    落地 ${r.http} len=${r.body.length}\n")
                return Harvest(jar, finalUrl, log.toString())
            }
            url = if (loc.startsWith("http")) loc else resolve(url, loc)
        }
        log.append("    重定向超过 $maxHops 跳\n")
        return Harvest(jar, finalUrl, log.toString())
    }

    private fun resolve(base: String, loc: String): String = try {
        java.net.URI(base).resolve(loc).toString()
    } catch (e: Exception) {
        "https://cas.bucm.edu.cn$loc"
    }

    // ---------- 各目标 ----------

    /**
     * 门户 accessToken。
     *
     * ⚠️ 这个不能靠 Set-Cookie —— 2026-11-21 实测：门户**从不下发**这个 cookie，
     * 是前端 `cas.html` 拿到 OAuth2 code 后，用 JS 自己 `$.cookie("accessToken", ...)` 写进去的。
     * 所以纯 native 必须自己走完 OAuth2 码流：
     *
     * ```
     * 1) GET cas?service=portal            → 302 → i.bucm.edu.cn/tp_nup/?ticket=ST-x
     * 2) POST apim/sems-tp-nup/home/pingcurrent?service=<上面那个 URL>
     *        → 401 + data 里就是一个完整 authorize URL
     * 3) GET <authorize>（带 CASTGC）      → 302 → tp_nup/cas.html?...&code=ST-y
     * 4) GET apim/sems-authc/oauth2/casToken/<code>/bucmnup?casDelegate=
     *        → {"code":200,"data":{"accessToken":"..."}}
     * ```
     *
     * 第 2 步如果直接 `code==200`，说明已经有会话，`data.accessToken` 直接可用。
     */
    fun portalToken(castgc: String): String? {
        val landing = follow(castgc, PORTAL_SERVICE).finalUrl
        if (!landing.contains("ticket=")) return null

        val ping = "https://i.bucm.edu.cn/apim/sems-tp-nup/home/pingcurrent?service=" +
            java.net.URLEncoder.encode(landing, "UTF-8")
        val r = try {
            Net.postJsonAbs(ping, "{}", cookie = "CASTGC=$castgc", timeoutMs = 20000)
        } catch (e: Exception) {
            return null
        }
        val obj = try {
            org.json.JSONObject(r.body)
        } catch (e: Exception) {
            return null
        }
        // 会话已存在时直接给 token
        if (obj.optInt("code") == 200) {
            return obj.optJSONObject("data")?.optString("accessToken")?.takeIf { it.isNotBlank() }
        }
        val authorize = obj.optString("data").takeIf { it.startsWith("http") } ?: return null
        val code = followAuthCode(castgc, authorize) ?: return null

        val tokenUrl = "https://i.bucm.edu.cn/apim/sems-authc/oauth2/casToken/" +
            java.net.URLEncoder.encode(code, "UTF-8") + "/bucmnup?casDelegate="
        val tr = Net.get(tokenUrl, timeoutMs = 20000)
        return try {
            org.json.JSONObject(tr.body).optJSONObject("data")?.optString("accessToken")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** 跟着 OAuth2 authorize 跳，从 Location 里抠出 code */
    private fun followAuthCode(castgc: String, authUrl: String): String? {
        var url = authUrl
        repeat(6) {
            val r = Net.get(url, cookie = "CASTGC=$castgc", timeoutMs = 20000)
            val loc = r.location
            Regex("[?&]code=([^&]+)").find(loc ?: url)?.let { return it.groupValues[1] }
            if (loc.isNullOrBlank()) return null
            url = if (loc.startsWith("http")) loc else resolve(url, loc)
        }
        return null
    }

    /**
     * 教务会话。强智需要**整串** cookie（`JSESSIONID=..; jsxsd=..; SERVERID_jwxt=..`），
     * 单独一个 JSESSIONID 不够用。
     */
    fun jwCookie(castgc: String): String? {
        val h = follow(castgc, JW_SERVICE)
        val parts = h.cookies.filterKeys {
            it == "JSESSIONID" || it == "jsxsd" || it.startsWith("SERVERID")
        }
        if (parts.isEmpty()) return null
        return parts.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 图书馆座位 `ic-cookie`。
     *
     * 已知难点（docs/13 §3.1）：`doAuth` 要求先把 redirectUrl 按 uuid 缓存到服务端，
     * 之后 CAS 才能回跳。纯 native 之前没拿到过，这里按同样流程再试一次，
     * 失败就返回 null —— **不重试、不猜**，宁可让门禁显示「缺」。
     */
    fun seatCookie(castgc: String): String? {
        val uuid = randomHex(32)
        val ext = uuid.take(10)
        val redirect = "$SEAT_BASE/ic-web//auth/token?uuid=$uuid&extInfo=$ext"
        val warm = "$SEAT_BASE/authcenter/toLoginPage?redirectUrl=" +
            java.net.URLEncoder.encode(redirect, "UTF-8")
        // 预热：让服务端把 redirectUrl 按 uuid 记下来
        Net.get(warm, timeoutMs = 15000, cookie = "CASTGC=$castgc")
        val h = follow(castgc, redirect, maxHops = 10)
        return h.cookies["ic-cookie"]?.takeIf { it.isNotBlank() }
    }

    private fun randomHex(n: Int): String {
        val bytes = ByteArray(n / 2)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ---------- 一次跑完 ----------

    data class Outcome(
        val ok: Boolean,
        /** 逐条进度（给人看） */
        val steps: List<Step>,
        val log: String,
        /**
         * 座位 `ic-cookie` 没拿到。
         *
         * 它不是靠 HTTP 能补的（见 docs/13 §3.2），只能让前端 SPA 跑一次 ——
         * 调用方看到这个标记就去起 [SilentSeatLoginActivity]（不可见 WebView）。
         */
        val needSeat: Boolean = false,
    )

    data class Step(val label: String, val ok: Boolean, val detail: String = "")

    /**
     * 假设 CASTGC 已经拿到，把四条凭证换齐。
     *
     * 每条拿到就立刻落盘：中途失败也不会白跑。
     * **不做重试** —— 学校有限流，且凭证拿不到通常是会话问题，重试只会更糟。
     */
    fun harvestAll(ctx: android.content.Context, castgc: String): Outcome {
        val steps = ArrayList<Step>()
        val log = StringBuilder()
        Session.saveCastgc(ctx, castgc)
        steps += Step("CAS 票据", true, "已保存")

        // 1. 门户 accessToken
        val token = portalToken(castgc)
        log.append("portal token=${token?.length ?: "null"}\n")
        if (token != null) Session.saveToken(ctx, token)
        steps += Step("门户 accessToken", token != null, token?.let { "${it.length} 字节" } ?: "未拿到")

        // 2. 教务会话
        val jw = jwCookie(castgc)
        log.append("jw cookie len=${jw?.length ?: 0}\n")
        if (jw != null) Session.saveJwSession(ctx, jw)
        steps += Step("教务会话", jw != null, jw?.take(40)?.plus("…") ?: "未拿到")

        // 3. 一卡通（复用既有 CAS 单点实现）
        val ec = EcardApi.login(castgc)
        log.append("ecard ok=${ec.ok} session=${ec.session?.length ?: 0} openid=${ec.openid}\n")
        if (ec.ok && ec.session != null) Session.saveEcard(ctx, ec.session, ec.openid)
        steps += Step("一卡通", ec.ok && ec.session != null, ec.openid?.let { "openid=$it" } ?: "无 openid")

        // 4. 图书馆座位（可能拿不到，见 seatCookie 注释）
        val seat = seatCookie(castgc)
        log.append("seat ic-cookie=${seat?.length ?: "null"}\n")
        if (seat != null) Session.saveSeatCookie(ctx, seat)
        steps += Step("图书馆座位", seat != null, seat?.let { "${it.length} 字节" } ?: "未拿到（可能需要网页登录一次）")

        val ok = steps.count { it.ok } >= 4
        return Outcome(ok, steps, log.toString(), needSeat = seat == null)
    }
}
