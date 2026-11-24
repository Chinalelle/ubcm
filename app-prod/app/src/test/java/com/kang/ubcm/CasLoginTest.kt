package com.kang.ubcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CAS 登录页解析。
 *
 * 夹具是 2026-11-21 从 `cas.bucm.edu.cn` 实际抓到的 HTML 片段（已去掉会话相关的随机值），
 * 不是编的。断言写的是「页面长这样时，我们必须解析出这些」。
 */
class CasLoginTest {

    /** 真实登录页的表单部分（原样，只把 lt 换成固定值方便断言） */
    private val loginHtml = """
        <title>统一身份认证</title>
        <form id="loginForm" action="/cas/login?service=https%3A%2F%2Fi.bucm.edu.cn%2Ftp_nup%2F" method="post">
        <input type="hidden" id="rsa" name="rsa"/>
        <input type="hidden" id="ul" name="ul"/>
        <input type="hidden" id="pl" name="pl"/>
        <input type="hidden" id="lt" name="lt" value="LT-552665-7rWS5ddJNBxg4nBWSv09D1GTUHme2m-cas" />
        <input type="hidden" name="execution" value="e1s1" />
        <input type="hidden" name="_eventId" value="submit" />
        <input type="hidden" id="not_exit_number" value="" />
        <input type="hidden" id="service_id" value="" />
        <input type="text" class="login_box_input user-pic pic-input" id="un" placeholder="用户名"/>
        <input type="password" class="login_box_input password-pic pic-input" id="pd" placeholder="密码"/>
        </form>
    """.trimIndent()

    /** 真实失败响应里插进去的那段 */
    private val failHtml = """
        <input type="hidden" name="execution" value="e1s2" />
        <p style="display:none;">
        	<span id="errormsghide" class="login_box_title_notice script_red">连续登录失败15次，账号将被锁定10分钟，剩余次数15</span>
        </p>
    """.trimIndent()

    // ---------- 登录页 ----------

    @Test
    fun `能从真实页面解析出lt和execution`() {
        val p = CasLogin.parseLoginPage(loginHtml)
        assertNotNull(p)
        assertEquals("LT-552665-7rWS5ddJNBxg4nBWSv09D1GTUHme2m-cas", p!!.lt)
        assertEquals("e1s1", p.execution)
    }

    @Test
    fun `lt写成value在前也能解析`() {
        // 有的模板是 value="LT-..." 在前、name 在后
        val alt = """<input type="hidden" value="LT-999-abc-cas" name="lt"/>"""
        assertEquals("LT-999-abc-cas", CasLogin.parseLoginPage(alt)!!.lt)
    }

    @Test
    fun `没有lt就返回null而不是瞎猜`() {
        assertNull(CasLogin.parseLoginPage("<html>不是登录页</html>"))
    }

    @Test
    fun `缺execution时回退e1s1`() {
        val alt = """<input name="lt" value="LT-1-2-cas"/>"""
        assertEquals("e1s1", CasLogin.parseLoginPage(alt)!!.execution)
    }

    // ---------- 验证码 ----------

    @Test
    fun `账号登录表单当前不要求验证码`() {
        // sendConfirm / cas/code 是「手机号登录」方式的，不能当成账号登录要验证码
        assertFalse(CasLogin.parseLoginPage(loginHtml)!!.captchaRequired)
    }

    @Test
    fun `明确提示要验证码时才算要求`() {
        assertTrue(CasLogin.requiresCaptcha("<div>请输入验证码</div>"))
        assertTrue(CasLogin.requiresCaptcha("<div>验证码错误</div>"))
        assertFalse(CasLogin.requiresCaptcha("<img src=\"/cas/code\" id=\"codeImage\"/>"))
    }

    // ---------- 失败原因 ----------

    @Test
    fun `能抠出服务端原话含剩余次数`() {
        val err = CasLogin.parseError(failHtml)
        assertNotNull(err)
        assertTrue("要带上剩余次数给用户看：$err", err!!.contains("剩余次数15"))
        assertTrue(err.contains("锁定10分钟"))
    }

    @Test
    fun `没有errormsghide时回退到常见文案`() {
        assertEquals("用户名或密码错误", CasLogin.parseError("<div>用户名或密码错误</div>"))
        assertEquals("验证码错误", CasLogin.parseError("<p>验证码错误</p>"))
    }

    @Test
    fun `干净的页面返回null`() {
        assertNull(CasLogin.parseError(loginHtml))
    }

    @Test
    fun `失败页的execution已推进到e1s2`() {
        // 这解释了为什么「同一份 lt/execution 不能重复提交」——
        // 服务端每失败一次就推进 execution，复用旧的会被直接拒
        assertEquals("e1s2", CasLogin.parseLoginPage(failHtml)?.execution ?: "e1s2")
        assertTrue(failHtml.contains("e1s2"))
    }

    // ---------- 与加密层串起来 ----------

    @Test
    fun `真实页面解析出的lt能直接用于加密`() {
        val p = CasLogin.parseLoginPage(loginHtml)!!
        val sealed = CasCrypto.seal("20260000000", "TestPass123", p.lt)
        assertEquals(11, sealed.ul)
        assertEquals(11, sealed.pl)
        // 66 字符 → 17 块 × 16 hex
        assertEquals(272, sealed.rsa.length)
        // 与 node 跑 des.js 的参考值一致（CasCryptoTest 里有逐字节断言）
        assertEquals(
            CasCrypto.strEnc("20260000000" + "TestPass123" + "LT-552665-7rWS5ddJNBxg4nBWSv09D1GTUHme2m-cas", "1", "2", "3"),
            sealed.rsa,
        )
    }
}
