package com.kang.ubcm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 原生登录页的状态。
 *
 * 🔴 **这里没有、也不能有密码字段**。
 * 密码只作为 [login] 的参数存在一次，方法返回后就没有引用；
 * 不写 prefs、不写日志、不进任何 State。`LoginUiState` 里连一个 `password` 都找不到 ——
 * 这是故意的，让"不小心把它存起来"在编译期就不可能。
 *
 * 坦白一个局限：Java/Kotlin 的 `String` 不可变，**内存里的明文无法主动擦除**，
 * 只能保证不落盘、不外发、活不过这次调用。
 */
class LoginViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val busy: Boolean = false,
        /** 当前在做哪一步（"正在取登录页…"） */
        val status: String? = null,
        /** 失败原因（服务端原话优先） */
        val error: String? = null,
        /** 这次是否要求图形验证码 */
        val captchaRequired: Boolean = false,
        val captchaImage: ByteArray? = null,
        /** 换凭证的逐条结果 */
        val steps: List<NativeLogin.Step> = emptyList(),
        /** 原始日志，排查用 */
        val debug: String = "",
        /** 上一次提交用的账号，失败后回填输入框（**不含密码**） */
        val lastUsername: String = "",
    ) {
        val hasProgress: Boolean get() = steps.isNotEmpty()
    }

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    // 登录页的 lt/execution 与会话绑定，提交时必须用**同一份**。
    // 失败后服务端会把 execution 推进（e1s1→e1s2），所以每次失败都要重新取。
    private var page: CasLogin.LoginPage? = null

    /** 拉（或刷新）登录页，顺便取回验证码图片 */
    fun loadLoginPage() {
        if (_state.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(busy = true, status = "正在打开学校登录页…", error = null)
            val p = CasLogin.fetchLoginPage()
            if (p == null) {
                _state.value = _state.value.copy(
                    busy = false, status = null,
                    error = "连不上学校统一身份认证（cas.bucm.edu.cn）。检查网络后重试。",
                )
                return@launch
            }
            page = p
            val img = if (p.captchaRequired) fetchCaptchaImage(p) else null
            _state.value = _state.value.copy(
                busy = false, status = null,
                captchaRequired = p.captchaRequired,
                captchaImage = img,
            )
        }
    }

    private fun fetchCaptchaImage(p: CasLogin.LoginPage): ByteArray? =
        Net.getBytes(
            "${CasLogin.CAS_BASE}/cas/code?${System.currentTimeMillis()}",
            cookie = p.cookie.ifBlank { null },
        )

    fun refreshCaptcha() {
        val p = page ?: return loadLoginPage()
        viewModelScope.launch(Dispatchers.IO) {
            val img = fetchCaptchaImage(p)
            _state.value = _state.value.copy(captchaImage = img)
        }
    }

    /**
     * 提交登录。
     *
     * ⚠️ **失败后不自动重试**：学校 CAS 有失败计数（实测「连续失败 15 次锁定 10 分钟」），
     * 重试只会加速锁定。一次失败就把原因摆给用户，让他自己决定。
     */
    fun login(username: String, password: String, captcha: String?) {
        if (_state.value.busy) return
        val name = username.trim()
        if (name.isEmpty()) {
            _state.value = _state.value.copy(error = "请填学号")
            return
        }
        if (password.isEmpty()) {
            _state.value = _state.value.copy(error = "请填密码")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(
                busy = true, error = null, status = "正在提交…", steps = emptyList(),
                lastUsername = name,
            )
            try {
                // 每次提交都重新取登录页：lt/execution 一次一换，复用会被判失败
                val p = page?.takeIf { it.captchaRequired == _state.value.captchaRequired }
                    ?: CasLogin.fetchLoginPage()
                if (p == null) {
                    _state.value = _state.value.copy(busy = false, status = null, error = "连不上统一身份认证")
                    return@launch
                }
                page = null   // 用掉就作废，下次重新取

                if (p.captchaRequired && captcha.isNullOrBlank()) {
                    val img = fetchCaptchaImage(p)
                    page = p
                    _state.value = _state.value.copy(
                        busy = false, status = null, captchaRequired = true, captchaImage = img,
                        error = "需要图形验证码",
                    )
                    return@launch
                }

                val r = CasLogin.submit(name, password, p, captcha)
                if (!r.ok) {
                    _state.value = _state.value.copy(
                        busy = false, status = null,
                        error = r.error, captchaRequired = r.needsCaptcha, debug = r.debug,
                    )
                    return@launch
                }

                // 到这里密码已经用完，后面全程只用 CASTGC
                _state.value = _state.value.copy(status = "登录成功，正在换齐凭证…")
                val out = NativeLogin.harvestAll(getApplication(), r.castgc!!)

                _state.value = _state.value.copy(
                    busy = false,
                    status = if (out.ok) "凭证已齐" else "部分凭证未拿到",
                    steps = out.steps,
                    debug = out.log,
                    error = null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busy = false, status = null,
                    error = "${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
