package com.kang.ubcm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 底部四个 Tab（docs/19 §2、docs/18 §8.2）。
 *
 * 图标用 Material Icons（Google 官方 `material-icons-extended`，已在依赖里）——
 * **没有引入新的第三方运行时依赖**。
 */
enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Filled.Home),
    STUDY("学习", Icons.Filled.DateRange),
    CAMPUS("校园", Icons.Filled.Place),
    ME("我的", Icons.Filled.Person),
}

/**
 * 主壳：四个 Tab + Tab 内子页 + 底部导航 + 缺凭证警示栏。
 *
 * 为什么四个 Tab 的页面**同时留在 composition 里**（不可见的用 alpha 0 盖住）：
 * 验收要求「切 Tab 各自保留滚动位置」。现有页面内部的 LazyColumn 用的是 `remember`
 * 状态而非 `rememberSaveable`，一旦离开 composition 就会丢；所以要保状态就不能卸载。
 * 为了让"看不见的页面"不误接触摸，给它们挂了一个消费 Initial 事件的 pointerInput。
 */
@Composable
fun MainShell(
    audit: CredentialGate.Audit,
    showWarn: Boolean,
    onDismissWarn: () -> Unit,
    onOpenLogin: () -> Unit,
    onLogout: () -> Unit,
    onRecheck: () -> Unit,
    onSaveManual: (jwt: String, jw: String, castgc: String, seat: String, ecard: String, openid: String) -> Unit,
    onWebLoginSeat: () -> Unit,
) {
    val ctx = LocalContext.current
    val homeVm: HomeViewModel = viewModel()
    val probeVm: ProbeViewModel = viewModel()
    val libVm: LibraryViewModel = viewModel()
    val loginVm: LoginViewModel = viewModel()
    val loginState by loginVm.state.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(MainTab.HOME) }
    // 每个 Tab 自己的子页栈（栈里存的是子页 key，目前只有 "auth" / "probe"）
    var stacks by remember { mutableStateOf<Map<MainTab, List<String>>>(emptyMap()) }
    // 懒挂载：进过的 Tab 才渲染，之后常驻（保状态）
    var visited by remember { mutableStateOf(setOf(MainTab.HOME)) }

    fun open(target: MainTab, sub: String? = null) {
        if (target !in visited) visited = visited + target
        if (sub != null) stacks = stacks + (target to (stacks[target].orEmpty() + sub))
        tab = target
    }

    fun pop() {
        val cur = stacks[tab].orEmpty()
        if (cur.isNotEmpty()) stacks = stacks + (tab to cur.dropLast(1))
    }

    Column(Modifier.fillMaxSize().background(UbcmColors.Paper)) {
        Box(Modifier.weight(1f)) {
            // 先画不可见的，再画当前的：当前层在上面，点击优先命中它
            MainTab.entries.filter { it in visited && it != tab }.forEach { t ->
                key(t) {
                    Box(Modifier.fillMaxSize().alpha(0f).blockInputs()) {
                        TabRoot(
                            tab = t,
                            stack = stacks[t].orEmpty(),
                            homeVm = homeVm,
                            libVm = libVm,
                            probeVm = probeVm,
                            audit = audit,
                            loginState = loginState,
                            onLoadLoginPage = { loginVm.loadLoginPage() },
                            onLogin = { u, p, c -> loginVm.login(u, p, c) },
                            onRefreshCaptcha = { loginVm.refreshCaptcha() },
                            onRecheck = onRecheck,
                            onSaveManual = onSaveManual,
                            onWebLoginSeat = onWebLoginSeat,
                            onOpenGate = onOpenLogin,
                            onLogout = onLogout,
                            onNav = ::open,
                            onPop = ::pop,
                        )
                    }
                }
            }
            key(tab) {
                TabRoot(
                    tab = tab,
                    stack = stacks[tab].orEmpty(),
                    homeVm = homeVm,
                    libVm = libVm,
                    probeVm = probeVm,
                    audit = audit,
                    loginState = loginState,
                    onLoadLoginPage = { loginVm.loadLoginPage() },
                    onLogin = { u, p, c -> loginVm.login(u, p, c) },
                    onRefreshCaptcha = { loginVm.refreshCaptcha() },
                    onRecheck = onRecheck,
                    onSaveManual = onSaveManual,
                    onWebLoginSeat = onWebLoginSeat,
                    onOpenGate = onOpenLogin,
                    onLogout = onLogout,
                    onNav = ::open,
                    onPop = ::pop,
                )
            }
        }

        if (showWarn) {
            WarnBar(onLogin = onOpenLogin, onClose = onDismissWarn)
        }

        TabBar(current = tab, onSelect = { t -> open(t) })
    }
}

