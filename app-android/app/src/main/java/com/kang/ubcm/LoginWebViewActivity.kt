package com.kang.ubcm

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * 通用 WebView 登录器。
 *
 * 学校各系统都是「CAS 单点」，短信验证码必须人工完成，所以用 WebView 让用户自己登，
 * 成功后把目标 cookie 抓回来存进 Session。
 *
 * 四个目标：数智北中医（accessToken + CASTGC）/ 教务（JSESSIONID 整串）/
 * 一卡通（servicehall + openid）/ 图书馆座位（ic-cookie）。
 *
 * ⚠️ 这里刻意**不用 Compose 的 AndroidView**，用传统 View 布局。
 * 在小米平板（HyperOS / Android 17）上，WebView 放进 Compose 的 AndroidView 会整片白屏：
 * DOM 正常、`Page.captureScreenshot` 也超时（合成层无输出），加软件层渲染也无效。
 * 换成 setContentView 的传统布局后可以正常显示。
 */
class LoginWebViewActivity : ComponentActivity() {

    /**
     * @param url        要打开的「登录入口」（通常在 cas.bucm.edu.cn）
     * @param cookieName 登录成功后要抓的 cookie 名
     * @param cookieHost **该 cookie 真正所在的域（含 path）**。登录入口在 cas 域，
     *                   但 accessToken 在 i.bucm.edu.cn 且 path=/tp_nup、JSESSIONID 在
     *                   jw.bucm.edu.cn/jsxsd。用错了域或路径都会查不到（首版就错在这）。
     * @param label      界面标题
     */
    enum class Target(
        val url: String,
        val cookieName: String,
        val cookieHost: String,
        val label: String,
    ) {
        SEAT("https://kjyy.bucm.edu.cn/", "ic-cookie", "https://kjyy.bucm.edu.cn/ic-web/", "图书馆座位预约"),
        ECARD("https://ykt.bucm.edu.cn/servicehall/sso/login", "servicehall", "https://ykt.bucm.edu.cn/servicehall/", "一卡通"),
        JW(
            "https://cas.bucm.edu.cn/cas/login?service=https%3A%2F%2Fjw.bucm.edu.cn%2F",
            "JSESSIONID",
            "https://jw.bucm.edu.cn/jsxsd/",
            "教务系统",
        ),
        PORTAL(
            "https://cas.bucm.edu.cn/cas/login?service=https%3A%2F%2Fi.bucm.edu.cn%2Ftp_nup%2F",
            "accessToken",
            // ⚠️ 必须带 /tp_nup：accessToken 的 cookie path 就是 /tp_nup，
            // 拿 https://i.bucm.edu.cn/ 去查会因 path 不匹配而返回 null（实测踩过）
            "https://i.bucm.edu.cn/tp_nup/",
            "数智北中医",
        );

        companion object {
            fun of(name: String?): Target = entries.firstOrNull { it.name == name } ?: PORTAL
        }
    }

    companion object {
        private const val EXTRA = "target"

        /** 供 `registerForActivityResult` 用：需要 Intent 才能拿到 resultCode */
        fun newIntent(ctx: Context, target: Target): Intent =
            Intent(ctx, LoginWebViewActivity::class.java).putExtra(EXTRA, target.name)

        fun start(ctx: Context, target: Target) = ctx.startActivity(newIntent(ctx, target))
    }

    private lateinit var target: Target
    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var doneBtn: Button
    private lateinit var progressBar: ProgressBar
    private val handler = Handler(Looper.getMainLooper())
    private var grabbed: String? = null
    private var pendingOpenid: String? = null
    private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        target = Target.of(intent.getStringExtra(EXTRA))

