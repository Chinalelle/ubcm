package com.kang.ubcm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CAS 登录加密的测试。
 *
 * ⚠️ 断言里的期望值**全部来自原版 `des.js` 在 node 里的实际输出**，不是我自己算的：
 * ```
 * node -e 'eval(require("fs").readFileSync("des.js","utf8"));
 *          console.log(strEnc("abcd","1","2","3"))'   // → A9CF2704230383D1
 * ```
 * 这样才是「我的实现 == 学校前端」，而不是「我的实现 == 我的实现」。
 */
class CasCryptoTest {

    // ---------- 与 des.js 对拍 ----------

    @Test
    fun `4字符整块与desjs一致`() {
        assertEquals("A9CF2704230383D1", CasCrypto.strEnc("abcd", "1", "2", "3"))
    }

    @Test
    fun `不足4字符自动补零与desjs一致`() {
        assertEquals("39644174795FB4D0", CasCrypto.strEnc("abc", "1", "2", "3"))
    }

    @Test
    fun `空串返回空串`() {
        // 原实现里 `if(leng > 0)` 才处理，空输入直接返回 ""
        assertEquals("", CasCrypto.strEnc("", "1", "2", "3"))
    }

    /** 真实登录串（66 字符 = 16 整块 + 2 字符尾巴），期望值是 node 跑 des.js 得到的 */
    @Test
    fun `真实登录串66字符与desjs逐字节一致`() {
        val lt = "LT-552665-7rWS5ddJNBxg4nBWSv09D1GTUHme2m-cas"
        val data = "20260000000" + "TestPass123" + lt
        assertEquals(66, data.length)
        assertEquals(
            "C052CA90C48D9C76315CB4B8654EACF2114F3790C5034F66E8E3A5569ABEBFB3" +
                "EB93ACA6BF55E3AE1F4ED2A494C1D5D869251D525AA6D47079551ED3AA9BA295" +
                "E9A990D12B7A98FC760727ED7F579201FA04F11DE21A8408651021E5CA0378AF" +
                "8F6FE8538A775332D55EC0F7CC9EE48486B4AA5403A07C09E8968829A83E6104" +
                "9A216BCFE6054686",
            CasCrypto.strEnc(data, "1", "2", "3"),
        )
    }

    @Test
    fun `输出长度等于块数乘16`() {
        for (n in 1..17) {
            val data = "a".repeat(n)
            val expected = Math.ceil(n / 4.0).toInt() * 16
            assertEquals("长度 $n 时", expected, CasCrypto.strEnc(data, "1", "2", "3").length)
        }
    }

    @Test
    fun `输出只含大写hex`() {
        val s = CasCrypto.strEnc("20260000000TestPass", "1", "2", "3")
        assertEquals(true, s.all { it in "0123456789ABCDEF" })
    }


    // ---------- 「不能换成 javax.crypto」的回归断言 ----------

    @Test
    fun `经典向量的结果证明它不是标准DES`() {
        // 经典 DES 测试向量：key=133457799BBCDFF1, pt=0123456789ABCDEF
        //   标准 DES  → 85E813540F0AB405（openssl 验证过）
        //   这个 des.js → CC38B78305003643
        // 如果哪天有人"优化"成 javax.crypto 的 DES/ECB，这条会立刻红。
        val got = DesLegacy.bt64ToHex(
            DesLegacy.enc(
                DesLegacy.hexToBt64("0123456789ABCDEF"),
                DesLegacy.hexToBt64("133457799BBCDFF1"),
            )
        )
        assertEquals("CC38B78305003643", got)
        org.junit.Assert.assertNotEquals("85E813540F0AB405", got)
    }

    @Test
    fun `javax的标准DES给出不同结果所以不能用它`() {
        // 拿同样的字节喂 javax.crypto，结果必然不同 —— 这就是为什么必须自己移植
        val key = ByteArray(8).also { it[1] = 0x31 }
        val data = byteArrayOf(0x00, 0x61, 0x00, 0x62, 0x00, 0x63, 0x00, 0x64)
        val c = javax.crypto.Cipher.getInstance("DES/ECB/NoPadding")
        c.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "DES")
        )
        val std = c.doFinal(data).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        assertEquals("5D4D01A3BDBCFD91", std)          // openssl 也是这个
        // 我们的 des.js 移植对同一块给 4A60B51D4FD386C1
        assertEquals("4A60B51D4FD386C1", DesLegacy.bt64ToHex(DesLegacy.enc(DesLegacy.strToBt("abcd"), DesLegacy.strToBt("1"))))
        org.junit.Assert.assertNotEquals(std, DesLegacy.bt64ToHex(DesLegacy.enc(DesLegacy.strToBt("abcd"), DesLegacy.strToBt("1"))))
    }

    // ---------- 表单三件套 ----------

    @Test
    fun `ul和pl是长度不是值`() {
        // 这是最容易搞错的一处：服务端校验的是长度，提交成值会直接「用户名或密码错误」
        val s = CasCrypto.seal("20260000000", "TestPass123", "LT-1")
        assertEquals(11, s.ul)     // "20260000000".length
        assertEquals(11, s.pl)     // "TestPass123".length
        assertEquals(16 * Math.ceil((11 + 11 + 4) / 4.0).toInt(), s.rsa.length)
    }

    @Test
    fun `seal的rsa等于手工拼串再加密`() {
        val s = CasCrypto.seal("u1", "p1", "LT-9")
        assertEquals(CasCrypto.strEnc("u1" + "p1" + "LT-9", "1", "2", "3"), s.rsa)
    }

    @Test
    fun `中文用户名与密码不会崩`() {
        // 学号密码通常都是 ASCII，但密码里有中文/特殊符号不能抛异常
        val s = CasCrypto.seal("学号", "密码！@#", "LT-1")
        assertEquals(2, s.ul)
        assertEquals(5, s.pl)
        assertEquals(true, s.rsa.length > 0)
    }

    @Test
    fun `key是前端写死的123`() {
        assertEquals("1", CasCrypto.KEY1)
        assertEquals("2", CasCrypto.KEY2)
        assertEquals("3", CasCrypto.KEY3)
    }
}
