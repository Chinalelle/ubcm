package com.kang.ubcm

/**
 * CAS 登录页的密码加密（对应前端 `/cas/comm/js/des.js` 的 `strEnc`）。
 *
 * 登录表单**不是明文提交**，浏览器提交前做三件事（`login10.js:153-155`）：
 * ```
 * $("#ul").val(u.length);                                   // 用户名「长度」
 * $("#pl").val(p.length);                                   // 密码「长度」
 * $("#rsa").val(strEnc(u + p + lt, '1', '2', '3'));         // 加密串
 * ```
 * ⚠️ `ul`/`pl` 存的是**长度而不是值** —— 服务端靠它校验完整性。填成值会直接
 * 「用户名或密码错误」，而且报错信息看不出是这里错了（踩过）。
 *
 * 真正的加密在 [DesLegacy]（那个 `des.js` 是个自洽的非标准 DES 变体），
 * 这里只负责拼串与表单字段。
 *
 * 🔴 安全约束：这个类只做纯计算，**不持有、不缓存、不落盘任何密码**。
 * 调用方传进来算完就该把明文扔掉。
 */
object CasCrypto {

    /** 登录表单要提交的加密三件套 */
    data class Sealed(
        /** `strEnc(用户名+密码+lt)` 的大写 hex */
        val rsa: String,
        /** 用户名长度（不是用户名） */
        val ul: Int,
        /** 密码长度（不是密码） */
        val pl: Int,
    )

    /**
     * 把一个账号 + 密码 + lt 封装成表单需要的三个字段。
     *
     * @param lt CAS 登录页 `/cas/login` 里隐藏的 `LT-...` 令牌，**每次 GET 都会变**
     */
    fun seal(username: String, password: String, lt: String): Sealed = Sealed(
        rsa = strEnc(username + password + lt, KEY1, KEY2, KEY3),
        ul = username.length,
        pl = password.length,
    )

    /** 委托给逐行移植的实现（不能换成 javax.crypto 的 DES，见 DesLegacy 的注释） */
    fun strEnc(data: String, k1: String, k2: String, k3: String): String =
        DesLegacy.strEnc(data, k1, k2, k3)

    /** 写死在前端 JS 里的三个 key（`login10.js:155`）。学校改了就得跟着改。 */
    const val KEY1 = "1"
    const val KEY2 = "2"
    const val KEY3 = "3"
}
