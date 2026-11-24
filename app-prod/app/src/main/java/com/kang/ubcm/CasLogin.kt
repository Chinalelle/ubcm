package com.kang.ubcm

/**
 * 原生 CAS 登录 —— 不打开任何网页，直接用账号密码换 CASTGC。
 *
 * 🔴 **安全约束（硬）**
 * 1. 密码只在内存里经过一次：[submit] 的入参，算完加密串就丢，**不进 Session / 不写 prefs / 不写日志**
 * 2. 除了 `cas.bucm.edu.cn`，密码相关的任何东西都不发往别处
 * 3. 不做任何形式的缓存/持久化；类里没有密码字段
 *
 * ⚠️ **风控约束（实测）**
 * 学校 CAS 有失败计数：`连续登录失败 15 次，账号将被锁定 10 分钟`（真实返回过）。
 * 所以：
 *  - **绝不自动重试**。一次失败就返回，让用户看清原因再手动决定
 *  - 失败后 `execution` 会从 `e1s1` 变成 `e1s2`，**同一份 lt/execution 不能重复提交**，
 *    必须重新拉登录页
 *
 * 加密细节见 [CasCrypto] / [DesLegacy]（前端 des.js 是个非标准 DES 变体）。
 */
object CasLogin {

    const val CAS_BASE = "https://cas.bucm.edu.cn"

    /** 门户的 CAS service（登录成功后 ticket 会回到这里） */
    const val PORTAL_SERVICE = "https://i.bucm.edu.cn/tp_nup/"

    /** 从登录页解析出来的东西 */
    data class LoginPage(
        val lt: String,
        val execution: String,
        /** 会话 cookie（JSESSIONID），lt/execution 与它绑定，必须一起带回去 */
        val cookie: String,
        /** 这次登录是否需要图形验证码 */
        val captchaRequired: Boolean,
    )

    data class Result(
        val ok: Boolean,
        val castgc: String? = null,
        /** 给用户看的中文原因 */
        val error: String? = null,
        /** 需要验证码（用户补完再提交） */
        val needsCaptcha: Boolean = false,
        /** 原始 http 码与日志，排查用 */
        val debug: String = "",
    )

    // ---------- 解析（纯函数，可单测） ----------

    /**
     * 解析 `/cas/login` 页面。
     *
     * ⚠️ 注意 `lt` 的写法在不同页面里可能是 `id="lt" name="lt" value="..."` 或
     * `name="lt" ... value="..."`，两种都要认。
     */
    fun parseLoginPage(html: String): LoginPage? {
        val lt = Regex("""name="lt"[^>]*value="([^"]+)"""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""value="(LT-[^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return null
        val execution = Regex("""name="execution"[^>]*value="([^"]+)"""")
            .find(html)?.groupValues?.get(1) ?: "e1s1"
        return LoginPage(
            lt = lt,
            execution = execution,
            cookie = "",
            captchaRequired = requiresCaptcha(html),
        )
    }

    /**
     * 这次登录页是否要求图形验证码。
     *
     * 实测的账号登录表单（2026-11-21）**没有**验证码输入框 —— `/cas/code` 那个是
     * 「手机号登录」方式用的。所以默认 false；只有页面里明确出现账号表单的
     * 验证码提示（`errormsghide` 说需要、或出现 captcha 输入）才算 true。
     */
    fun requiresCaptcha(html: String): Boolean {
        if (html.contains("请输入验证码") || html.contains("验证码错误")) return true
        // sendConfirm 属于手机登录方式，不能作为账号登录要求验证码的依据
        return false
    }

    /**
     * 从失败页里抠出服务端的中文提示。
     *
     * 真实响应里有：
     * `<span id="errormsghide" ...>连续登录失败15次，账号将被锁定10分钟，剩余次数15</span>`
     */
    fun parseError(html: String): String? {
        val m = Regex("""id="errormsghide"[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL).find(html)
        if (m != null) {
            val txt = m.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
            if (txt.isNotEmpty()) return txt
        }
        val words = listOf(
            "用户名或密码错误", "账号或密码错误", "密码错误", "账号不存在",
            "账号已被锁定", "验证码错误", "验证码不能为空",
        )
        return words.firstOrNull { html.contains(it) }
    }

    /** 从响应里找 CASTGC（可能在 Set-Cookie，也可能在 body 里被重写过） */
    fun findCastgc(resp: Net.Resp): String? =
        resp.setCookie.firstOrNull { it.startsWith("CASTGC=") }
            ?.substringAfter("=")?.substringBefore(";")

    // ---------- 流程 ----------

    /** 步骤 1：拉登录页，拿 lt / execution / JSESSIONID */
    fun fetchLoginPage(service: String = PORTAL_SERVICE, token: String? = null): LoginPage? {
        val enc = java.net.URLEncoder.encode(service, "UTF-8")
        val r = Net.get("$CAS_BASE/cas/login?service=$enc", timeoutMs = 20000)
        if (r.http !in 200..299) return null
        val page = parseLoginPage(r.body) ?: return null
        val jar = r.setCookie.joinToString("; ") {
            val n = it.substringBefore("=").trim()
            "$n=${it.substringAfter("=").substringBefore(";")}"
        }
        return page.copy(cookie = jar)
    }

    /**
     * 步骤 2：提交账号密码，换 CASTGC。
     *
     * 🔴 密码参数 `password` 用完即丢，本函数不保留任何副本。
     *
     * @param captcha 需要验证码时用户输入的值（当前账号登录通常不需要）
     */
    fun submit(
        username: String,
        password: String,
        page: LoginPage,
        captcha: String? = null,
        service: String = PORTAL_SERVICE,
    ): Result {
        val enc = java.net.URLEncoder.encode(service, "UTF-8")
        val sealed = CasCrypto.seal(username, password, page.lt)

        // 表单字段完全照登录页的 name 来（见 login10.js:153-155）
        val form = buildString {
            append("rsa=").append(sealed.rsa)
            append("&ul=").append(sealed.ul)
            append("&pl=").append(sealed.pl)
            append("&lt=").append(java.net.URLEncoder.encode(page.lt, "UTF-8"))
            append("&execution=").append(page.execution)
            append("&_eventId=submit")
            append("&not_exit_number=")
            append("&service_id=")
            if (!captcha.isNullOrBlank()) {
                append("&captcha=").append(java.net.URLEncoder.encode(captcha, "UTF-8"))
            }
        }

        val r = Net.postForm(
            "$CAS_BASE/cas/login?service=$enc",
            form,
            page.cookie.ifBlank { null },
            timeoutMs = 25000,
        )
        val debug = "http=${r.http} loc=${r.location?.take(80)} len=${r.body.length}"

        // 成功：302 到 service 且带 ticket
        if (r.http in 300..399) {
            findCastgc(r)?.let { return Result(true, castgc = it, debug = debug) }
            // 302 回登录页 = 失败（有些实现会 302 回自己）
            val loc = r.location.orEmpty()
            if (loc.contains("/cas/login")) {
                return Result(false, error = "登录未通过（请检查账号密码）", debug = debug)
            }
            return Result(false, error = "登录后未拿到票据", debug = debug)
        }

        // 200 = 回到登录页 = 失败，抠出服务端原话
        val err = parseError(r.body)
        val needsCaptcha = requiresCaptcha(r.body)
        return Result(
            ok = false,
            error = err ?: "登录失败（服务端未给出原因）",
            needsCaptcha = needsCaptcha,
            debug = debug,
        )
    }
}
