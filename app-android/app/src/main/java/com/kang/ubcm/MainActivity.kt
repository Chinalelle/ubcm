package com.kang.ubcm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

// ---------- 配色：暖白 + 朱砂，刻意避开 Material 默认紫 ----------
private val Ink = Color(0xFF1E1B17)
private val InkSoft = Color(0xFF6B6359)
private val Paper = Color(0xFFFBF7F0)
private val CardWhite = Color(0xFFFFFFFF)
private val Line = Color(0xFFEDE3D4)
private val Cinnabar = Color(0xFFB33A2B)
private val Jade = Color(0xFF2E7D5B)
private val Amber = Color(0xFFC98A1F)
private val Ash = Color(0xFF9A9A94)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 教务站证书链缺中间证书，必须先装好补链 TrustManager，否则课表请求会 SSLHandshakeException
        Tls.install(this)
        enableEdgeToEdge()
        // 调试注入。注意：adb shell 会把参数拼成一条命令再在设备上重新解析，
        // 所以含 ";" 的 cookie 必须走 base64，否则会被当命令分隔符截断（踩过）。
        readB64("jwt_b64")?.let { Session.saveToken(this, it) }
        readB64("jw_b64")?.let { Session.saveJwSession(this, it) }
        readB64("castgc_b64")?.let { Session.saveCastgc(this, it) }
        readB64("seat_b64")?.let { Session.saveSeatCookie(this, it) }
        // 兼容没有分号的短值
        intent?.getStringExtra("jwt")?.let { Session.saveToken(this, it) }
        setContent { AppRoot() }
    }

    private fun readB64(key: String): String? {
        val raw = intent?.getStringExtra(key) ?: return null
        return try {
            String(android.util.Base64.decode(raw, android.util.Base64.NO_WRAP), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 四个页面：首页（聚合卡片）/ 课表周视图 / 图书馆 / 数据体检页（探针 + 登录面板）。
 * 不引 Navigation 库 —— 四个页面一个状态位就够，保持零依赖。
 *
 * ⚠️ 这些都**在登录门禁之后**：凭证不齐时根本走不到这里（见 [AppRoot]）。
 */
private enum class Page { HOME, WEEK, LIBRARY, PROBE }

/**
 * 根组件 = **登录门禁** + 门禁后的主内容。
 *
 * 定位（2026-11-21 用户明确）：这是测试软件，用来验底层接口和算法，不是产品。
 * 所以打开第一件事是「凭证齐不齐」，缺任何一条必需凭证都强制留在登录页。
 *
 * 登录方式（2026-11-21 改）：**原生表单**，输入学号密码 → 直接和 CAS 通信换 CASTGC
 * → 纯 native SSO 换齐其余凭证。**不跳任何网页**。
 * 只有座位 `ic-cookie` 保留一个网页补登的例外（native 路径不稳，见 docs/13）。
 *
 * 🔴 密码只经过 `loginVm.login()` 一次，不落盘、不写日志。
 */
@Composable
private fun AppRoot() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val loginVm: LoginViewModel = viewModel()
    val loginState by loginVm.state.collectAsStateWithLifecycle()
    var gateKey by remember { mutableStateOf(0) }
    // 静默补座位只自动跑一次，避免拿不到时反复弹
    var silentSeatTried by remember { mutableStateOf(false) }

    val audit = remember(gateKey) { CredentialGate.audit(readCredentialSnapshot(ctx)) }

    // 座位 ic-cookie 拿不到纯 native 版本，只能让前端 SPA 跑一次（不可见 WebView）
    val silentSeatLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { gateKey++ }

    // 换凭证跑完就重读 Session：齐了会自动切到主内容
    LaunchedEffect(loginState.steps) {
        if (loginState.steps.isEmpty()) return@LaunchedEffect
        gateKey++
        val seatStep = loginState.steps.firstOrNull { it.label == "图书馆座位" }
        if (seatStep != null && !seatStep.ok && !silentSeatTried) {
            silentSeatTried = true
            silentSeatLauncher.launch(SilentSeatLoginActivity.newIntent(ctx))
        }
    }

    // 座位用网页补登（唯一保留的例外），回来重读
    val seatLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { gateKey++ }

    if (!audit.allOk) {
        LoginGateScreen(
            audit = audit,
            login = loginState,
            onLoadLoginPage = { loginVm.loadLoginPage() },
            onLogin = { u, p, c -> loginVm.login(u, p, c) },
            onRefreshCaptcha = { loginVm.refreshCaptcha() },
            onRecheck = { gateKey++ },
            onSaveManual = { jwt, jw, castgc, seat, ecard, openid ->
                // 空着的字段不动，只覆盖用户真的粘了东西的那些
                if (jwt.isNotBlank()) Session.saveToken(ctx, jwt)
                if (jw.isNotBlank()) Session.saveJwSession(ctx, jw)
                if (castgc.isNotBlank()) Session.saveCastgc(ctx, castgc)
                if (seat.isNotBlank()) Session.saveSeatCookie(ctx, seat)
                if (ecard.isNotBlank()) Session.saveEcard(ctx, ecard, openid.ifBlank { null })
                else if (openid.isNotBlank()) Session.saveEcardOpenid(ctx, openid)
                gateKey++
            },
            // 座位这一条：后台不可见 WebView 补齐（用户看不到网页）。
            // 拿不到再让他走可见的网页登录 —— 那是最后的兜底。
            onWebLoginSeat = {
                silentSeatLauncher.launch(SilentSeatLoginActivity.newIntent(ctx))
            },
        )
        return
    }

    MainContent(
        onLogout = {
            Session.clearCredentials(ctx)
            // WebView 的学校会话也要清，否则重新用「网页补座位」时会带着旧会话直接进去
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
            loginVm.reset()
            gateKey++          // 重算 audit → 凭证不齐 → 自动回到登录页
        }
    )
}

@Composable
private fun MainContent(onLogout: () -> Unit) {
    val homeVm: HomeViewModel = viewModel()
    val probeVm: ProbeViewModel = viewModel()
    val libVm: LibraryViewModel = viewModel()
    // 测试软件落到「数据体检」而不是首页：这里有 18 个探针，是真正要看的东西
    var page by remember { mutableStateOf(Page.PROBE) }
    var showAuth by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    when (page) {
        Page.HOME -> HomeScreen(
            vm = homeVm,
            onOpenProbe = { page = Page.PROBE },
            onOpenLogin = {
                showAuth = true
                page = Page.PROBE
            },
            onOpenLibrary = { page = Page.LIBRARY },
            onOpenWeek = { page = Page.WEEK },
            onLogout = onLogout,
        )

        Page.WEEK -> WeekScreen(
            vm = homeVm,
            onBack = { page = Page.HOME },
            onOpenProbe = { page = Page.PROBE },
        )

        Page.LIBRARY -> LibraryScreen(
            vm = libVm,
            onBack = { page = Page.HOME },
            // 座位登录本身就带 ic-cookie 抓取，直接进这个目标
            onOpenLogin = { LoginWebViewActivity.start(ctx, LoginWebViewActivity.Target.SEAT) },
            onOpenSchoolPage = {
                SchoolWebActivity.start(
                    ctx,
                    "https://kjyy.bucm.edu.cn/mobile.html",
                    "图书馆座位预约",
                    hashAfterLoad = "#/seatReserve",
                )
            },
            onOpenRoom = { roomId, roomName ->
                // 直达该区域的选座页：学校页面认 roomId 这个 query
                SchoolWebActivity.start(
                    ctx,
                    "https://kjyy.bucm.edu.cn/mobile.html",
                    roomName,
                    hashAfterLoad = "#/seatReserve?roomId=$roomId",
                )
            },
        )

        Page.PROBE -> Screen(
            vm = probeVm,
            initialShowAuth = showAuth,
            onLogout = onLogout,
            onBack = {
                page = Page.HOME
                showAuth = false
                // 从体检页回来时刷新一次首页（可能刚登录过）
                homeVm.refresh(false)
                libVm.refresh()
            },
        )
    }
}

@Composable
private fun Screen(
    vm: ProbeViewModel = viewModel(),
    initialShowAuth: Boolean = false,
    onLogout: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val st by vm.state.collectAsStateWithLifecycle()
    var showAuth by remember { mutableStateOf(initialShowAuth) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }

    Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Paper,
            bottomBar = {
                RunBar(running = st.running,
                    enabled = st.token != null,
                    onClick = { vm.runAll() })
            },
        ) { pad ->
            Box(
                Modifier.fillMaxSize().padding(pad),
                contentAlignment = Alignment.TopCenter,
            ) {
            LazyColumn(
                modifier = Modifier.fillMaxHeight().widthIn(max = 720.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { BackRow(onBack, onLogout = onLogout) }
                item { Header(st, onAuth = { showAuth = !showAuth }) }

                // 一卡通是 WebView 承载的，单独给一个明显的入口
                if (st.castgc != null) {
                    item { EcardEntry() }
                }

                if (showAuth) {
                    item {
                        AuthPanel(
                            st,
                            onJwt = vm::setToken,
                            onJw = vm::setJwCookie,
                            onCastgc = vm::setCastgc,
                            onClear = vm::clear,
                        )
                    }
                }

                val groups = st.probes.groupBy { it.group }
                groups.forEach { (group, list) ->
                    item(key = "g-$group") { GroupHeader(group, list) }
                    items(list, key = { it.id }) { p ->
                        ProbeCard(
                            p = p,
                            expanded = p.id in expanded,
                            onClick = {
                                expanded = if (p.id in expanded) expanded - p.id else expanded + p.id
                            },
                        )
                    }
                }
                item { FootNote() }
            }
            }
        }
    }
}

/**
 * 顶部一行：返回首页 + 退出登录。
 *
 * 退出登录用**点两次确认**而不是弹窗 —— 和项目里其它交互保持一致（不弹窗），
 * 而且误触就清空全部凭证、要重新输密码，值得挡一下。
 */
@Composable
private fun BackRow(onBack: () -> Unit, onLogout: () -> Unit = {}) {
    var confirming by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "‹  返回首页",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = InkSoft,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onBack() }
                .padding(vertical = 5.dp, horizontal = 3.dp),
        )
        Spacer(Modifier.weight(1f))
        if (!confirming) {
            Text(
                "退出登录",
                fontSize = 12.sp,
                color = Cinnabar,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { confirming = true }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("清空所有凭证？", fontSize = 11.sp, color = InkSoft)
                Spacer(Modifier.width(6.dp))
                Text(
                    "确认退出",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Cinnabar)
                        .clickable { onLogout() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "取消",
                    fontSize = 11.sp,
                    color = Ash,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { confirming = false }
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(st: UiState, onAuth: () -> Unit) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "数据体检",
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Black,
                    color = Ink,
                    letterSpacing = 1.sp,
                )
                Text(
                    "UBcm · 安卓端最小可执行框架",
                    fontSize = 12.sp,
                    color = InkSoft,
                    letterSpacing = 0.6.sp,
                )
            }
            // 三态：能解析 ≠ 没过期。JWT 只有 4 小时，过期了要明确说"已过期"而不是"已登录"
            val jwtValid = st.identity?.let { it.remainingMs() > 0 } == true
            val authTone = when {
                jwtValid -> Jade
                st.identity != null -> Amber
                else -> Cinnabar
            }
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(authTone.copy(alpha = .12f))
                    .clickable { onAuth() }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    when {
                        jwtValid -> "已登录"
                        st.identity != null -> "已过期"
                        else -> "未登录"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = authTone,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        IdentityStrip(st)
    }
}

