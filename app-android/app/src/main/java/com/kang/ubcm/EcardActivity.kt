package com.kang.ubcm

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 一卡通服务大厅（WebView 承载）
 *
 * 为什么不原生调接口：新开普平台的响应体是 AES-ECB 加密的（{"data":"..."}），
 * 密钥在页面运行时才生成，逆向不划算；而页面本身就是移动端适配的 H5，
 * 用 WebView 承载能拿到完整功能（余额/流水/挂失/充值），且平台升级不用改代码。
 *
 * 登录态：把 CASTGC 写进 WebView 的 cookie 存储，页面会自己走完 CAS SSO。
 */
class EcardActivity : ComponentActivity() {

    companion object {
        private const val SSO_URL = "https://ykt.bucm.edu.cn/servicehall/sso/login"
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, EcardActivity::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val castgc = Session.loadCastgc(this)
        // 把 CAS 票据放进 WebView 的 cookie 罐，后续 SSO 自动完成
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            castgc?.let { setCookie("https://cas.bucm.edu.cn", "CASTGC=$it") }
            setCookie("https://cas.bucm.edu.cn", "Language=zh_CN")
        }
        setContent { EcardScreen(SSO_URL) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EcardScreen(url: String) {
    var progress by remember { mutableIntStateOf(0) }
    val webRef = remember { mutableStateOf<WebView?>(null) }

    Column(Modifier.fillMaxSize()) {
        // 顶部薄进度条
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Color(0xFFB33A2B),
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
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
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, u: String?) {
                            progress = 100
                        }
                    }
                    loadUrl(url)
                    webRef.value = this
                }
            },
        )
    }
}

/** 顶部提示条：告诉用户"这是学校系统，不是本 APP 做的" */
@Composable
private fun Banner() {
    Surface(color = Color(0xFFFBF7F0)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("校园卡服务由学校一卡通平台提供", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B6359))
        }
    }
}
