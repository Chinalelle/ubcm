package com.kang.ubcm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

/**
 * 智慧后勤（`zhhq.bucm.edu.cn`）是**企业微信 H5**，依赖微信 JS-SDK，
 * 塞进 App 的 WebView 里必然白屏/报错（用户实测反馈）。
 *
 * 正确做法：唤起企业微信 App，让用户从「工作台 → 智慧后勤」进入；
 * 没装企业微信就复制链接，提示用户到企业微信里打开。
 */
object WeworkLauncher {

    const val URL = "https://zhhq.bucm.edu.cn/wx/#/basicHome"
    private const val WEWORK_PKG = "com.tencent.wework"

    fun open(ctx: Context) {
        // 优先用标准 launch intent；企业微信用的是自定义入口，
        // 在 Android 11+ 上还可能因为包可见性拿不到 → 用显式 ComponentName 兜底
        val candidates = buildList<android.content.Intent> {
            try {
                ctx.packageManager.getLaunchIntentForPackage(WEWORK_PKG)?.let { add(it) }
            } catch (e: Exception) {
            }
            add(
                android.content.Intent().setClassName(
                    WEWORK_PKG,
                    // 实测本机 `cmd package resolve-activity` 的入口就是它
                    "com.tencent.wework.launch.LaunchSplashEduActivity",
                )
            )
        }
        candidates.forEach { intent ->
            try {
                ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                toast(ctx, "已打开企业微信，请到 工作台 → 智慧后勤")
                return
            } catch (e: Exception) {
                // 换下一个候选
            }
        }
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("智慧后勤", URL))
            toast(ctx, "未安装企业微信，链接已复制，可在企业微信中打开")
        } catch (e: Exception) {
            toast(ctx, "未安装企业微信，请到工作台 → 智慧后勤")
        }
    }

    private fun toast(ctx: Context, text: String) {
        android.os.Handler(ctx.mainLooper).post {
            Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
        }
    }
}