        // 预置已有的 CASTGC，若 CAS 会话还在就能直接过
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            Session.loadCastgc(this@LoginWebViewActivity)?.let {
                setCookie("https://cas.bucm.edu.cn", "CASTGC=$it")
            }
        }

        setContentView(buildLayout())
        startPolling()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AColor.parseColor("#FBF7F0"))
        }

        // 顶部状态栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(AColor.parseColor("#FBF7F0"))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = target.label
            setTextColor(AColor.parseColor("#1E1B17"))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        statusText = TextView(this).apply {
            text = "等待登录…"
            setTextColor(AColor.parseColor("#6B6359"))
            textSize = 11f
        }
        titles.addView(statusText)
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        doneBtn = Button(this).apply {
            text = "完成"
            textSize = 12f
            visibility = View.GONE
            setTextColor(AColor.WHITE)
            setBackgroundColor(AColor.parseColor("#B33A2B"))
            setOnClickListener {
                // 回 RESULT_OK 是给登录门禁的「一键补全」用的：
                // 门禁只有收到 OK 才继续下一个目标，用户按返回键退出就停，不会被一直弹窗拽回去。
                setResult(RESULT_OK)
                finish()
            }
        }
        bar.addView(doneBtn, LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(AColor.parseColor("#B33A2B"))
        }
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                displayZoomControls = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // 去掉 "wv" 标记，避免个别站点把 WebView 当异常环境
                userAgentString = userAgentString.replace("; wv", "")
            }
            setBackgroundColor(AColor.WHITE)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    statusText.text = "加载中…"
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    progressBar.visibility = View.GONE
                    if (grabbed == null) statusText.text = "已加载，请登录"
                    // 一卡通落地页有隐藏字段 <input id="openid" value="...">，顺手挖出来
                    if (target == Target.ECARD) {
                        view?.evaluateJavascript(
                            "(function(){var e=document.getElementById('openid');return e?e.value:''})()"
                        ) { v ->
                            val oid = v?.trim()?.trim('"')?.trim()
                            if (!oid.isNullOrBlank() && oid.length >= 8) pendingOpenid = oid
                        }
                    }
                }
            }
            loadUrl(target.url)
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    /** 每秒查一次目标 cookie，命中就落盘 */
    private fun startPolling() {
        polling = true
        val deadline = System.currentTimeMillis() + 240_000
        val ctx = this
        val tick = object : Runnable {
            override fun run() {
                if (!polling) return
                if (System.currentTimeMillis() > deadline) {
                    statusText.text = "超时，请重试"
                    return
                }
                val cm = CookieManager.getInstance()
                val raw = cm.getCookie(target.cookieHost)
                statusText.text = "cookie=${raw?.length ?: -1}B ${if (grabbed != null) "· 已获取 ${target.cookieName}" else ""}"
                if (!raw.isNullOrEmpty()) {
                    val hit = raw.split(";").map { it.trim() }
                        .firstOrNull { it.startsWith("${target.cookieName}=") }
                        ?.substringAfter("=")
                        ?.takeIf { it.isNotBlank() }
                    if (hit != null && hit != grabbed) {
                        grabbed = hit
                        // 门户登录后顺手把 CASTGC（path=/cas）也存下 —— 一卡通/教务的 SSO 都靠它
                        if (target == Target.PORTAL) {
                            CookieManager.getInstance().getCookie("https://cas.bucm.edu.cn/cas/")
                                ?.split(";")?.map { it.trim() }
                                ?.firstOrNull { it.startsWith("CASTGC=") }
                                ?.substringAfter("=")?.takeIf { it.isNotBlank() }
                                ?.let { Session.saveCastgc(ctx, it) }
                        }
                        when (target) {
                            Target.SEAT -> Session.saveSeatCookie(ctx, hit)
                            Target.PORTAL -> Session.saveToken(ctx, hit)
                            Target.JW -> Session.saveJwSession(ctx, raw.replace(" ", ""))
                            Target.ECARD -> Session.saveEcard(ctx, hit, pendingOpenid)
                        }
                        statusText.text = "已获取 ${target.cookieName}（${hit.length} 字节）"
                        doneBtn.visibility = View.VISIBLE
                    }
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(tick)
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }
}
