package com.kang.ubcm

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * **不可见**的座位凭证补齐。用户看不到任何页面。
 *
 * 为什么需要它（`docs/13` §3.2 已经挖到底）：
 * 图书馆的 `ic-cookie` **拿不到纯 native 版本** —— `auth/token` 返回 200 但 0 字节、
 * 无 `Set-Cookie`，`uniToken` 也不能直接当凭证用（试过 cookie / Authorization / POST，全不认）。
 * 它只在前端 SPA 跑起来之后才被 JS 写进 cookie。上一棒在 WebView 里 hook fetch/XHR 都没抓到。
 *
 * 但它**只差"让那个 SPA 跑一次"**。所以这里开一个 1×1 像素、透明、无动画的 WebView：
 * 预置 CASTGC → 加载座位页 → SPA 自动单点 → cookie 罐里出现 `ic-cookie` → 立刻退出。
 *
 * 用户视角：点「登录」之后进度多显示几秒，然后直接进主界面 —— 没有任何网页跳转。
 *
 * ⚠️ 刻意不用 Compose 的 `AndroidView`：在这台机型上（HyperOS / Android 17）
 * WebView 放进 Compose 会整片白屏（`docs/12` 踩过），所以用传统 `setContentView`。
 */
class SilentSeatLoginActivity : ComponentActivity() {

    companion object {
        /** 座位页 SPA 加载 + CAS 跳转 + 前端写 cookie，留足 25 秒 */
        private const val TIMEOUT_MS = 25_000L
        private const val POLL_MS = 400L
        private const val SEAT_HOST = "https://kjyy.bucm.edu.cn/"
        private const val START_URL = "https://kjyy.bucm.edu.cn/mobile.html"

        fun newIntent(ctx: Context): Intent =
            Intent(ctx, SilentSeatLoginActivity::class.java)
    }

    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 把已有的 CASTGC 预置进 WebView 的 cookie 罐，CAS 会自动放行（用户不用再输密码）
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            Session.loadCastgc(this@SilentSeatLoginActivity)?.let {
                setCookie("https://cas.bucm.edu.cn", "CASTGC=$it")
            }
        }

        val wv = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                userAgentString = userAgentString.replace("; wv", "")
            }
            setBackgroundColor(Color.TRANSPARENT)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // 页面一加载完就抢先查一次，多数情况下 cookie 此刻已经有了
                    tryGrab()
                }
            }
            loadUrl(START_URL)
        }
        webView = wv
        // 1×1 像素：看不见，但确实附着在窗口上、JS 正常执行
        // （设成 INVISIBLE/GONE 有被系统暂停渲染的风险，所以保持可见只是极小且透明）
        setContentView(wv, ViewGroup.LayoutParams(1, 1))

        startPolling()
    }

    private fun startPolling() {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        val tick = object : Runnable {
            override fun run() {
                if (finished) return
                if (tryGrab()) return
                if (System.currentTimeMillis() > deadline) {
                    // 拿不到就老实失败 —— 不重试、不猜。调用方会把座位标成「缺」，并给出网页补登入口。
                    finishWith(false)
                    return
                }
                handler.postDelayed(this, POLL_MS)
            }
        }
        handler.post(tick)
    }

    /** 查 cookie 罐里有没有 ic-cookie；有就存进 Session 并结束 */
    private fun tryGrab(): Boolean {
        if (finished) return false
        val raw = CookieManager.getInstance().getCookie(SEAT_HOST) ?: return false
        val hit = raw.split(";").asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("ic-cookie=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
            ?: return false
        Session.saveSeatCookie(this, hit)
        finishWith(true)
        return true
    }

    private fun finishWith(ok: Boolean) {
        if (finished) return
        finished = true
        setResult(if (ok) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.let { wv ->
            wv.stopLoading()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
