package com.kang.ubcm

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新（P14）。
 *
 * 数据源：GitHub Releases 的 `releases/latest`（公开仓库，不需要 token）。
 * 只做**用户手动触发**的检查和下载 —— 不做后台静默更新。
 */
object UpdateChecker {

    const val RELEASE_API = "https://api.github.com/repos/Chinalelle/ubcm/releases/latest"

    data class RemoteRelease(
        val tag: String,
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val fileName: String,
        val sizeBytes: Long,
    )

    data class Result(
        val release: RemoteRelease? = null,
        val error: String? = null,
    )

    /**
     * 语义化版本比较（纯逻辑，可单测）。
     * `1.0.10 > 1.0.9`，忽略前缀 v 和 `-debug` 之类的后缀。
     */
    fun compareVersions(a: String, b: String): Int {
        fun parts(s: String): List<Int> =
            s.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
                .split('.')
                .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    /** 当前是否该提示更新：远端版本严格高于本地。 */
    fun hasUpdate(remoteVersion: String, localVersion: String): Boolean =
        compareVersions(remoteVersion, localVersion) > 0

    /** 解析 GitHub release JSON。拿不到 APK 资产视为"没有可用更新"。 */
    fun parseRelease(json: String): RemoteRelease? = try {
        val o = JSONObject(json)
        val tag = o.optString("tag_name").ifBlank { return null }
        val notes = o.optString("body").ifBlank { "" }
        val assets = o.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        var fileName = ""
        var size = 0L
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (name.lowercase().endsWith(".apk")) {
                apkUrl = a.optString("browser_download_url")
                fileName = name
                size = a.optLong("size", 0L)
                break
            }
        }
        val url = apkUrl?.takeIf { it.isNotBlank() } ?: return null
        RemoteRelease(
            tag = tag,
            versionName = tag.removePrefix("v"),
            notes = notes,
            apkUrl = url,
            fileName = fileName.ifBlank { "update.apk" },
            sizeBytes = size,
        )
    } catch (e: Exception) {
        null
    }

    /** 网络检查。失败给出**可读原因**，不静默。 */
    fun check(localVersion: String): Result = try {
        val resp = Net.get(RELEASE_API, timeoutMs = 15000)
        if (!resp.isHttpOk()) {
            Result(error = "GitHub 返回 HTTP ${resp.http}")
        } else {
            val rel = parseRelease(resp.body)
                ?: return Result(error = "最新 Release 里没有 APK 资产")
            Result(release = rel)
        }
    } catch (e: Exception) {
        Result(error = "检查失败：${e.javaClass.simpleName}: ${e.message ?: "网络不可用"}")
    }

    /**
     * 下载 APK 到 `cache/updates/`，带进度回调（0..100）。
     * 只在用户点「更新」时调用，不做后台静默下载。
     */
    fun download(
        ctx: Context,
        release: RemoteRelease,
        onProgress: (Int) -> Unit,
    ): File? {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        // 先清掉旧包，避免磁盘越攒越多
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, release.fileName)
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                setRequestProperty("User-Agent", "UBcm-updater")
            }
            if (conn.responseCode !in 200..299) return null
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            onProgress(100)
            out.takeIf { it.length() > 0 }
        } catch (e: Exception) {
            out.delete()
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 拉起系统安装器（FileProvider + ACTION_VIEW）。 */
    fun install(ctx: Context, apk: File): String? = try {
        val uri: Uri = FileProvider.getUriForFile(
            ctx,
            "${ctx.packageName}.fileprovider",
            apk,
        )
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        null
    } catch (e: Exception) {
        "无法拉起安装器：${e.javaClass.simpleName}（请确认已允许安装未知来源应用）"
    }
}
