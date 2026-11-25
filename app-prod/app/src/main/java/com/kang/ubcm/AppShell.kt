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
/**
 * 本机调试注入（仅 `BuildConfig.DEBUG` 生效，正常启动恒为 null）。
 *
 * 用途：学习页的图书馆卡有「无预约 / 已预约 / 使用中」三态，其中后两态依赖真实座位预约，
 * 而预约是**写操作**（红线：App 不代用户预约），截图验证时用 adb 传一个假数据进去。
 * 它只影响 UI 渲染，不碰网络层、不写盘。
 */
object DebugInjection {
    /** 由 MainActivity 按 APK 的 debuggable 标志置位（AGP 8 默认不生成 BuildConfig） */
    var enabled: Boolean = false
    var demoReservation: SeatApi.Resv? = null

    // 以下只在 `--es demo_data 1` 时填充：座位 ic-cookie / 教务会话过期时，
    // 用它们把"楼层折叠 / 房间网格 / 教室网格"的结构画出来做视觉验收。
    // 页面里会同时显示"示例数据"字样，不冒充真实数据。
    var demoFloors: List<LibraryState.Floor> = emptyList()
    var demoKinds: List<SpaceApi.Kind> = emptyList()
    var demoRooms: List<SpaceApi.Room> = emptyList()
    var demoEmptyRooms: List<JwApi.EmptyRoom> = emptyList()
}

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
    val campusVm: CampusViewModel = viewModel()
    val loginState by loginVm.state.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(MainTab.HOME) }
    // 只保留「当前 + 上一个」Tab 的 composition（P9：四个常驻会让切 Tab 掉帧）
    var lastTab by remember { mutableStateOf(MainTab.HOME) }
    // 每个 Tab 自己的子页栈（栈里存的是子页 key，目前只有 "auth" / "probe"）
    var stacks by remember { mutableStateOf<Map<MainTab, List<String>>>(emptyMap()) }

    fun open(target: MainTab, sub: String? = null) {
        if (target != tab) lastTab = tab
        if (sub != null) stacks = stacks + (target to (stacks[target].orEmpty() + sub))
        tab = target
    }

    fun pop() {
        val cur = stacks[tab].orEmpty()
        if (cur.isNotEmpty()) stacks = stacks + (tab to cur.dropLast(1))
    }

    // 返回键：子页 → 上一页；Tab 根页 → 回首页 Tab；已在首页 → 退出 App（P9）
    androidx.activity.compose.BackHandler {
        when {
            stacks[tab].orEmpty().isNotEmpty() -> pop()
            tab != MainTab.HOME -> {
                lastTab = tab
                tab = MainTab.HOME
            }
            else -> (ctx as? android.app.Activity)?.finish()
        }
    }

    Column(Modifier.fillMaxSize().background(UbcmColors.Paper)) {
        Box(Modifier.weight(1f)) {
            // 先画不可见的（只保留上一个 Tab），再画当前的：当前层在上面，点击优先命中它
            listOf(lastTab).distinct().filter { it != tab }.forEach { t ->
                key(t) {
                    val invisibleAlpha by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = 0f,
                        animationSpec = androidx.compose.animation.core.tween(150),
                        label = "tab-out",
                    )
                    Box(Modifier.fillMaxSize().alpha(invisibleAlpha).blockInputs()) {
                        TabRoot(
                            tab = t,
                            stack = stacks[t].orEmpty(),
                            homeVm = homeVm,
                            libVm = libVm,
                            probeVm = probeVm,
                            campusVm = campusVm,
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
                    campusVm = campusVm,
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
    campusVm: CampusViewModel,
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
            "empty_room" -> EmptyRoomScreen(onBack = onPop, onOpenAuth = { onNav(MainTab.ME, "auth") })

            "todo" -> TodoListScreen(
                onBack = onPop,
                onChanged = { homeVm.reloadTodos() },
            )

            "notice" -> {
                val hs by homeVm.state.collectAsStateWithLifecycle()
                NoticeListScreen(
                    items = hs.noticeItems,
                    error = hs.noticeError,
                    loading = hs.refreshing,
                    onBack = onPop,
                    onRetry = { homeVm.reloadNotices() },
                )
            }

            else -> HomeScreen(
                vm = homeVm,
                actions = HomeActions(
                    openCampus = { onNav(MainTab.CAMPUS, null) },
                    openWeek = { onNav(MainTab.STUDY, null) },
                    openLibrary = { onNav(MainTab.CAMPUS, "library") },
                    openEmptyRoom = { onNav(MainTab.HOME, "empty_room") },
                    openNotice = { onNav(MainTab.HOME, "notice") },
                    openTodoAll = { onNav(MainTab.HOME, "todo") },
                    openLogin = onOpenGate,
                    openProbe = { onNav(MainTab.ME, "probe") },
                    openLogistics = { WeworkLauncher.open(ctx) },
                    openJw = {
                        SchoolWebActivity.start(
                            ctx,
                            // 直接开 xsMain_bzy.html 时，会话失效会显示一段裸 JSON；
                            // 从 /jsxsd/ 入口进会走站点自己的登录跳转
                            Net.JW_BASE + "/jsxsd/",
                            "智慧教务",
                        )
                    },
                    openEcrad = { onNav(MainTab.CAMPUS, null) },
                    logout = onLogout,
                ),
            )
        }

        MainTab.STUDY -> when (sub) {
            "seat" -> SeatReserveScreen(vm = libVm, onBack = onPop)

            "space" -> SpaceReserveScreen(onBack = onPop)

            else -> StudyScreen(
                homeVm = homeVm,
                libVm = libVm,
                actions = StudyActions(
                    openSeatReserve = { onNav(MainTab.STUDY, "seat") },
                    openSpaceReserve = { onNav(MainTab.STUDY, "space") },
                ),
            )
        }

        MainTab.CAMPUS -> when (sub) {
            "library" -> LibraryScreen(
                vm = libVm,
                onBack = onPop,
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

            "empty_room" -> EmptyRoomScreen(onBack = onPop, onOpenAuth = { onNav(MainTab.ME, "auth") })

            "more" -> PlaceholderPage(
                title = "更多服务",
                note = "服务清单以学校门户为准，V1 先开这两个入口",
                onBack = onPop,
            )

            else -> CampusScreen(
                vm = campusVm,
                actions = CampusActions(
                    openEcard = { EcardActivity.start(ctx) },
                    openLogistics = { WeworkLauncher.open(ctx) },
                    openJw = {
                        SchoolWebActivity.start(
                            ctx,
                            // 直接开 xsMain_bzy.html 时，会话失效会显示一段裸 JSON；
                            // 从 /jsxsd/ 入口进会走站点自己的登录跳转
                            Net.JW_BASE + "/jsxsd/",
                            "智慧教务",
                        )
                    },
                    openLibrary = { onNav(MainTab.CAMPUS, "library") },
                    openSpace = { onNav(MainTab.STUDY, "space") },
                    openPortal = {
                        SchoolWebActivity.start(ctx, "https://i.bucm.edu.cn", "数智北中医")
                    },
                    openEmptyRoom = { onNav(MainTab.CAMPUS, "empty_room") },
                    // "更多"V1 先弹一个说明（不引弹窗组件，这里用占位子页更统一）
                    openMore = { onNav(MainTab.CAMPUS, "more") },
                ),
            )
        }

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

            else -> MeScreen(
                homeVm = homeVm,
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
                val tone by androidx.compose.animation.animateColorAsState(
                    targetValue = if (selected) UbcmColors.Cinnabar else UbcmColors.Ash,
                    animationSpec = androidx.compose.animation.core.tween(150),
                    label = "tab-tone",
                )
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
