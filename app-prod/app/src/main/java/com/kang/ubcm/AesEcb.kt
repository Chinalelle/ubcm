package com.kang.ubcm

import android.util.Base64
import java.security.Key
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 一卡通平台的响应解密。
 *
 * 逆向结论（REA 定位 + 源码确认，见 docs/10）：
 *   dkywwachat.js:367
 *   tempFunction(aesUtil.decrypt(response.data.substr(16), response.data.substr(0, 16)));
 *
 * 即：**密文前 16 个字符就是密钥**，剩下的是 AES 密文。
 * 算法：AES / ECB / PKCS7（CryptoJS 默认），密钥按 UTF-8 取 16 字节。
 * 也就是说密钥随密文一起下发 —— 自包含，客户端可独立解密。
 */
object AesEcb {

    /** 对 response.data 整体解密；格式不符时返回 null */
    fun decryptSelfContained(payload: String?): String? {
        if (payload == null || payload.length <= 17) return null
        return try {
            val key = payload.substring(0, 16)
            val cipherText = payload.substring(16)
            val raw = Base64.decode(cipherText, Base64.DEFAULT)
            val k: Key = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
            val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, k)
            String(c.doFinal(raw), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
