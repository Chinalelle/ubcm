package com.kang.ubcm

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 从 Session 读一次凭证快照。
 *
 * 放在 UI 文件里而不是 `Credential.kt`：后者是纯逻辑（可 JVM 单测），
 * 一旦它引用 Context 就再也测不了了。
 */
fun readCredentialSnapshot(ctx: Context): CredentialGate.Snapshot {
    val token = Session.loadToken(ctx)
    return CredentialGate.Snapshot(
        accessToken = token,
        castgc = Session.loadCastgc(ctx),
        jwSession = Session.loadJwSession(ctx),
        ecardSession = Session.loadEcardSession(ctx),
        ecardOpenid = Session.loadEcardOpenid(ctx),
        seatCookie = Session.loadSeatCookie(ctx),
        // 解析不出 exp 时返回 null —— 门禁按「有值」处理，不因畸形 token 把人挡在门外
        jwtRemainingMs = Session.parse(token)?.remainingMs(),
    )
}

/**
 * 凭证与登录管理页（挂在「我的」下）。
 *
 * 定位（2026-11-24 起）：**不再是强制门禁**。冷启动缺凭证时显示的是 [LoginScreen]
 * （可「先跳过」），这里则是完整的补齐入口：原生登录 + 凭证清单 + 手工粘贴 + 网页补座位。
 *
 * 主路径是**原生登录**：在这里输入学号和密码，App 直接和学校 CAS 通信换凭证，
 * 不跳任何网页（2026-11-21 用户要求）。
 *
 * 🔴 红线守住的方式：
 *  - 密码只在 [onLogin] 的入参里活一次，不落盘、不写日志、不进 State
 *  - 不做任何自动重试（学校 CAS 连续失败 15 次锁 10 分钟）
 *  - 「手工粘贴」保留为退路，站点改版时 App 不至于变砖
 */
