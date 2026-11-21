package com.kang.ubcm

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 * 登录门禁页。**凭证不齐时 App 唯一能看到的页面。**
 *
 * 三种补齐方式，缺一不可：
 *  1. 一键补全：按 PORTAL → JW → ECARD → SEAT 顺序自动串起来（后三个靠 CASTGC 自动 SSO）
 *  2. 单项去登录：哪条缺点哪条
 *  3. 手工粘贴：某些凭证只能从别处捞（比如浏览器里已有的 cookie），必须留这条路，
 *     否则门禁会把人锁死在门外
 */
@Composable
fun LoginGateScreen(
    audit: CredentialGate.Audit,
    onLoginTarget: (String) -> Unit,
    onAutoFill: () -> Unit,
    onRecheck: () -> Unit,
    onSaveManual: (jwt: String, jw: String, castgc: String, seat: String, ecard: String, openid: String) -> Unit,
) {
    var showManual by remember { mutableStateOf(false) }

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
                Text("UBcm 测试台", fontSize = 24.sp, fontWeight = FontWeight.Black, color = UbcmColors.Ink, letterSpacing = 1.sp)
                Text("先登录，再测接口", fontSize = 12.sp, color = UbcmColors.InkSoft)

                Spacer(Modifier.height(14.dp))

                // 总状态
                val tone = if (audit.allOk) UbcmColors.Jade else UbcmColors.Cinnabar
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
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = tone,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (audit.allOk) "可以开始测试"
                            else "缺凭证时跑测试会一片红，分不清是接口坏了还是没登录，所以先挡住",
                            fontSize = 11.sp,
                            color = UbcmColors.InkSoft,
                            lineHeight = 15.sp,
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

                Spacer(Modifier.height(12.dp))

                // 逐条清单
                audit.items.forEach { item ->
                    CredentialRow(item, onLoginTarget)
                    Spacer(Modifier.height(7.dp))
                }

                Spacer(Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        BigButton(
                            text = if (audit.allOk) "重新检查" else "一键补全缺失",
                            filled = !audit.allOk,
                            onClick = { if (audit.allOk) onRecheck() else onAutoFill() },
                        )
                    }
                    if (audit.allOk) {
                        Box(Modifier.weight(1f)) {
                            BigButton(text = "重新检查", filled = false, onClick = onRecheck)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 手工粘贴：门禁的最后一条退路
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { showManual = !showManual }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (showManual) "收起手工粘贴" else "手工粘贴凭证（从别处捞的 cookie）",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = UbcmColors.Cinnabar,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(if (showManual) "▲" else "▼", fontSize = 10.sp, color = UbcmColors.Ash)
                }

                if (showManual) {
                    ManualPanel(onSaveManual)
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "这是测试软件，不是产品：只用来验底层接口和算法，数据只存本机。\n" +
                        "不代登录、不存密码。账号密码只在学校的登录页里输入。",
                    fontSize = 10.sp,
                    color = UbcmColors.Ash,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun CredentialRow(item: CredentialGate.Item, onLoginTarget: (String) -> Unit) {
    val tone = when {
        item.ok -> UbcmColors.Jade
        item.present && item.problem != null -> UbcmColors.Amber
        !item.required -> UbcmColors.Ash
        else -> UbcmColors.Cinnabar
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
                if (!item.required) {
                    Text("可选", fontSize = 9.sp, color = UbcmColors.Ash)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                item.problem ?: item.hint,
                fontSize = 10.sp,
                color = if (item.problem != null) UbcmColors.Amber else UbcmColors.InkSoft,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (item.ok) {
            Text("有", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = UbcmColors.Jade)
        } else {
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(tone)
                    .clickable { onLoginTarget(item.targetName) }
                    .padding(horizontal = 11.dp, vertical = 6.dp)
            ) {
                Text(if (item.problem != null) "重登" else "去登录", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (filled) Color.White else UbcmColors.InkSoft,
        )
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
