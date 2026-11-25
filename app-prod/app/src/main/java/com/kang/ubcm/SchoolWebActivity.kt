package com.kang.ubcm

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.os.Bundle
import android.util.Log
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
        private const val TAG = "SchoolWeb"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HASH = "hash"
        private const val EXTRA_JS = "js"
        private const val EXTRA_WEWORK = "wework_fallback"

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
            /** 智慧后勤这类 H5 打不开/要微信授权时，给一个"用企业微信打开"的兜底按钮 */
            fallbackWework: Boolean = false,
        ) {
            ctx.startActivity(
                Intent(ctx, SchoolWebActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_HASH, hashAfterLoad)
                    .putExtra(EXTRA_JS, jsAfterLoad)
                    .putExtra(EXTRA_WEWORK, fallbackWework)
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

            // 教务：存的是整串 "JSESSIONID=...; SERVERID_jwxt=...; jsxsd=..."，
            // 必须拆成逐条 cookie 注入，否则整串会被当成一个非法 key（P8 修复：原来完全没注入）
            Session.loadJwSession(this@SchoolWebActivity)?.let { raw ->
                raw.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { kv ->
                    setCookie("https://jw.bucm.edu.cn", kv)
                }
            }

            // 门户：accessToken 的 cookie path 是 /tp_nup（不是 /），必须显式带 path，
            // 否则 i.bucm.edu.cn 页面读不到（P8 修复：原来没注入，所以数智北中医是登录页）
            Session.loadToken(this@SchoolWebActivity)?.let {
                setCookie("https://i.bucm.edu.cn", "accessToken=$it; path=/tp_nup")
            }
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

        // 兜底入口：智慧后勤这类 H5 若打不开/要微信授权，用户可一键转企业微信（P11 C5 修正）
        if (intent.getBooleanExtra(EXTRA_WEWORK, false)) {
            bar.addView(TextView(this).apply {
                text = "企业微信"
                setTextColor(AColor.parseColor("#3A7BC8"))
                textSize = 12f
                setPadding(dp(10), dp(4), dp(2), dp(4))
                setOnClickListener {
                    WeworkLauncher.open(this@SchoolWebActivity)
                }
            })
        }
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
                // 不再伪装 MicroMessenger UA（P13 撤回 P12 的改动）：
                // 实测带了这个 UA 后，zhhq 页面会认为自己在微信里，然后**主动跳**
                // open.weixin.qq.com/connect/oauth2/authorize 走网页授权 —— 普通 WebView 无法完成，
                // 落到微信的 "Oops! Something went wrong"。不带 UA 时页面会安静地显示
                // "请在微信中打开" 的静态提示，才是可预期的状态。
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

                /**
                 * 页面调用 `window.close()` 时**不要静默销毁**（用户看到的就是"闪一下就关"）。
                 * 这里保留 Activity，把提示写进标题栏，用户可以自己返回或重试。
                 */
                override fun onCloseWindow(window: WebView?) {
                    Log.w(TAG, "onCloseWindow: 页面请求关闭，已拦截（不退出） url=$url")
                    statusText.text = "页面请求关闭（已拦截）"
                    // 不调用 super、不 finish()
                }

                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                    if (isZhhq) {
                        Log.d(TAG, "console[${msg.messageLevel()}] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                    }
                    return super.onConsoleMessage(msg)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, u: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, u, favicon)
                    if (isZhhq) Log.d(TAG, "onPageStarted: $u")
                }

                override fun onPageFinished(view: WebView?, u: String?) {
                    progressBar.visibility = View.GONE
                    if (isZhhq) Log.d(TAG, "onPageFinished: $u")
                    // wx 桩：用 Proxy 返回 no-op 函数，页面里 wx.xxx() 随便调都不会 TypeError；
                    // 但**真实 JS-SDK 能力（wx.config 签名/支付/相机）依然不可用**
                    if (isZhhq) {
                        view?.evaluateJavascript(
                            "window.wx = new Proxy({}, { get: function(){ return function(){}; } });" +
                                "window.WeixinJSBridge = window.WeixinJSBridge || { invoke: function(t, p, cb) { if (cb) cb({ err_msg: 'ok' }); } };" +
                                "void 0;",
                            null,
                        )
                    }
                    if (!hashAfterLoad.isNullOrBlank()) {
                        applyRoute(view, if (hashApplied) null else hashAfterLoad)
                    }
                }

                /** 非 http(s) 链接（weixin:// wxwork:// intent:// 等）不交给系统，避免 Activity 被切走 */
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): Boolean {
                    val target = request?.url?.toString().orEmpty()
                    return handleUrl(target)
                }

                @Deprecated("旧回调，兜底")
                override fun shouldOverrideUrlLoading(view: WebView?, u: String?): Boolean =
                    handleUrl(u.orEmpty())

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    // 只处理主框架，子资源失败不打扰用户
                    if (request?.isForMainFrame != true) return
                    val code = error?.errorCode ?: -1
                    val desc = error?.description?.toString().orEmpty()
                    Log.e(TAG, "onReceivedError code=$code desc=$desc url=${request.url}")
                    showErrorPage("页面加载失败", "错误码 $code\n$desc")
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?,
                ) {
                    val host = runCatching { android.net.Uri.parse(error?.url.orEmpty()).host }.getOrNull().orEmpty()
                    Log.e(TAG, "onReceivedSslError: primary=${error?.primaryError} url=${error?.url} host=$host")
                    // 只对校园域做 proceed 兜底（其他域仍拒绝）。
                    // 注意：zhhq 实测证书链完整，这条正常不会触发；真触发说明是别的问题，日志里能看到。
                    if (host.endsWith(".bucm.edu.cn") || host == "bucm.edu.cn") {
                        Log.w(TAG, "SSL 错误在校园域，proceed 兜底: $host")
                        handler?.proceed()
                    } else {
                        handler?.cancel()
                        showErrorPage(
                            "证书校验失败",
                            "服务器证书有问题（错误 ${error?.primaryError}）。",
                        )
                    }
                }

                /** 渲染进程崩溃：返回 true 表示已处理，Activity 不跟着挂 */
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?,
                ): Boolean {
                    Log.e(TAG, "onRenderProcessGone: crashed=${detail?.didCrash()} url=$url")
                    statusText.text = "页面渲染进程异常，请返回重进"
                    return true
                }
            }
            loadUrl(url)
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private var hashApplied = false

    private val isZhhq: Boolean by lazy { intent.getStringExtra(EXTRA_URL).orEmpty().contains("zhhq.bucm.edu.cn") }

    /** 拦截非 http(s) scheme，交给系统会把当前 Activity 切走（用户看到"闪退"） */
    private fun handleUrl(target: String): Boolean {
        if (target.isBlank()) return false
        val lower = target.lowercase()

        // ⚠️ 微信 OAuth 检查必须放在 http(s) 放行**之前**，否则 https 会先 return false（踩过）
        if (lower.startsWith("https://open.weixin.qq.com/connect/oauth2") ||
            lower.startsWith("http://open.weixin.qq.com/connect/oauth2")
        ) {
            Log.w(TAG, "拦截微信 OAuth 跳转: $target")
            statusText.text = "需要在微信内授权（已拦截）"
            showErrorPage(
                "需要微信授权",
                "这个页面要先用微信完成授权（openid），在 App 内无法完成。\n请点右上角「企业微信」打开，或复制链接到微信里打开。",
            )
            return true
        }

        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("about:") || lower.startsWith("javascript:") || lower.startsWith("data:")
        ) {
            return false
        }

        Log.w(TAG, "拦截非 http(s) 跳转: $target")
        val hint = when {
            lower.startsWith("weixin://") -> "该页面想打开微信"
            lower.startsWith("wxwork://") || lower.startsWith("wxwork") -> "该页面想打开企业微信"
            lower.startsWith("intent://") -> "该页面想启动其他 App"
            lower.startsWith("tel:") -> "该页面想拨打电话"
            else -> "该页面想启动外部应用"
        }
        statusText.text = "$hint（已拦截）"
        return true
    }

    /** 用内置错误页替代白屏/闪退 */
    private fun showErrorPage(title: String, detail: String) {
        progressBar.visibility = View.GONE
        statusText.text = title
        val target = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!::webView.isInitialized) return
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="font-family:-apple-system,sans-serif;padding:28px;text-align:center;color:#101B2B">
              <div style="font-size:34px;line-height:1;margin-bottom:14px">&#9888;</div>
              <h3 style="margin:0 0 10px">${title}</h3>
              <p style="color:#4E5D73;font-size:14px;line-height:1.6;margin:0 0 18px">${detail}</p>
              <a href="${target}" style="display:inline-block;padding:10px 22px;background:#1B4A7E;color:#fff;border-radius:10px;text-decoration:none">重新加载</a>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(target, html, "text/html", "utf-8", null)
    }

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