/** 一个 Tab 当前该显示什么。子页 key：`"auth"` = 凭证与登录，`"probe"` = 数据体检。 */
@Composable
private fun TabRoot(
    tab: MainTab,
    stack: List<String>,
    homeVm: HomeViewModel,
    libVm: LibraryViewModel,
    probeVm: ProbeViewModel,
    audit: CredentialGate.Audit,
    loginState: LoginViewModel.UiState,
    onLoadLoginPage: () -> Unit,
    onLogin: (String, String, String?) -> Unit,
    onRefreshCaptcha: () -> Unit,
    onRecheck: () -> Unit,
    onSaveManual: (String, String, String, String, String, String) -> Unit,
    onWebLoginSeat: () -> Unit,
    onOpenGate: () -> Unit,
    onLogout: () -> Unit,
    onNav: (MainTab, String?) -> Unit,
    onPop: () -> Unit,
) {
    val ctx = LocalContext.current
    val sub = stack.lastOrNull()

    when (tab) {
        MainTab.HOME -> when (sub) {
            "empty_room" -> PlaceholderPage(
                title = "空教室",
                note = "查询教学楼的空闲教室 · 非实时 · P4 接入",
                onBack = onPop,
            )

            "todo" -> PlaceholderPage(
                title = "待办",
                note = "完整待办页（新增 / 编辑 / 已完成）· P7 接入",
                onBack = onPop,
            )

            else -> HomeScreen(
                vm = homeVm,
                actions = HomeActions(
                    openCampus = { onNav(MainTab.CAMPUS, null) },
                    openWeek = { onNav(MainTab.STUDY, null) },
                    openLibrary = { onNav(MainTab.CAMPUS, null) },
                    openEmptyRoom = { onNav(MainTab.HOME, "empty_room") },
                    // 通知列表页未做，先落到校园 Tab（lead 允许的临时落点）
                    openNotice = { onNav(MainTab.CAMPUS, null) },
                    openTodoAll = { onNav(MainTab.HOME, "todo") },
                    openLogin = onOpenGate,
                    openProbe = { onNav(MainTab.ME, "probe") },
                    openLogistics = {
                        SchoolWebActivity.start(
                            ctx,
                            "https://zhhq.bucm.edu.cn/wx/#/basicHome",
                            "智慧后勤",
                        )
                    },
                    openJw = {
                        SchoolWebActivity.start(
                            ctx,
                            Net.JW_BASE + "/jsxsd/framework/xsMain_bzy.html",
                            "智慧教务",
                        )
                    },
                    openEcrad = { onNav(MainTab.CAMPUS, null) },
                    logout = onLogout,
                ),
            )
        }

        MainTab.STUDY -> WeekScreen(
            vm = homeVm,
            onBack = { onNav(MainTab.HOME, null) },
            onOpenProbe = { onNav(MainTab.ME, "probe") },
        )

        MainTab.CAMPUS -> LibraryScreen(
            vm = libVm,
            onBack = { onNav(MainTab.HOME, null) },
            onOpenLogin = { onNav(MainTab.ME, "auth") },
            onOpenSchoolPage = {
                SchoolWebActivity.start(
                    ctx,
                    "https://kjyy.bucm.edu.cn/mobile.html",
                    "图书馆座位预约",
                    hashAfterLoad = "#/seatReserve",
                )
            },
            onOpenRoom = { roomId, roomName ->
                SchoolWebActivity.start(
                    ctx,
                    "https://kjyy.bucm.edu.cn/mobile.html",
                    roomName,
                    hashAfterLoad = "#/seatReserve?roomId=$roomId",
                )
            },
        )

        MainTab.ME -> when (sub) {
            "auth" -> LoginGateScreen(
                audit = audit,
                login = loginState,
                onLoadLoginPage = onLoadLoginPage,
                onLogin = onLogin,
                onRefreshCaptcha = onRefreshCaptcha,
                onRecheck = onRecheck,
                onSaveManual = onSaveManual,
                onWebLoginSeat = onWebLoginSeat,
                onBack = onPop,
            )

            "probe" -> ProbeScreen(
                vm = probeVm,
                onLogout = onLogout,
                onBack = onPop,
                backLabel = "‹  我的",
            )

            else -> MeHome(
                audit = audit,
                onOpenAuth = { onNav(MainTab.ME, "auth") },
                onOpenProbe = { onNav(MainTab.ME, "probe") },
                onLogout = onLogout,
            )
        }
    }
}

