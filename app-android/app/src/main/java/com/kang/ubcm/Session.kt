package com.kang.ubcm

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/**
 * 从 JWT 里解出的身份信息。
 * 注意：payload 里还有 id_number（身份证号）和人脸照片地址，本类刻意不接收，
 * 避免它出现在任何 UI / 日志 / 崩溃上报里。
 */
data class Identity(
    val studentNo: String,
    val name: String,
    val mobile: String,
    val unitUid: String,
    val cardType: String,
    val cardId: String,
    val expEpochSec: Long,
) {
    val expAt: Long get() = expEpochSec * 1000
    fun remainingMs(now: Long = System.currentTimeMillis()) = expAt - now
}

object Session {
    private const val PREF = "ubcm_session"
    private const val KEY_JWT = "access_token"
    private const val KEY_JW_SESSION = "jw_jsessionid"
    private const val KEY_CASTGC = "castgc"
    private const val KEY_SEAT = "seat_cookie"
    private const val KEY_ECARD_SESSION = "ecard_session"
    private const val KEY_ECARD_OPENID = "ecard_openid"
    private const val KEY_FIRST_WEEK_MONDAY = "first_week_monday"
    private const val KEY_ECARD_TS = "ecard_ts"

    // ---------- 周次锚点 ----------

    /**
     * 本学期首周周一（"2026-09-07"）。每学期换一次，首页算「第几周 / 下一节课」全依赖它。
     * 没存过时返回默认值。
     */
    fun loadFirstWeekMonday(ctx: Context): String =
        prefs(ctx).getString(KEY_FIRST_WEEK_MONDAY, null)
            ?.takeIf { it.isNotBlank() }
            ?: Timetable.DEFAULT_FIRST_WEEK_MONDAY

    fun saveFirstWeekMonday(ctx: Context, v: String) =
        prefs(ctx).edit().putString(KEY_FIRST_WEEK_MONDAY, v.trim()).apply()

    // ---------- 一卡通会话（避免每次打开都重走 3 跳 CAS） ----------

    /** openid 允许为空：WebView 登录时页面还没加载出 openid 就先只存会话，下次现取 */
    fun saveEcard(ctx: Context, session: String, openid: String?) {
        val e = prefs(ctx).edit()
            .putString(KEY_ECARD_SESSION, session)
            .putLong(KEY_ECARD_TS, System.currentTimeMillis())
        if (!openid.isNullOrBlank()) e.putString(KEY_ECARD_OPENID, openid)
        e.apply()
    }

    fun loadEcardSession(ctx: Context): String? =
        prefs(ctx).getString(KEY_ECARD_SESSION, null)?.takeIf { it.isNotBlank() }

    fun loadEcardOpenid(ctx: Context): String? =
        prefs(ctx).getString(KEY_ECARD_OPENID, null)?.takeIf { it.isNotBlank() }

    /** 只补 openid（手工粘贴时可能只拿到这一个值） */
    fun saveEcardOpenid(ctx: Context, v: String) =
        prefs(ctx).edit().putString(KEY_ECARD_OPENID, v.trim()).apply()

    fun clearEcard(ctx: Context) =
        prefs(ctx).edit().remove(KEY_ECARD_SESSION).remove(KEY_ECARD_OPENID).remove(KEY_ECARD_TS).apply()

    fun saveToken(ctx: Context, token: String) =
        prefs(ctx).edit().putString(KEY_JWT, token.trim()).apply()

    fun loadToken(ctx: Context): String? =
        prefs(ctx).getString(KEY_JWT, null)?.takeIf { it.isNotBlank() }

    fun clear(ctx: Context) = prefs(ctx).edit().clear().apply()

    fun saveJwSession(ctx: Context, cookie: String) =
        prefs(ctx).edit().putString(KEY_JW_SESSION, cookie).apply()

    fun loadJwSession(ctx: Context): String? =
        prefs(ctx).getString(KEY_JW_SESSION, null)?.takeIf { it.isNotBlank() }

    /** CAS 票据：一卡通/教务的 SSO 都靠它，比 JWT 更"万能" */
    fun saveCastgc(ctx: Context, v: String) =
        prefs(ctx).edit().putString(KEY_CASTGC, v.trim()).apply()

    fun loadCastgc(ctx: Context): String? =
        prefs(ctx).getString(KEY_CASTGC, null)?.takeIf { it.isNotBlank() }

    fun saveSeatCookie(ctx: Context, v: String) =
        prefs(ctx).edit().putString(KEY_SEAT, v.trim()).apply()

    fun loadSeatCookie(ctx: Context): String? =
        prefs(ctx).getString(KEY_SEAT, null)?.takeIf { it.isNotBlank() }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * 解析 JWT payload。不做签名校验 —— 客户端拿不到公钥，签名校验没意义，
     * 真正的校验由服务端做；这里只用于读展示字段 + 判断本地过期。
     */
    fun parse(token: String?): Identity? {
        if (token.isNullOrBlank()) return null
        val parts = token.split('.')
        if (parts.size < 2) return null
        return try {
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val j = JSONObject(payload)
            Identity(
                studentNo = j.optString("sub"),
                name = j.optString("user_name"),
                mobile = j.optString("mobile"),
                unitUid = j.optString("unit_uid"),
                cardType = j.optString("card_type"),
                cardId = j.optString("card_id"),
                expEpochSec = j.optLong("exp"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
