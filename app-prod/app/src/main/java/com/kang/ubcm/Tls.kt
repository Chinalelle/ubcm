package com.kang.ubcm

import android.content.Context
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS 补链。
 *
 * **问题**：教务 `jw.bucm.edu.cn` 的 TLS 只下发叶子证书，**不带中间证书**：
 * ```
 * openssl s_client -connect jw.bucm.edu.cn:443
 *   0 s:CN=*.bucm.edu.cn
 *     i:CN=GeoTrust G2 TLS CN RSA4096 SHA256 2022 CA1
 *   Verify return code: 21 (unable to verify the first certificate)   ← 只有 1 张
 * ```
 * 对比门户 `i.bucm.edu.cn` 发了 3 张（叶子 + 中间 + 根），所以门户一直正常。
 *
 * macOS/浏览器会自动走 AIA 去拉中间证书补上，**Android 的 TrustManager 不会**，
 * 于是真机上 `loadSkzccx` 直接抛：
 * `SSLHandshakeException: Trust anchor for certification path not found`。
 *
 * **做法**：内置那张公共中间证书（DigiCert 签发，公开可验证的 CA 证书），
 * 系统校验失败时**把中间证书补进链里重试**。
 *
 * ⚠️ 这不是"跳过证书校验"：证书本身仍然由系统信任库校验，只是把服务器漏发的那一环补上。
 * 如果补链后仍然失败，异常照常抛出。
 */
object Tls {

    /** 只在 `jw.bucm.edu.cn` 这类"链不完整"的校内部点上生效，不改变全局的信任锚 */
    private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        try {
            val extra = loadExtra(ctx)
            if (extra.isEmpty()) return

            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val system = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull() ?: return

            val wrapped = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
                    system.checkClientTrusted(chain, authType)

                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    try {
                        system.checkServerTrusted(chain, authType)
                    } catch (e: CertificateException) {
                        // 服务器漏发了中间证书 —— 补上再验一次，仍然失败就照常抛
                        val full = (chain.toList() + extra).distinct().toTypedArray()
                        if (full.size == chain.size) throw e
                        system.checkServerTrusted(full, authType)
                    }
                }
            }

            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf(wrapped), null)
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
            installed = true
            android.util.Log.i("UBcmTls", "已安装补链 TrustManager（内置 ${extra.size} 张中间证书）")
        } catch (e: Exception) {
            android.util.Log.w("UBcmTls", "安装失败，回退系统默认校验: ${e.message}")
        }
    }

    private fun loadExtra(ctx: Context): List<X509Certificate> = try {
        val stream: InputStream = ctx.resources.openRawResource(R.raw.bucm_intermediate)
        stream.use {
            CertificateFactory.getInstance("X.509")
                .generateCertificates(it)
                .filterIsInstance<X509Certificate>()
        }
    } catch (e: Exception) {
        emptyList()
    }
}
