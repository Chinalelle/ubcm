package com.kang.ubcm

/**
 * 登录门禁 —— 这个 App 现在的定位是**测试软件**，不是产品。
 *
 * （2026-11-21 用户明确：现在不做功能，只用它验底层接口和算法，UI 排布后面再谈。）
 *
 * 所以打开 App 的第一件事不是看页面，而是**确认凭证齐不齐**：缺任何一条就强制登录。
 *
 * 为什么必须拦（不是"体验优化"，是刚需）：
 * 上一轮吃过亏 —— 所有学校登录态过期的时候，20 个探针全红，
 * 根本分不清是「接口改坏了」还是「压根没登录」。凭证不齐就跑测试 = 制造假信号。
 *
 * ⚠️ 这个文件刻意不碰 Android：凭证由调用方从 Session 读好传进来（[Snapshot]），
 * 这样判定逻辑能被 JVM 单测直接覆盖。
 */
object CredentialGate {

    /**
     * 一次凭证快照。字段为 null / 空白 = 这条没有。
     *
     * @param jwtRemainingMs JWT 剩余毫秒；null 表示没有 token 或解析不出 exp
     */
    data class Snapshot(
        val accessToken: String? = null,
        val castgc: String? = null,
        val jwSession: String? = null,
        val ecardSession: String? = null,
        val ecardOpenid: String? = null,
        val seatCookie: String? = null,
        val jwtRemainingMs: Long? = null,
    )

    /** 一条凭证的检查结果 */
    data class Item(
        val id: String,
        val label: String,
        val hint: String,
        /** 缺了该去哪个 WebView 目标补（[LoginWebViewActivity.Target] 的枚举名，纯字符串避免耦合 Android） */
        val targetName: String,
        val required: Boolean,
        val present: Boolean,
        /** 有值但**不可用**时的说明（目前只有 JWT 过期） */
        val problem: String? = null,
    ) {
        /** 可用 = 有值 且 没有 problem */
        val ok: Boolean get() = present && problem == null
    }

    data class Audit(
        val items: List<Item>,
        /** 缺的、且必须有的 */
        val missingRequired: List<Item>,
        val allOk: Boolean,
        /** 下一步该登哪个目标；全齐时为 null */
        val nextTarget: String?,
    ) {
        val okCount: Int get() = items.count { it.ok }
        val requiredCount: Int get() = items.count { it.required }
    }

    /**
     * 五条**必须有**的凭证 + 一条自动补齐的。
     *
     * 顺序就是登录顺序，也是 [Audit.nextTarget] 的优先级：
     * PORTAL 必须第一个 —— 它顺带把 CASTGC 存下来，后面三个目标全靠 CASTGC 自动 SSO。
     *
     * `ecard_openid` 标记为不必需：它是**派生值**（一卡通落地页里的隐藏 input），
     * WebView 抓取时机不对就可能拿不到。把它设成硬门槛会让用户永远过不了门禁，
     * 而一卡通刷新时本来就会自动补（见 `HomeViewModel.refreshCard`）。
     */
    fun audit(s: Snapshot): Audit {
        val jwtText = s.accessToken.orEmpty().trim()
        val jwtPresent = jwtText.isNotEmpty()
        val jwtExpired = jwtPresent && s.jwtRemainingMs != null && s.jwtRemainingMs <= 0

        val items = listOf(
            Item(
                id = "access_token",
                label = "门户 accessToken",
                hint = "数智北中医的 JWT，只有 4 小时",
                targetName = "PORTAL",
                required = true,
                present = jwtPresent && !jwtExpired,
                problem = if (jwtExpired) "已过期，点右边重新登" else null,
            ),
            Item(
                id = "castgc",
                label = "CAS 票据 CASTGC",
                hint = "一卡通 / 教务 / 座位 的单点都靠它",
                targetName = "PORTAL",
                required = true,
                present = !s.castgc.isNullOrBlank(),
            ),
            Item(
                id = "jw_jsessionid",
                label = "教务会话",
                hint = "课表、成绩、考试",
                targetName = "JW",
                required = true,
                present = !s.jwSession.isNullOrBlank(),
            ),
            Item(
                id = "ecard_session",
                label = "一卡通会话",
                hint = "余额、今日消费、流水",
                targetName = "ECARD",
                required = true,
                present = !s.ecardSession.isNullOrBlank(),
            ),
            Item(
                id = "ecard_openid",
                label = "一卡通 openid",
                hint = "落地页隐藏字段，缺了下次刷新余额会自动补",
                targetName = "ECARD",
                required = false,
                present = !s.ecardOpenid.isNullOrBlank(),
            ),
            Item(
                id = "seat_cookie",
                label = "图书馆座位 cookie",
                hint = "空位、找空桌、预约",
                targetName = "SEAT",
                required = true,
                present = !s.seatCookie.isNullOrBlank(),
            ),
        )

        val missing = items.filter { it.required && !it.ok }
        return Audit(
            items = items,
            missingRequired = missing,
            allOk = missing.isEmpty(),
            nextTarget = missing.firstOrNull()?.targetName,
        )
    }
}