/** 未实现页面的统一占位（标题 + 一句「谁的活」+ 返回）。 */
@Composable
private fun PlaceholderPage(title: String, note: String, onBack: () -> Unit) {
    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.fillMaxSize().widthIn(max = 640.dp).padding(20.dp)) {
                Text(
                    "‹  返回",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = UbcmColors.InkSoft,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onBack)
                        .padding(vertical = 5.dp, horizontal = 3.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink)
                Spacer(Modifier.height(8.dp))
                Text("建设中", fontSize = 13.sp, color = UbcmColors.Ash)
                Spacer(Modifier.height(4.dp))
                Text(note, fontSize = 12.sp, color = UbcmColors.InkSoft)
            }
        }
    }
}

// ---------------- 底部导航 ----------------

@Composable
private fun TabBar(current: MainTab, onSelect: (MainTab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(UbcmColors.CardWhite)) {
        // 1dp 顶边线，无阴影（docs/18 §8.2）
        Box(Modifier.fillMaxWidth().height(1.dp).background(UbcmColors.Line))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp)) {
            MainTab.entries.forEach { t ->
                val selected = t == current
                val tone = if (selected) UbcmColors.Cinnabar else UbcmColors.Ash
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = t.icon,
                        contentDescription = t.label,
                        tint = tone,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t.label,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = tone,
                    )
                }
            }
        }
    }
}

// ---------------- 缺凭证警示栏 ----------------

/**
 * 缺凭证警示栏（docs/28 §3）：底部 Tab 栏正上方、40dp、danger 10% 底 + 1dp danger 顶边。
 * 可关闭（本次会话不落盘），点「去登录」回登录页。
 */
@Composable
private fun WarnBar(onLogin: () -> Unit, onClose: () -> Unit) {
    val danger = UbcmColors.Danger
    Column(Modifier.fillMaxWidth().background(Color(0xFFFDECEC))) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(danger))
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⚠", fontSize = 12.sp, color = danger)
            Spacer(Modifier.width(6.dp))
            Text(
                "凭证不完整，部分数据不可用",
                fontSize = 13.sp,
                color = danger,
                modifier = Modifier.weight(1f),
            )
            Text(
                "去登录",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = danger,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onLogin)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "×",
                fontSize = 18.sp,
                color = danger,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

// ---------------- 我的（P1 占位） ----------------

/**
 * 「我的」根页：P1 只放占位 + 两个真实入口（凭证与登录、数据体检）。
 * 完整版（头像/设置三分组/DDL 滚轮）在 P6。
 */
@Composable
private fun MeHome(
    audit: CredentialGate.Audit,
    onOpenAuth: () -> Unit,
    onOpenProbe: () -> Unit,
    onLogout: () -> Unit,
) {
    val ready = audit.requiredCount - audit.missingRequired.size
    var confirmingLogout by remember { mutableStateOf(false) }

    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                Modifier.fillMaxHeight().widthIn(max = 640.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("我的", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink)
                    Text("凭证与设置", fontSize = 12.sp, color = UbcmColors.InkSoft)
                }

                item {
                    MeRow(
                        title = "凭证与登录",
                        sub = if (audit.allOk) "5 条凭证已就绪" else "$ready/${audit.requiredCount} 条可用，点这里补齐",
                        tone = if (audit.allOk) UbcmColors.Jade else UbcmColors.Danger,
                        onClick = onOpenAuth,
                    )
                }

                item {
                    MeRow(
                        title = "数据体检",
                        sub = "18 个探针，查接口通不通",
                        tone = UbcmColors.InkSoft,
                        onClick = onOpenProbe,
                    )
                }

                item {
                    MeRow(
                        title = "设置",
                        sub = "周次锚点、座位 cookie、版本信息 · 建设中",
                        tone = UbcmColors.Ash,
                        onClick = {},
                    )
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    if (!confirmingLogout) {
                        Text(
                            "退出登录",
                            fontSize = 14.sp,
                            color = UbcmColors.Danger,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { confirmingLogout = true }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("清空所有凭证？", fontSize = 13.sp, color = UbcmColors.InkSoft)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "确认退出",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(UbcmColors.Danger)
                                    .clickable { onLogout() }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "取消",
                                fontSize = 13.sp,
                                color = UbcmColors.Ash,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { confirmingLogout = false }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeRow(title: String, sub: String, tone: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = UbcmColors.Ink)
            Spacer(Modifier.height(2.dp))
            Text(sub, fontSize = 13.sp, color = tone)
        }
        Text("›", fontSize = 18.sp, color = UbcmColors.Ash)
    }
}

/**
 * 吃掉所有触摸。给「不可见但仍留在 composition 里」的 Tab 用，
 * 防止用户在别的 Tab 上滑动时误滚到下面那层看不见的列表。
 */
private fun Modifier.blockInputs(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        }
    }
}