@Composable
fun LoginGateScreen(
    audit: CredentialGate.Audit,
    login: LoginViewModel.UiState,
    onLoadLoginPage: () -> Unit,
    onLogin: (username: String, password: String, captcha: String?) -> Unit,
    onRefreshCaptcha: () -> Unit,
    onRecheck: () -> Unit,
    onSaveManual: (jwt: String, jw: String, castgc: String, seat: String, ecard: String, openid: String) -> Unit,
    onWebLoginSeat: () -> Unit,
    /** 通用网页登录入口：座位 / 一卡通 / 教务（P11 C6） */
    onWebLogin: (LoginWebViewActivity.Target) -> Unit = { onWebLoginSeat() },
    /** 从「我的」进来时可返回；直接当冷启动登录页时不传 */
    onBack: (() -> Unit)? = null,
) {
    var showManual by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf(login.lastUsername) }
    var password by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }

    // 首次进来先拉一次登录页：拿 lt/execution，顺便探一下要不要验证码
    LaunchedEffect(Unit) { onLoadLoginPage() }

    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 18.dp),
            ) {
                if (onBack != null) {
                    Text(
                        "‹  我的",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = UbcmColors.InkSoft,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onBack() }
                            .padding(vertical = 5.dp, horizontal = 3.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text("凭证与登录", fontSize = 24.sp, fontWeight = FontWeight.Black, color = UbcmColors.Ink, letterSpacing = 1.sp)
                Text("补齐后各页面数据才可用", fontSize = 12.sp, color = UbcmColors.InkSoft)

                Spacer(Modifier.height(14.dp))
                StatusCard(audit)
                Spacer(Modifier.height(12.dp))

                // ---- 登录表单 ----
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(UbcmColors.CardWhite)
                        .border(1.dp, UbcmColors.Line, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("学号 / 密码登录", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = UbcmColors.Ink)
                    Text(
                        "直接和学校统一身份认证通信，不跳网页。\n密码只在本机内存里用一次，不保存、不上传。",
                        fontSize = 10.sp,
                        color = UbcmColors.Ash,
                        lineHeight = 14.sp,
                    )

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        singleLine = true,
                        label = { Text("学号", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text("密码", fontSize = 11.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    )

                    if (login.captchaRequired) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = captcha,
                                onValueChange = { captcha = it },
                                singleLine = true,
                                label = { Text("图形验证码", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f),
                                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .size(width = 92.dp, height = 44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(UbcmColors.Paper)
                                    .border(1.dp, UbcmColors.Line, RoundedCornerShape(8.dp))
                                    .clickable { onRefreshCaptcha() },
                                contentAlignment = Alignment.Center,
                            ) {
                                val bmp = login.captchaImage
                                    ?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
                                if (bmp != null) {
                                    Image(bmp.asImageBitmap(), contentDescription = "验证码", modifier = Modifier.fillMaxSize())
                                } else {
                                    Text("点我刷新", fontSize = 10.sp, color = UbcmColors.Ash)
                                }
                            }
                        }
                    }

                    login.error?.let { err ->
                        Text(
                            err,
                            fontSize = 11.sp,
                            color = UbcmColors.Danger,
                            lineHeight = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(UbcmColors.Danger.copy(alpha = .08f))
                                .padding(10.dp),
                        )
                    }

                    login.status?.let { s ->
                        Text("· $s", fontSize = 11.sp, color = UbcmColors.InkSoft)
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (login.busy) UbcmColors.Ink.copy(alpha = .35f) else UbcmColors.Cinnabar)
                            .clickable(enabled = !login.busy) {
                                if (!login.busy) onLogin(username, password, captcha.takeIf { it.isNotBlank() })
                            }
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (login.busy) "登录中…" else "登录并取齐凭证",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        )
                    }

                    // 换凭证的逐条结果
                    if (login.hasProgress) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            login.steps.forEach { st ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(6.dp).clip(CircleShape)
                                            .background(if (st.ok) UbcmColors.Jade else UbcmColors.Warning)
                                    )
                                    Spacer(Modifier.width(7.dp))
                                    Text(st.label, fontSize = 11.sp, color = UbcmColors.Ink, modifier = Modifier.width(110.dp))
                                    Text(
                                        st.detail,
                                        fontSize = 10.sp,
                                        color = if (st.ok) UbcmColors.InkSoft else UbcmColors.Warning,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ---- 凭证清单 ----
                audit.items.forEach { item ->
                    CredentialRow(item, onLoginTarget = { onWebLoginSeat() })
                    Spacer(Modifier.height(7.dp))
                }

                Spacer(Modifier.height(6.dp))
                BigButton(
                    text = "重新检查",
                    filled = false,
                    onClick = onRecheck,
                )

                Spacer(Modifier.height(10.dp))

                // ---- 退路：手工粘贴 ----
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { showManual = !showManual }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (showManual) "收起手工粘贴" else "手工粘贴凭证（从别处捞的 cookie）",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Cinnabar,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(if (showManual) "▲" else "▼", fontSize = 10.sp, color = UbcmColors.Ash)
                }
                if (showManual) ManualPanel(onSaveManual)

                Spacer(Modifier.height(12.dp))
                // 网页登录：让用户自己在学校页面里登录/过滑块，成功后会收到反馈（P11 C6）
                Text(
                    "网页登录（取图书馆凭证等）",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = UbcmColors.InkSoft,
                )
                Spacer(Modifier.height(6.dp))
                listOf(
                    LoginWebViewActivity.Target.SEAT to "图书馆座位凭证（ic-cookie）",
                    LoginWebViewActivity.Target.ECARD to "一卡通（servicehall）",
                    LoginWebViewActivity.Target.JW to "教务系统（JSESSIONID）",
                ).forEach { (target, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(UbcmColors.Paper)
                            .clickable { onWebLogin(target) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, fontSize = 12.sp, color = UbcmColors.Ink, modifier = Modifier.weight(1f))
                        Text("›", fontSize = 14.sp, color = UbcmColors.Ash)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    "在网页里登录或过完滑块即可，App 只读取登录后的凭证，不保存密码。",
                    fontSize = 10.sp,
                    color = UbcmColors.Ash,
                    lineHeight = 15.sp,
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    "这是测试软件，不是产品：只用来验底层接口和算法，数据只存本机。\n" +
                        "不代登录、不存密码。密码只在内存里用一次。",
                    fontSize = 10.sp, color = UbcmColors.Ash, lineHeight = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(audit: CredentialGate.Audit) {
    val tone = if (audit.allOk) UbcmColors.Jade else UbcmColors.Danger
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (audit.allOk) "凭证齐全" else "缺 ${audit.missingRequired.size} 条必需凭证",
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = tone,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                if (audit.allOk) "可以开始测试"
                else "缺凭证时跑测试会一片红，分不清是接口坏了还是没登录，所以先挡住",
                fontSize = 11.sp, color = UbcmColors.InkSoft, lineHeight = 15.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(tone.copy(alpha = .12f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("${audit.okCount} / ${audit.items.size}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = tone)
        }
    }
}

@Composable
private fun CredentialRow(item: CredentialGate.Item, onLoginTarget: () -> Unit) {
    val tone = when {
        item.ok -> UbcmColors.Jade
        item.present && item.problem != null -> UbcmColors.Warning
        !item.required -> UbcmColors.Ash
        else -> UbcmColors.Danger
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = UbcmColors.Ink)
                Spacer(Modifier.width(6.dp))
                if (!item.required) Text("可选", fontSize = 9.sp, color = UbcmColors.Ash)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                item.problem ?: item.hint,
                fontSize = 10.sp,
                color = if (item.problem != null) UbcmColors.Warning else UbcmColors.InkSoft,
                lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (item.ok) {
            Text("有", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = UbcmColors.Jade)
        } else if (item.id == "seat_cookie") {
            // 只有座位这一条可能还需要网页（ic-cookie 的 native 路径不稳，见 docs/13）
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(tone.copy(alpha = .12f))
                    .clickable { onLoginTarget() }
                    .padding(horizontal = 9.dp, vertical = 6.dp)
            ) {
                Text("网页补", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = tone)
            }
        }
    }
}

@Composable
private fun BigButton(text: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (filled) UbcmColors.Cinnabar else UbcmColors.CardWhite)
            .border(1.dp, if (filled) UbcmColors.Cinnabar else UbcmColors.Line, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (filled) Color.White else UbcmColors.InkSoft)
    }
}

/**
 * 手工粘贴。给「凭证只能从别处捞」的场景留的后路 ——
 * 没有这条路，登录站点一变更，整个 App 就彻底进不去了。
 */
@Composable
private fun ManualPanel(
    onSave: (String, String, String, String, String, String) -> Unit,
) {
    var jwt by remember { mutableStateOf("") }
    var jw by remember { mutableStateOf("") }
    var castgc by remember { mutableStateOf("") }
    var seat by remember { mutableStateOf("") }
    var ecard by remember { mutableStateOf("") }
    var openid by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(12.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("粘进来的值直接覆盖本机已有的，空着不动", fontSize = 10.sp, color = UbcmColors.Ash)
        PasteField("门户 accessToken（JWT）", jwt) { jwt = it }
        PasteField("教务 cookie（JSESSIONID=...; jsxsd=...）", jw) { jw = it }
        PasteField("CASTGC（TGT-...）", castgc) { castgc = it }
        PasteField("一卡通 servicehall cookie", ecard) { ecard = it }
        PasteField("一卡通 openid（数字，可选）", openid) { openid = it }
        PasteField("图书馆 ic-cookie", seat) { seat = it }
        Spacer(Modifier.height(2.dp))
        BigButton(text = "保存并重新检查", filled = true) {
            onSave(jwt, jw, castgc, seat, ecard, openid)
        }
    }
}

@Composable
private fun PasteField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 10.sp) },
        minLines = 1,
        maxLines = 3,
        modifier = Modifier.fillMaxWidth(),
        textStyle = LocalTextStyle.current.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
    )
}
