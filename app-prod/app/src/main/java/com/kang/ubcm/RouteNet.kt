package com.kang.ubcm

/**
 * 双路线请求层：校内直连，失败自动回退 WebVPN。
 *
 * 设计参考 UBAA 的 DualSessionCoordinator：
 *  - 不是二选一切换，而是两条腿都可用
 *  - 每个结果都带 route，界面明确显示"这次实际走的哪条路"
 *  - 直连失败（网络层错误 / 非本系统响应）才回退，业务错误（如未登录）不回退
 */
object RouteNet {

    enum class Route { DIRECT, WEBVPN, NONE }

    data class Result(
        val resp: Net.Resp,
        val route: Route,
        val note: String = "",
    )

    /** 是否在校园网：能直连到内网网关就算 */
    @Volatile private var cachedInternal: Boolean? = null
    @Volatile private var cachedAt: Long = 0

    fun isInternal(forceRefresh: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedInternal != null && now - cachedAt < 30_000) return cachedInternal!!
        // 用最轻的校内地址探测：i.bucm 的静态资源
        val r = try {
            Net.get("https://i.bucm.edu.cn/tp_nup/resource/bucm/html/cardCenter/smallCard/ykt.html", timeoutMs = 4000)
        } catch (e: Exception) {
            Net.Resp(-1, "", 0)
        }
        val ok = r.http in 200..399
        cachedInternal = ok
        cachedAt = now
        return ok
    }

    /**
     * GET：优先直连；直连不通则改走 WebVPN 代理地址。
     * webvpnCookie 为空时，回退会失败并明确提示"缺少 WebVPN 会话"。
     */
    fun get(
        url: String,
        cookie: String? = null,
        webvpnCookie: String? = null,
        timeoutMs: Int = 15000,
    ): Result {
        val direct = Net.get(url, cookie = cookie, timeoutMs = timeoutMs)
        if (isUsable(direct)) return Result(direct, Route.DIRECT)

        val proxy = Wengine.toProxy(url)
        if (webvpnCookie.isNullOrBlank()) {
            return Result(
                direct,
                Route.NONE,
                "直连失败且无 WebVPN 会话（需要先登录 webvpn）",
            )
        }
        val viaVpn = Net.get(proxy, cookie = webvpnCookie, timeoutMs = timeoutMs)
        return if (isUsable(viaVpn)) {
            Result(viaVpn, Route.WEBVPN, "已回退 WebVPN")
        } else {
            Result(viaVpn, Route.NONE, "直连与 WebVPN 均失败")
        }
    }

    /** 能不能当成"拿到了有效响应"：HTTP 2xx/3xx 且有内容 */
    private fun isUsable(r: Net.Resp): Boolean =
        r.http in 200..399 && r.body.isNotEmpty() || (r.http in 200..299)
}
