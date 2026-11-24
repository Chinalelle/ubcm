package com.kang.ubcm

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * 通用「学校页面」承载器。
 *
 * 用途：凡是学校已经做好、而 App 只该做入口的功能（座位预约、一卡通、各种写操作），
 * 都用它打开。App 侧坚持只读，写操作留在学校页面 —— 这是 docs/05 定的合规边界。
 *
 * ⚠️ 用传统 View 而不是 Compose 的 AndroidView：在小米平板（HyperOS / Android 17）上，
 * WebView 放进 Compose AndroidView 会整片白屏（页面正常、合成层无输出）。
 * 详见 docs/12 §8.5。
 */
class SchoolWebActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HASH = "hash"
        private const val EXTRA_JS = "js"

        /**
         * @param hashAfterLoad SPA 的 hash 路由（如 `#/seatReserve?roomId=43`）。
         *   学校这套 Vue Router 在**冷启动**时会忽略 URL 里的 hash 直接落到首页，
         *   所以不能把它拼进 url 里，只能等首页加载完再用 JS 切过去（实测有效）。
         */
        fun start(
            ctx: Context,
            url: String,
            title: String,
            hashAfterLoad: String? = null,
            /** 切完路由后再执行的 JS（用来预填表单，比如日期和时段） */
            jsAfterLoad: String? = null,
        ) {
            ctx.startActivity(
                Intent(ctx, SchoolWebActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_HASH, hashAfterLoad)
                    .putExtra(EXTRA_JS, jsAfterLoad)
            )
        }
    }

    private var jsAfterLoad: String? = null
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        val url = intent.getStringExtra(EXTRA_URL) ?: "https://kjyy.bucm.edu.cn/"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "学校页面"
        val hashAfterLoad = intent.getStringExtra(EXTRA_HASH)
        jsAfterLoad = intent.getStringExtra(EXTRA_JS)

        // 把已有票据放进 WebView cookie 罐，页面自己会走完 SSO
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            Session.loadCastgc(this@SchoolWebActivity)?.let { setCookie("https://cas.bucm.edu.cn", "CASTGC=$it") }
            Session.loadSeatCookie(this@SchoolWebActivity)?.let { setCookie("https://kjyy.bucm.edu.cn", "ic-cookie=$it") }
            Session.loadEcardSession(this@SchoolWebActivity)?.let { setCookie("https://ykt.bucm.edu.cn", "servicehall=$it") }
        }

        setContentView(buildLayout(url, title, hashAfterLoad))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildLayout(url: String, title: String, hashAfterLoad: String?): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AColor.parseColor("#F4F7FB"))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(AColor.parseColor("#F4F7FB"))
        }
        bar.addView(TextView(this).apply {
            text = "‹  返回"
            setTextColor(AColor.parseColor("#4E5D73"))
            textSize = 13f
            setPadding(0, 0, dp(14), 0)
            setOnClickListener { finish() }
        })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = title
            setTextColor(AColor.parseColor("#101B2B"))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        statusText = TextView(this).apply {
            text = "加载中…"
            setTextColor(AColor.parseColor("#4E5D73"))
            textSize = 10f
        }
        titles.addView(statusText)
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(AColor.parseColor("#1B4A7E"))
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
                userAgentString = userAgentString.replace("; wv", "")
            }
            setBackgroundColor(AColor.WHITE)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, p: Int) {
                    progressBar.progress = p
                    progressBar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
                }
                override fun onReceivedTitle(view: WebView?, t: String?) {
                    if (!t.isNullOrBlank()) statusText.text = t
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    progressBar.visibility = View.GONE
                    if (!hashAfterLoad.isNullOrBlank()) {
                        applyRoute(view, if (hashApplied) null else hashAfterLoad)
                    }
                }
            }
            loadUrl(url)
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private var hashApplied = false

    /**
     * 把页面切到指定路由。
     *
     * 两个坑：
     * 1. 冷启动时 Vue 还没 mount 完，`onPageFinished` 立刻改 hash 会被路由初始化覆盖 → 延迟 2.5s；
     * 2. 直接改 `location.hash` 有时不触发导航 → 优先用 `$router.push`，失败再退 hash。
     */
    private fun applyRoute(view: WebView?, route: String?) {
        if (view == null || route.isNullOrBlank() || hashApplied) return
        hashApplied = true
        val path = route.removePrefix("#")
        val js = """
            (function(){
              var tries = 0;
              function go(){
                try{
                  var app = document.querySelector('#app');
                  var vm = app && app.__vue__;
                  if (vm && vm.${'$'}router && vm.${'$'}router.push) { vm.${'$'}router.push('${path}'); return 'router'; }
                }catch(e){}
                if (tries++ < 6) { setTimeout(go, 600); return 'retry'; }
                try{ location.hash = '${route}'; return 'hash'; }catch(e){ return 'fail'; }
              }
              return go();
            })()
        """.trimIndent()
        view.postDelayed({ view.evaluateJavascript(js, null) }, 2500)
        // 路由稳定后再跑预填脚本
        jsAfterLoad?.let { extra ->
            view.postDelayed({ view.evaluateJavascript(extra, null) }, 4200)
            jsAfterLoad = null
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }
}
