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

// ---------- 配色：蓝白金（统一取 UbcmColors，不再在本文件重复写色值） ----------
private val Ink = UbcmColors.Ink
private val InkSoft = UbcmColors.InkSoft
private val Paper = UbcmColors.Paper
private val CardWhite = UbcmColors.CardWhite
private val Line = UbcmColors.Line
private val Cinnabar = UbcmColors.Cinnabar
private val Jade = UbcmColors.Jade
private val Amber = UbcmColors.Amber
private val Ash = UbcmColors.Ash

/** 仅 debug：造一条内存里的假预约，给学习页三态截图用。 */
private fun demoReservation(using: Boolean): SeatApi.Resv {
    val now = java.time.LocalDateTime.now()
    return if (using) {
        SeatApi.Resv(
            resvId = 1,
            date = "",
            beginAt = now.minusMinutes(20),
            endAt = now.plusMinutes(40),
            checkAt = "auto",
            seatName = "A-12",
            roomName = "三层南区",
            labName = "",
            statusCode = 1217,
        )
    } else {
        SeatApi.Resv(
            resvId = 2,
            date = "",
            beginAt = now.plusMinutes(30),
            endAt = now.plusMinutes(150),
            checkAt = null,
            seatName = "B-07",
            roomName = "二层东区",
            labName = "",
            statusCode = 1217,
        )
    }
}

/** 仅 debug：楼层折叠页的示例数据（结构与 seatMenu 一致）。 */
private fun demoFloors(): List<LibraryState.Floor> {
    fun area(id: Int, name: String, total: Int, remain: Int) =
        LibraryState.Area(id, name, total, remain)
    return listOf(
        LibraryState.Floor(1, "图书馆二层", 282, 282, listOf(
            area(11, "二层南区", 120, 120), area(12, "二层北区", 162, 162),
        )),
        LibraryState.Floor(2, "图书馆三层", 778, 412, listOf(
            area(21, "三层南区", 145, 100), area(22, "三层西A区", 48, 30),
            area(23, "三层西B区", 304, 240), area(24, "三层西C区", 64, 50),
            area(25, "三层北区", 217, 92),
        )),
        LibraryState.Floor(3, "图书馆四层", 782, 98, listOf(
            area(31, "四层南区", 400, 60), area(32, "四层北区", 382, 38),
        )),
        LibraryState.Floor(4, "图书馆五层", 780, 0, listOf(
            area(41, "五层南区", 390, 0), area(42, "五层北区", 390, 0),
        )),
    )
}

/** 仅 debug：空间房间的示例数据（研讨间 24 / 静音仓 4 / 朗读亭 3）。 */
private fun demoRooms(): List<SpaceApi.Room> {
    val now = java.time.LocalDate.now()
    fun resv(fromH: Int, toH: Int) = SpaceApi.ResvInfo(
        begin = now.atTime(fromH, 30), end = now.atTime(toH, 30), status = 1093,
    )
    val out = mutableListOf<SpaceApi.Room>()
    var id = 1
    // 研讨间 301–310、401–414（部分占用）
    val seminar = listOf("301", "302", "303", "304", "305", "306", "307", "308",
        "309", "310", "401", "402", "403", "404", "405", "406",
        "407", "408", "409", "410", "411", "412", "413", "414")
    seminar.forEachIndexed { i, n ->
        val occupied = i % 3 == 2
        out += SpaceApi.Room(
            devId = id++, name = n, minResvTime = 60,
            openTimes = listOf(SpaceApi.OpenTime("08:30", "22:00")),
            resvInfos = if (occupied) listOf(resv(14, 16)) else emptyList(),
            kindId = 1,
        )
    }
    // 静音仓 01–04
    listOf("静音仓 01", "静音仓 02", "静音仓 03", "静音仓 04").forEachIndexed { i, n ->
        out += SpaceApi.Room(
            devId = id++, name = n, minResvTime = 60,
            openTimes = listOf(SpaceApi.OpenTime("08:30", "22:00")),
            resvInfos = if (i == 1) listOf(resv(14, 16)) else emptyList(),
            kindId = 2,
        )
    }
    // 朗读亭 01–03
    listOf("朗读亭 01", "朗读亭 02", "朗读亭 03").forEachIndexed { i, n ->
        out += SpaceApi.Room(
            devId = id++, name = n, minResvTime = 60,
            openTimes = listOf(SpaceApi.OpenTime("08:30", "22:00")),
            resvInfos = if (i == 2) listOf(resv(14, 16)) else emptyList(),
            kindId = 3,
        )
    }
    return out
}

