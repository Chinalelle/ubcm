package com.kang.ubcm

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 零依赖网络层（HttpURLConnection）。
 * 之所以不用 OkHttp：首版只要"能跑通 + 能看原始响应"，少一个依赖少一次下载。
 */
object Net {

    const val PORTAL_BASE = "https://i.bucm.edu.cn/apim/sems-tp-nup"
    const val JW_BASE = "https://jw.bucm.edu.cn"

    data class Resp(
        val http: Int,
        val body: String,
        val ms: Long,
        val location: String? = null,
        val setCookie: List<String> = emptyList(),
    ) {
        fun isHttpOk() = http in 200..299
        /** 从 Set-Cookie 里按名字取值 */
        fun cookie(name: String): String? =
            setCookie.firstOrNull { it.startsWith("$name=") }
                ?.substringAfter("=")?.substringBefore(";")
    }

    fun postJson(path: String, body: String = "{}", token: String? = null, timeoutMs: Int = 20000): Resp =
        request("$PORTAL_BASE$path", body, token, "POST", timeoutMs)

    /** 绝对地址的 JSON POST（一卡通等第三方平台用） */
    fun postJsonAbs(url: String, body: String, cookie: String? = null, timeoutMs: Int = 20000): Resp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            cookie?.let { setRequestProperty("Cookie", it) }
            instanceFollowRedirects = false
        }
        return exec(conn, body)
    }

    /** 教务（强智）是表单 POST，不是 JSON */
    fun postForm(url: String, form: String, cookie: String?, timeoutMs: Int = 20000): Resp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "*/*")
            cookie?.let { setRequestProperty("Cookie", it) }
            instanceFollowRedirects = false
        }
        return exec(conn, form)
    }

    fun postAbsolute(url: String, body: String, token: String? = null, cookie: String? = null): Resp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            cookie?.let { setRequestProperty("Cookie", it) }
            instanceFollowRedirects = false
        }
        return exec(conn, body)
    }

    fun get(url: String, token: String? = null, cookie: String? = null, timeoutMs: Int = 20000): Resp {        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "*/*")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            cookie?.let { setRequestProperty("Cookie", it) }
            instanceFollowRedirects = false
        }
        return exec(conn, null)
    }

    private fun request(url: String, body: String, token: String?, method: String, timeoutMs: Int): Resp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            setRequestProperty("User-Agent", UA)
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            instanceFollowRedirects = false
        }
        return exec(conn, body)
    }

    private fun exec(conn: HttpURLConnection, body: String?): Resp {
        val t0 = System.currentTimeMillis()
        return try {
            if (body != null) {
                conn.outputStream.use { os: OutputStream ->
                    os.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            val http = conn.responseCode
            val stream = if (http in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            Resp(
                http, text, System.currentTimeMillis() - t0,
                conn.getHeaderField("Location"),
                conn.headerFields.filterKeys { it?.equals("Set-Cookie", true) == true }
                    .values.flatten(),
            )
        } catch (e: Exception) {
            Resp(-1, "ERR: ${e.javaClass.simpleName}: ${e.message}", System.currentTimeMillis() - t0)
        } finally {
            conn.disconnect()
        }
    }

    const val UA = "Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/157.0 Mobile Safari/537.36"
}
