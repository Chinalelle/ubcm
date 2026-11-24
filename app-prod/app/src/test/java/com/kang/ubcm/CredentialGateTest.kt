package com.kang.ubcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录门禁判定。
 *
 * 这一层错了的后果不是"界面难看"，而是**整个测试结果失去意义**：
 * 门禁漏放 → 缺凭证还能进体检 → 一片红，分不清接口坏了还是没登录；
 * 门禁过严 → 用户永远进不去 → 软件变砖。
 * 两个方向都要有断言。
 */
class CredentialGateTest {

    /** 五条全齐、JWT 还剩 3 小时 */
    private fun full() = CredentialGate.Snapshot(
        accessToken = "eyJhbGciOi.payload.sig",
        castgc = "TGT-1234-cas",
        jwSession = "JSESSIONID=ABC; jsxsd=1",
        ecardSession = "servicehall=XYZ",
        ecardOpenid = "53247",
        seatCookie = "ic-cookie=SEAT1",
        jwtRemainingMs = 3 * 3600_000L,
    )

    private fun item(a: CredentialGate.Audit, id: String) = a.items.first { it.id == id }

    // ---------- 放行 ----------

    @Test
    fun `五条齐了才放行`() {
        val a = CredentialGate.audit(full())
        assertTrue(a.allOk)
        assertTrue(a.missingRequired.isEmpty())
        assertNull("全齐时没有下一个目标", a.nextTarget)
    }

    @Test
    fun `六项里五条必需`() {
        val a = CredentialGate.audit(full())
        assertEquals(6, a.items.size)
        assertEquals(5, a.requiredCount)
        assertEquals(6, a.okCount)
    }

    @Test
    fun `openid 缺了也放行`() {
        // openid 是派生值（落地页隐藏 input），抓取时机不对就可能没有。
        // 设成硬门槛会让用户永远过不了门禁 —— 而一卡通刷新本来就会自动补。
        val a = CredentialGate.audit(full().copy(ecardOpenid = null))
        assertTrue(a.allOk)
        assertFalse(item(a, "ecard_openid").present)
        assertFalse("openid 不该是必需", item(a, "ecard_openid").required)
    }

    @Test
    fun `openid 为空串等同缺失`() {
        val a = CredentialGate.audit(full().copy(ecardOpenid = "   "))
        assertTrue(a.allOk)
        assertFalse(item(a, "ecard_openid").present)
    }

    // ---------- 拦截：逐条缺 ----------

    @Test
    fun `缺 accessToken 要拦且指向门户`() {
        val a = CredentialGate.audit(full().copy(accessToken = null))
        assertFalse(a.allOk)
        assertEquals("PORTAL", a.nextTarget)
        assertEquals(listOf("access_token"), a.missingRequired.map { it.id })
    }

    @Test
    fun `缺 CASTGC 也要拦且仍指向门户`() {
        // CASTGC 由门户登录顺带保存，所以它缺了还是去登门户
        val a = CredentialGate.audit(full().copy(castgc = null))
        assertFalse(a.allOk)
        assertEquals("PORTAL", a.nextTarget)
        assertEquals(listOf("castgc"), a.missingRequired.map { it.id })
    }

    @Test
    fun `缺教务会话指向教务`() {
        val a = CredentialGate.audit(full().copy(jwSession = null))
        assertFalse(a.allOk)
        assertEquals("JW", a.nextTarget)
    }

    @Test
    fun `缺一卡通会话指向一卡通`() {
        val a = CredentialGate.audit(full().copy(ecardSession = null))
        assertFalse(a.allOk)
        assertEquals("ECARD", a.nextTarget)
    }

    @Test
    fun `缺座位cookie指向座位`() {
        val a = CredentialGate.audit(full().copy(seatCookie = null))
        assertFalse(a.allOk)
        assertEquals("SEAT", a.nextTarget)
    }

    @Test
    fun `全空时从门户开始`() {
        val a = CredentialGate.audit(CredentialGate.Snapshot())
        assertFalse(a.allOk)
        assertEquals(5, a.missingRequired.size)   // 5 条必需全缺
        assertEquals(0, a.okCount)
        assertEquals("PORTAL", a.nextTarget)
    }

    // ---------- JWT 过期 ----------

    @Test
    fun `JWT 过期等同没有`() {
        // 这不是字面意义的"没有"，但过期凭证用不了：
        // 待办/应用清单接口会 401，跑出来的红点和接口坏了无法区分。
        val a = CredentialGate.audit(full().copy(jwtRemainingMs = 0))
        assertFalse(a.allOk)
        assertEquals("PORTAL", a.nextTarget)
        assertEquals("已过期，点右边重新登", item(a, "access_token").problem)
    }

    @Test
    fun `JWT 还剩一秒也算有`() {
        val a = CredentialGate.audit(full().copy(jwtRemainingMs = 1))
        assertTrue(a.allOk)
    }

    @Test
    fun `JWT 过期问题文案不含 markdown 星号`() {
        // 上一轮踩过：文案里写了 **加粗**，Compose 不解析，真机上直接显示星号
        val p = CredentialGate.audit(full().copy(jwtRemainingMs = -1)).items
            .first { it.id == "access_token" }.problem
        assertTrue(p!!.isNotEmpty())
        assertFalse("文案里不能有 markdown：$p", p.contains("*"))
    }

    @Test
    fun `无法解析 exp 时按有值处理`() {
        // 解析不出 exp（jwtRemainingMs=null）不能当成过期，否则一个畸形 token 会把用户挡在门外
        val a = CredentialGate.audit(full().copy(jwtRemainingMs = null))
        assertTrue(a.allOk)
    }

    // ---------- 下一个目标 ----------

    @Test
    fun `缺两条以上时按门户教务一卡通座位的顺序给第一个`() {
        // 用户点「一键补全」时，顺序必须是 PORTAL 先 —— 后面三个靠它存的 CASTGC 自动 SSO
        val a = CredentialGate.audit(full().copy(accessToken = null, jwSession = null, seatCookie = null))
        assertEquals("PORTAL", a.nextTarget)
        assertEquals(
            listOf("access_token", "jw_jsessionid", "seat_cookie"),
            a.missingRequired.map { it.id },
        )
    }

    @Test
    fun `门户和教务都齐但差一卡通和座位时先给一卡通`() {
        val a = CredentialGate.audit(full().copy(ecardSession = null, seatCookie = null))
        assertEquals("ECARD", a.nextTarget)
    }

    @Test
    fun `每个检查项都要有能补它的目标`() {
        val legal = setOf("PORTAL", "JW", "ECARD", "SEAT")
        for (it in CredentialGate.audit(full()).items) {
            assertTrue("${it.id} 的 targetName=${it.targetName} 不是合法目标", it.targetName in legal)
        }
    }

    @Test
    fun `每条都有给人看的说明`() {
        for (it in CredentialGate.audit(full()).items) {
            assertTrue("${it.id} 缺 label", it.label.isNotBlank())
            assertTrue("${it.id} 缺 hint", it.hint.isNotBlank())
        }
    }
}