/** 仅 debug：空教室的示例数据。 */
private fun demoEmptyRooms(): List<JwApi.EmptyRoom> {
    val out = mutableListOf<JwApi.EmptyRoom>()
    listOf("良东", "良西").forEach { b ->
        for (i in 1..24) {
            val free = i % 3 != 0
            out += JwApi.EmptyRoom(b, "$b${100 + i}", free, if (free) "" else "课")
        }
    }
    return out
}

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
        // 仅 debug：学习页图书馆卡的预约态截图注入（using / pending）
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            DebugInjection.enabled = true
            DebugInjection.demoReservation = when (intent?.getStringExtra("demo_resv")) {
                "using" -> demoReservation(using = true)
                "pending" -> demoReservation(using = false)
                else -> null
            }
            if (intent?.getStringExtra("demo_data") == "1") {
                DebugInjection.demoFloors = demoFloors()
                DebugInjection.demoKinds = listOf(
                    SpaceApi.Kind(1, "研讨间"), SpaceApi.Kind(2, "静音仓"), SpaceApi.Kind(3, "朗读亭"),
                )
                DebugInjection.demoRooms = demoRooms()
                DebugInjection.demoEmptyRooms = demoEmptyRooms()
            }
            // 仅 debug：本机截图时重新 SSO 一次座位 ic-cookie（后台线程，不阻塞首屏）
            if (intent?.getBooleanExtra("sso_seat", false) == true) {
                val castgc = Session.loadCastgc(this)
                if (!castgc.isNullOrBlank()) {
                    Thread {
                        runCatching {
                            val lr = SeatApi.login(castgc)
                            if (lr.ok && lr.cookie != null) {
                                Session.saveSeatCookie(this.applicationContext, lr.cookie!!)
                            }
                        }
                    }.start()
                }
            }
        }
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
 * 根组件 = 冷启动分流（登录页 / 主壳）。
 *
 * 定位（2026-11-24 起，docs/28）：**不再强制门禁**。
 *  - 凭证齐全 → 直接进主壳（[MainShell]），不出现登录页
 *  - 缺凭证   → 先给 [LoginScreen]，可「先跳过」进主壳
 *  - 跳过后主壳底部出现红色警示栏（[MainShell] 的 showWarn），可关闭、可「去登录」
 *
 * 强制门禁作废（docs/15 已标注变更），但**凭证补齐流程完整保留**：
 * 原生表单登录 + 凭证清单 + 手工粘贴 + 网页补座位，都在「我的 → 凭证与登录」。
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

    // 缺凭证 → 登录页（可「先跳过」）；凭证齐全 → 直接进主壳，不出现登录页。
    var skipped by remember { mutableStateOf(false) }
    var forcingLogin by remember { mutableStateOf(false) }
    var warnClosed by remember { mutableStateOf(false) }
    val needLogin = !audit.allOk && (!skipped || forcingLogin)

    if (needLogin) {
        LoginScreen(
            login = loginState,
            onLoadPage = { loginVm.loadLoginPage() },
            onLogin = { u, p, c -> loginVm.login(u, p, c) },
            onRefreshCaptcha = { loginVm.refreshCaptcha() },
            onSkip = {
                skipped = true
                forcingLogin = false
            },
        )
    } else {
        MainShell(
            audit = audit,
            showWarn = !audit.allOk && !warnClosed,
            onDismissWarn = { warnClosed = true },
            onOpenLogin = { forcingLogin = true },
            onLogout = {
                Session.clearCredentials(ctx)
                // WebView 的学校会话也要清，否则重新用「网页补座位」时会带着旧会话直接进去
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()
                loginVm.reset()
                skipped = false
                forcingLogin = false
                warnClosed = false
                gateKey++
            },
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
            // 座位这一条：**必须人工网页登录一次** —— 那个路由有滑块验证码，
            // 静默页过不去，而红线不允许绕验证码。登录后抓到的 cookie 会经 SeatApi.validCookie 校验。
            onWebLoginSeat = {
                seatLauncher.launch(
                    LoginWebViewActivity.newIntent(ctx, LoginWebViewActivity.Target.SEAT)
                )
            },
        )
    }
}

@Composable
internal fun ProbeScreen(
    vm: ProbeViewModel = viewModel(),
    initialShowAuth: Boolean = false,
    onLogout: () -> Unit = {},
    onBack: () -> Unit = {},
    backLabel: String = "‹  返回首页",
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
                item { BackRow(onBack, onLogout = onLogout, backLabel = backLabel) }
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
private fun BackRow(onBack: () -> Unit, onLogout: () -> Unit = {}, backLabel: String = "‹  返回首页") {
    var confirming by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            backLabel,
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
                color = UbcmColors.Danger,
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
                        .background(UbcmColors.Danger)
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
                else -> UbcmColors.Danger
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
                    color = if (left > 0) Jade else UbcmColors.Danger)
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
        Status.FAIL -> UbcmColors.Danger
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
        Status.FAIL -> "失败" to UbcmColors.Danger
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