@Composable
private fun EcardEntry() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardWhite)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .clickable { EcardActivity.start(ctx) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("一卡通服务大厅", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("余额 · 流水 · 充值 · 挂失（学校平台）", fontSize = 11.sp, color = InkSoft)
        }
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Cinnabar).padding(horizontal = 12.dp, vertical = 6.dp)
        ) { Text("打开", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White) }
    }
}

@Composable
private fun IdentityStrip(st: UiState) {
    val id = st.identity
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardWhite)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (id == null) {
            Column {
                Text("还没有凭证", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Text("点右上角「未登录」粘贴 accessToken", fontSize = 12.sp, color = InkSoft)
            }
        } else {
            Column(Modifier.weight(1f)) {
                Text(id.name.ifBlank { "（无名）" }, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    "${id.studentNo}   ·   ${id.cardType.ifBlank { "-" }} / ${id.cardId.ifBlank { "-" }}",
                    fontSize = 12.sp,
                    color = InkSoft,
                    fontFamily = FontFamily.Monospace,
                )
            }
            val left = id.remainingMs()
            Column(horizontalAlignment = Alignment.End) {
                Text(if (left > 0) "剩 ${ProbeViewModel.fmtDuration(left)}" else "已过期",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (left > 0) Jade else Cinnabar)
                Text(ProbeViewModel.fmtTime(id.expAt), fontSize = 10.sp, color = Ash, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun AuthPanel(
    st: UiState,
    onJwt: (String) -> Unit,
    onJw: (String) -> Unit,
    onCastgc: (String) -> Unit,
    onClear: () -> Unit,
) {
    var jwt by remember { mutableStateOf(st.token.orEmpty()) }
    var jw by remember { mutableStateOf(st.jwCookie.orEmpty()) }
    var castgc by remember { mutableStateOf(st.castgc.orEmpty()) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardWhite)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("凭证", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
        OutlinedTextField(
            value = jwt, onValueChange = { jwt = it },
            label = { Text("accessToken（门户 JWT）", fontSize = 11.sp) },
            minLines = 2, maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
        )
        OutlinedTextField(
            value = jw, onValueChange = { jw = it },
            label = { Text("JW 的 Cookie（JSESSIONID=...）", fontSize = 11.sp) },
            minLines = 1, maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
        )
        OutlinedTextField(
            value = castgc, onValueChange = { castgc = it },
            label = { Text("CASTGC（一卡通/教务的 CAS 票据）", fontSize = 11.sp) },
            minLines = 1, maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onJwt(jwt); onJw(jw); onCastgc(castgc) }, colors = ButtonDefaults.buttonColors(containerColor = Cinnabar)) {
                Text("保存", fontSize = 12.sp)
            }
            TextButton(onClick = onClear) { Text("清空", fontSize = 12.sp, color = InkSoft) }
        }
        Text(
            "也可直接在 APP 内登录（推荐）：",
            fontSize = 11.sp, color = InkSoft,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "数智北中医" to LoginWebViewActivity.Target.PORTAL,
                "教务系统" to LoginWebViewActivity.Target.JW,
                "一卡通" to LoginWebViewActivity.Target.ECARD,
                "图书馆座位" to LoginWebViewActivity.Target.SEAT,
            ).forEach { (label, target) ->
                OutlinedButton(
                    onClick = { LoginWebViewActivity.start(ctx, target) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text(label, fontSize = 11.sp) }
            }
        }
        Text(
            "或手动粘贴：JWT=「accessToken」／教务=JSESSIONID／一卡通=CASTGC",
            fontSize = 10.sp, color = Ash, lineHeight = 14.sp,
        )
    }
}

@Composable
private fun GroupHeader(group: String, list: List<Probe>) {
    val ok = list.count { it.status == Status.OK }
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(group, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Cinnabar, letterSpacing = 1.4.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.height(1.dp).weight(1f).background(Line))
        Spacer(Modifier.width(8.dp))
        Text("$ok/${list.size}", fontSize = 11.sp, color = Ash, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ProbeCard(p: Probe, expanded: Boolean, onClick: () -> Unit) {
    val accent = when (p.status) {
        Status.OK -> Jade
        Status.EMPTY -> Amber
        Status.FAIL -> Cinnabar
        Status.NOAUTH -> Ash
        Status.RUNNING -> Amber
        Status.IDLE -> Line
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardWhite)
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .clickable { onClick() },
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.width(8.dp))
                Badge(p.status)
                p.route?.let { rt ->
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp))
                            .background(if (rt == "WEBVPN") Amber.copy(alpha = .15f) else Jade.copy(alpha = .12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            if (rt == "WEBVPN") "VPN" else "直连",
                            fontSize = 8.sp, fontWeight = FontWeight.SemiBold,
                            color = if (rt == "WEBVPN") Amber else Jade,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (p.ms > 0) Text("${p.ms}ms", fontSize = 10.sp, color = Ash, fontFamily = FontFamily.Monospace)
            }
            if (p.summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(p.summary, fontSize = 12.sp, color = InkSoft, lineHeight = 16.sp)
            } else if (p.status == Status.IDLE) {
                Spacer(Modifier.height(3.dp))
                Text(p.hint, fontSize = 11.sp, color = Ash)
            }
            if (expanded) {
                if (p.fields.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    p.fields.forEach { (k, v) ->
                        Row(Modifier.padding(vertical = 1.dp)) {
                            Text(k, fontSize = 11.sp, color = Ash, modifier = Modifier.width(76.dp))
                            Text(v, fontSize = 11.sp, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                p.raw?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        fontSize = 10.sp,
                        color = InkSoft,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 13.sp,
                    )
                }
            } else if (p.fields.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("点开看 ${p.fields.size} 项明细", fontSize = 10.sp, color = Cinnabar)
            }
        }
    }
}

@Composable
private fun Badge(s: Status) {
    val (text, color) = when (s) {
        Status.OK -> "可以" to Jade
        Status.EMPTY -> "空" to Amber
        Status.FAIL -> "失败" to Cinnabar
        Status.NOAUTH -> "无凭证" to Ash
        Status.RUNNING -> "…" to Amber
        Status.IDLE -> "待测" to Ash
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = .12f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun RunBar(running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(color = Paper, tonalElevation = 0.dp) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Button(
            onClick = onClick,
            enabled = enabled && !running,
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp)
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Ink,
                contentColor = Paper,
                disabledContainerColor = Ink.copy(alpha = .25f),
                disabledContentColor = Paper,
            ),
        ) {
            Text(
                when {
                    running -> "体检中…"
                    !enabled -> "先粘贴凭证"
                    else -> "开始体检"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }
        }
    }
}

@Composable
private fun FootNote() {
    Text(
        "零第三方依赖 · 数据只在本机 · 不存储密码\n凭证 4 小时后过期，过期需重新取",
        fontSize = 10.sp,
        color = Ash,
        lineHeight = 15.sp,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}
