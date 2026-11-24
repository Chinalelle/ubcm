package com.kang.ubcm

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网瑞达 wEngine（学校 WebVPN）的主机名编码。
 *
 * 格式（已实测验证，与北航 UBAA 项目一致）：
 *   encode(host) = KEY_HEX + hex( AES-128-CFB(plaintext = host, key = KEY, iv = KEY) )
 *   KEY = "wrdvpnisthebest!"（16 字节）
 *   KEY_HEX = "77726476706e69737468656265737421"
 *
 * 代理地址：
 *   https://webvpn.bucm.edu.cn/https/<encoded>/<原 path>
 *
 * 实测：cas.bucm.edu.cn → ...21f3f652d225256b5d300d8db9d6562d（与页面抓到的完全一致）
 */
object Wengine {

    private const val KEY_STR = "wrdvpnisthebest!"
    private const val KEY_HEX = "77726476706e69737468656265737421"
    private val KEY = KEY_STR.toByteArray(Charsets.UTF_8)

    fun encodeHost(host: String): String {
        val plain = host.toByteArray(Charsets.UTF_8)
        val enc = try {
            Cipher.getInstance("AES/CFB/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(KEY))
                doFinal(plain)
            }
        } catch (e: Exception) {
            cfb128(plain, encrypt = true)
        }
        return KEY_HEX + enc.toHex()
    }

    /** 手写 CFB128 兜底（部分 ROM 的 Provider 不认 AES/CFB） */
    private fun cfb128(input: ByteArray, encrypt: Boolean): ByteArray {
        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"))
        var iv = KEY.copyOf()
        val out = ByteArray(input.size)
        var off = 0
        while (off < input.size) {
            val ks = aes.doFinal(iv)
            val n = minOf(16, input.size - off)
            for (i in 0 until n) out[off + i] = (input[off + i].toInt() xor ks[i].toInt()).toByte()
            // 下一块 IV = 密文块（加密时）或输入块（解密时）
            val src = if (encrypt) out else input
            System.arraycopy(src, off, iv, 0, n)
            off += n
        }
        return out
    }

    /** 把任意校内 URL 转成 WebVPN 代理 URL */
    fun toProxy(url: String): String {
        return try {
            val u = java.net.URI(url)
            val host = u.host ?: return url
            val scheme = if (u.port > 0 && u.port != 443 && u.port != 80) "https" else "https"
            val path = buildString {
                append(u.rawPath.ifBlank { "/" })
                u.rawQuery?.let { append("?").append(it) }
            }
            "https://webvpn.bucm.edu.cn/$scheme/${encodeHost(host)}$path"
        } catch (e: Exception) {
            url
        }
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b.toInt() and 0xff))
        return sb.toString()
    }
}
