package com.kang.ubcm

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 学习页的跳转动作。 */
data class StudyActions(
    /** 座位预约：P4 之前先落到现有图书馆页 */
    val openSeatReserve: () -> Unit = {},
    /** 空间预约：同上 */
    val openSpaceReserve: () -> Unit = {},
)

/** 签到宽限：学校 `earlyInTime` = 10 分钟（docs/13）。 */
private const val CHECK_IN_GRACE_MIN = 10L

/** 网格行高与节间缝（沿用周视图的绝对排版口径）。 */
private const val ROW_DP = 46f
private const val GAP_DP = 4f
private const val LANE_GAP_DP = 1.5f
private val PERIOD_AXIS_W = 40.dp

private val HM = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 学习页（docs/24）：顶部图书馆卡 + 课表（日/周视图）。
 *
 * 课表算法一行没动：[WeekGrid] 负责周次过滤与冲突分栏、[WeekGeometry] 负责跨节绝对高度，
 * 这里只负责画。日视图是新增的，走时间轴列表。
 */
@Composable
fun StudyScreen(
    homeVm: HomeViewModel,
    libVm: LibraryViewModel,
    actions: StudyActions,
) {
    val st by homeVm.state.collectAsStateWithLifecycle()
    val lib by libVm.state.collectAsStateWithLifecycle()

    // 全页共用的秒级时钟（图书馆倒计时 + 课表时间线）
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val now = remember(nowMillis / 1000) { Agenda.dateTimeOf(nowMillis) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            nowMillis = System.currentTimeMillis()
        }
    }

    // 进页面刷一次图书馆（空座 + 我的预约）
    LaunchedEffect(Unit) { libVm.refresh() }

    var mode by remember { mutableStateOf(TimetableMode.WEEK) }
    // 日视图看哪天：null = 今天
    var dayOffset by remember { mutableStateOf(0) }
    var picked by remember { mutableStateOf<WeekGrid.Slot?>(null) }

    // 课程详情 Sheet 打开时，返回键先关它
    BackHandler(enabled = picked != null) { picked = null }

    val grid = remember(st.sessions, st.shownWeek) { WeekGrid.build(st.sessions, st.shownWeek) }

    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.fillMaxSize().widthIn(max = 900.dp)) {
                    LazyColumn(
                        Modifier.fillMaxHeight().weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            LibraryCard(
                                st = lib,
                                now = now,
                                onSeatReserve = actions.openSeatReserve,
                                onSpaceReserve = actions.openSpaceReserve,
                            )
                        }

                        item {
                            ViewSwitchRow(
                                mode = mode,
                                onMode = { mode = it },
                                weekLabel = weekLabel(st),
                                dayLabel = dayLabel(now, dayOffset),
                                onPrev = {
                                    if (mode == TimetableMode.WEEK) homeVm.shiftWeek(-1)
                                    else dayOffset -= 1
                                },
                                onNext = {
                                    if (mode == TimetableMode.WEEK) homeVm.shiftWeek(1)
                                    else dayOffset += 1
                                },
                                onCenter = {
                                    if (mode == TimetableMode.WEEK) homeVm.selectWeek(null)
                                    else dayOffset = 0
                                },
                                centerIsCurrent = if (mode == TimetableMode.WEEK) st.onCurrentWeek else dayOffset == 0,
                            )
                        }

                        if (st.sessions.isEmpty()) {
                            item { EmptyTimetable() }
                        } else if (mode == TimetableMode.WEEK) {
                            item {
                                WeekPager(
                                    week = st.shownWeek,
                                    sessions = st.sessions,
                                    onShift = { dir -> homeVm.shiftWeek(dir) },
                                ) { weekGrid ->
                                    WeekView(
                                        grid = weekGrid,
                                        st = st,
                                        now = now,
                                        nowMillis = nowMillis,
                                        pickedName = picked?.let { slotKey(it) },
                                        onPick = { s ->
                                            picked = if (picked != null && slotKey(picked!!) == slotKey(s)) null else s
                                        },
                                    )
                                }
                            }
                            item {
                                AnchorBar(firstMonday = st.firstWeekMonday, onSave = { homeVm.setFirstWeekMonday(it) })
                            }
                        } else {
                            item {
                                DayView(
                                    sessions = st.sessions,
                                    slots = st.slots,
                                    firstWeekMonday = st.firstWeekMonday,
                                    date = now.toLocalDate().plusDays(dayOffset.toLong()),
                                    onPick = { picked = it },
                                )
                            }
                        }
                    }
                }
            }

            // 课程详情：自绘底部浮层（不引 ModalBottomSheet，保持零实验 API）
            picked?.let { slot ->
                CourseSheet(slot = slot, st = st, onClose = { picked = null })
            }
        }
    }
}

private enum class TimetableMode { WEEK, DAY }

private fun weekLabel(st: HomeState): String =
    if (st.shownWeek >= 1) "第 ${st.shownWeek} 周" else "未开学"

private val DAY_CN = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private fun dayLabel(now: LocalDateTime, offset: Int): String {
    val d = now.toLocalDate().plusDays(offset.toLong())
    val cn = DAY_CN[d.dayOfWeek.value - 1]
    val prefix = when (offset) {
        0 -> "今天"
        1 -> "明天"
        -1 -> "昨天"
        else -> "${d.monthValue}月${d.dayOfMonth}日"
    }
    return if (offset in -1..1) "$prefix · $cn" else "$prefix · $cn"
}

// ---------------- 图书馆卡 ----------------

@Composable
private fun LibraryCard(
    st: LibraryState,
    now: LocalDateTime,
    onSeatReserve: () -> Unit,
    onSpaceReserve: () -> Unit,
) {
    // 口径（用户已定）：分母 = 所有支持预约的座位 = seatMenu 各楼层 totalCount 之和（实测 2622），
    // 分子 = 各层 remainCount 之和。两者同口径，不会出现"空座 > 总座"。
    // seatMenu 失败（floors 空）时退回 resvStatus 的"全馆口径"，并在下面标出来。
    val menuFree = st.floors.sumOf { it.remain }
    val menuTotal = st.floors.sumOf { it.total }
    val useMenu = menuTotal > 0
    val free = if (useMenu) menuFree else st.available
    val total = if (useMenu) menuTotal else {
        val a = st.available
        val u = st.used
        if (a != null && u != null) a + u else st.totalSeats.takeIf { it > 0 }
    }

    // 当前生效的预约：优先"使用中"，其次"待签到/已预约"
    // 仅 debug：`adb shell am start ... --es demo_resv using|pending` 注入一条假预约，
    // 用来在真机上截「使用中 / 已预约」两态。正常启动为 null，不影响任何真实数据。
    val injected = if (DebugInjection.enabled) DebugInjection.demoReservation else null
    val active = injected ?: st.upcoming
        .filter { it.isActive(now) }
        .minByOrNull { it.beginAt ?: LocalDateTime.MAX }
    val using = active != null && active.statusText(now) == "使用中"

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("图书馆", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = UbcmColors.Ink)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        free?.toString() ?: "—",
                        fontSize = 31.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = UbcmColors.Ink,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    )
                    Text(
                        "/${total?.toString() ?: "—"}",
                        fontSize = 16.sp,
                        color = UbcmColors.Ash,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text(
                    if (useMenu) "当前空座 / 总座（可预约）" else "当前空座 / 总座（全馆口径）",
                    fontSize = 11.sp,
                    color = UbcmColors.Ash,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallPrimaryButton("座位预约", onSeatReserve)
                SmallSecondaryButton("空间预约", onSpaceReserve)
            }
        }

        if (active != null) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(UbcmColors.Line))
            Spacer(Modifier.height(12.dp))
            ReservationStatus(active = active, using = using, now = now)
        }
    }
}

@Composable
private fun SmallPrimaryButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(UbcmColors.Cinnabar)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

@Composable
private fun SmallSecondaryButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmTheme.Brand300, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = UbcmTheme.Brand700)
    }
}

/**
 * 预约状态栏（docs/24 §2.2）：
 *  - 待签到 → 黄点 + 位置 + 时段 + 距签到截止（danger）
 *  - 使用中 → 绿点 + 位置 + 时段 + 进度条 + 百分比
 */
@Composable
private fun ReservationStatus(active: SeatApi.Resv, using: Boolean, now: LocalDateTime) {
    val dot = if (using) UbcmTheme.Success else UbcmTheme.Warning
    val label = if (using) "使用中" else "已预约"
    val place = listOf(active.roomName, active.seatName)
        .filter { it.isNotBlank() && it != "null" }
        .joinToString(" ")
        .ifBlank { active.labName }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = UbcmColors.Ink)
            Spacer(Modifier.weight(1f))
            Text(place, fontSize = 14.sp, color = UbcmColors.Ink)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(active.timeRange(), fontSize = 12.sp, color = UbcmColors.InkSoft)
            Spacer(Modifier.weight(1f))
            if (using) {
                val pct = progressPercent(active, now)
                Text("$pct%", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = UbcmTheme.Success)
            } else {
                val deadline = active.beginAt?.plusMinutes(CHECK_IN_GRACE_MIN)
                if (deadline != null) {
                    Text(
                        "距签到截止 ${Agenda.countdown(Agenda.millisOf(deadline), Agenda.millisOf(now))}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = UbcmColors.Danger,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    )
                }
            }
        }
        if (using) {
            Spacer(Modifier.height(6.dp))
            val pct = progressPercent(active, now)
            Box(
                Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(UbcmColors.Line)
            ) {
                if (pct > 0) {
                    Box(
                        Modifier.fillMaxWidth(pct / 100f).fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp)).background(UbcmTheme.Success)
                    )
                }
            }
        }
    }
}

private fun progressPercent(resv: SeatApi.Resv, now: LocalDateTime): Int {
    val b = resv.beginAt ?: return 0
    val e = resv.endAt ?: return 0
    val total = java.time.Duration.between(b, e).toMinutes()
    if (total <= 0) return 0
    val done = java.time.Duration.between(b, now).toMinutes().coerceIn(0, total)
    return ((done * 100) / total).toInt().coerceIn(0, 100)
}

// ---------------- 第一行：视图切换 + 周次/日期导航 ----------------

@Composable
private fun ViewSwitchRow(
    mode: TimetableMode,
    onMode: (TimetableMode) -> Unit,
    weekLabel: String,
    dayLabel: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCenter: () -> Unit,
    centerIsCurrent: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 分段胶囊
        Row(
            Modifier.clip(RoundedCornerShape(999.dp)).background(UbcmColors.CardWhite).padding(3.dp),
        ) {
            SegItem("周视图", mode == TimetableMode.WEEK) { onMode(TimetableMode.WEEK) }
            SegItem("日视图", mode == TimetableMode.DAY) { onMode(TimetableMode.DAY) }
        }
        Spacer(Modifier.weight(1f))
        // 非本周时给一个显式的"回本周"（点中间文字也能回，但不够显眼）
        if (!centerIsCurrent && mode == TimetableMode.WEEK) {
            Text(
                "回本周",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = UbcmTheme.Brand500,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(UbcmTheme.Brand50)
                    .clickable(onClick = onCenter)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        // 周次 / 日期导航
        Text(
            "‹",
            fontSize = 16.sp,
            color = UbcmTheme.Brand700,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onPrev)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Text(
            if (mode == TimetableMode.WEEK) weekLabel else dayLabel,
            fontSize = 13.sp,
            fontWeight = if (centerIsCurrent) FontWeight.SemiBold else FontWeight.Normal,
            color = if (centerIsCurrent) UbcmColors.Ink else UbcmColors.InkSoft,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onCenter)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
        Text(
            "›",
            fontSize = 16.sp,
            color = UbcmTheme.Brand700,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onNext)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SegItem(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) UbcmTheme.Brand100 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) UbcmTheme.Brand700 else UbcmColors.InkSoft,
        )
    }
}

@Composable
private fun EmptyTimetable() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("还没有课表", fontSize = 15.sp, color = UbcmColors.Ink)
        Spacer(Modifier.height(6.dp))
        Text("登录后到「我的 → 凭证与登录」补齐教务会话", fontSize = 12.sp, color = UbcmColors.Ash)
    }
}

// ---------------- 周视图 ----------------

private fun slotKey(s: WeekGrid.Slot): String =
    "${s.session.name}|${s.dayOfWeek}|${s.startPeriod}-${s.endPeriod}|${s.session.location}"

@Composable
private fun WeekView(
    grid: WeekGrid.Grid,
    st: HomeState,
    now: LocalDateTime,
    nowMillis: Long,
    pickedName: String?,
    onPick: (WeekGrid.Slot) -> Unit,
) {
    val today = now.toLocalDate()
    val firstMonday = Timetable.mondayOf(grid.week, st.firstWeekMonday)
    val gridHeight = WeekGeometry.totalHeight(grid.periods, ROW_DP, GAP_DP).dp

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp))
            .padding(vertical = 10.dp)

    ) {
        // 两行表头：星期 + 日期（M/d）。
        // 固定渲染周一~周日 7 列 —— 不看 WeekGrid.days：那个字段带着旧测试台"周末没课就只画 5 天"
        // 的优化语义，而产品页要求固定 7 列（WeekGridTest 也钉了 5 天的断言，所以不动它）。
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(PERIOD_AXIS_W))
            (1..7).forEach { d ->
                val date = firstMonday.plusDays((d - 1).toLong())
                val isToday = date == today
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        DAY_CN[d - 1],
                        fontSize = 11.sp,
                        fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isToday) UbcmTheme.Brand700 else UbcmColors.InkSoft,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${date.monthValue}/${date.dayOfMonth}",
                        fontSize = 10.sp,
                        fontWeight = if (isToday) FontWeight.Medium else FontWeight.Normal,
                        color = if (isToday) UbcmTheme.Brand700 else UbcmColors.Ash,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // 网格
        Row(Modifier.fillMaxWidth()) {
            // 节次轴
            Box(Modifier.width(PERIOD_AXIS_W).height(gridHeight)) {
                for (p in 1..grid.periods) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .offset(y = WeekGeometry.topOf(p, ROW_DP, GAP_DP).dp)
                            .height(ROW_DP.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$p", fontSize = 11.sp, color = UbcmColors.Ash)
                    }
                }
            }

            (1..7).forEach { d ->
                BoxWithConstraints(
                    Modifier.weight(1f).height(gridHeight).padding(horizontal = 1.dp),
                ) {
                    val colW = maxWidth
                    val date = firstMonday.plusDays((d - 1).toLong())
                    val isToday = date == today

                    // 今天列：brand-50 极淡底
                    if (isToday) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(4.dp))
                                .background(UbcmTheme.Brand50)
                        )
                    }

                    // 横向网格线（每节顶部一条）
                    for (p in 1..grid.periods) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .offset(y = WeekGeometry.topOf(p, ROW_DP, GAP_DP).dp)
                                .height(1.dp)
                                .background(UbcmColors.Line.copy(alpha = 0.6f))
                        )
                    }

                    // 课程块
                    grid.slotsOn(d).forEach { s ->
                        CourseBlock(
                            slot = s,
                            colWidth = colW,
                            picked = pickedName == slotKey(s),
                            onPick = { onPick(s) },
                        )
                    }

                    // 当前时间线（只在今天列）
                    if (isToday) {
                        val y = yForTime(now, st.slots)
                        if (y != null) {
                            Row(
                                Modifier
                                    .offset(y = y.dp)
                                    .fillMaxWidth()
                                    .height(2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(5.dp).clip(CircleShape).background(UbcmTheme.Brand500))
                                Box(
                                    Modifier.weight(1f).height(1.5.dp)
                                        .background(UbcmTheme.Brand500)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 周切换 = **手指拖动实时跟随**（P9 修复"松手才整页切换"）。
 *
 * 做法：`Animatable<Float>` 记水平偏移，外层 `draggable` 直接 snap 到手指位移；
 * 拖动时同时画出相邻一周（offset ± 屏宽），松手按位移/速度决定「吸附到下一周」还是「回弹」。
 */
@Composable
private fun WeekPager(
    week: Int,
    sessions: List<CourseSession>,
    onShift: (Int) -> Unit,
    content: @Composable (WeekGrid.Grid) -> Unit,
) {
    val density = LocalDensity.current
    val offset = remember { Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
        val widthPx = with(density) { maxWidth.toPx() }
        val prevGrid = remember(sessions, week) { WeekGrid.build(sessions, week - 1) }
        val nextGrid = remember(sessions, week) { WeekGrid.build(sessions, week + 1) }

        Box(
            Modifier
                .fillMaxWidth()
                .draggable(
                    orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                    state = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
                        scope.launch {
                            // 边缘阻尼：越界只跟 35%，手感像"拉到头"
                            val v = offset.value + delta
                            val damped = when {
                                v < -widthPx * 0.35f -> -widthPx * 0.35f + (v + widthPx * 0.35f) * 0.3f
                                v > widthPx * 0.35f -> widthPx * 0.35f + (v - widthPx * 0.35f) * 0.3f
                                else -> v
                            }
                            offset.snapTo(damped)
                        }
                    },
                    onDragStopped = { velocity ->
                        val threshold = widthPx * 0.26f
                        val target = when {
                            offset.value < -threshold || velocity < -900f -> 1
                            offset.value > threshold || velocity > 900f -> -1
                            else -> 0
                        }
                        scope.launch {
                            if (target == 0) {
                                offset.animateTo(0f, tween(180))
                            } else {
                                val end = if (target > 0) -widthPx else widthPx
                                offset.animateTo(end, tween(190))
                                onShift(target)
                                offset.snapTo(0f)
                            }
                        }
                    },
                ),
        ) {
            // 相邻周（先画，当前周盖上面）
            if (offset.value < 0f) {
                Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((offset.value + widthPx).roundToInt(), 0) }) {
                    content(nextGrid)
                }
            } else if (offset.value > 0f) {
                Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((offset.value - widthPx).roundToInt(), 0) }) {
                    content(prevGrid)
                }
            }
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(offset.value.roundToInt(), 0) }) {
                content(WeekGrid.build(sessions, week))
            }
        }
    }
}

/** 当前时间映射到网格纵坐标（dp）；落在节间或作息之外返回 null。 */
private fun yForTime(now: LocalDateTime, slots: List<PeriodSlot>): Float? {
    val min = now.hour * 60 + now.minute
    val first = slots.minByOrNull { it.startMin } ?: return null
    val last = slots.maxByOrNull { it.endMin } ?: return null
    if (min < first.startMin || min > last.endMin) return null
    val slot = slots.firstOrNull { min in it.startMin until it.endMin } ?: return null
    val frac = (min - slot.startMin).toFloat() / (slot.endMin - slot.startMin).coerceAtLeast(1)
    return WeekGeometry.topOf(slot.period, ROW_DP, GAP_DP) + frac * ROW_DP
}

/**
 * 课程块：brand-100 底 + brand-700 字、8dp 圆角、6dp 内边距（docs/24 §3.2）。
 * 位置/高度全由节次算出来（[WeekGeometry]），冲突时按 lane/laneCount 并排。
 */
@Composable
private fun CourseBlock(
    slot: WeekGrid.Slot,
    colWidth: Dp,
    picked: Boolean,
    onPick: () -> Unit,
) {
    val top = WeekGeometry.topOf(slot.startPeriod, ROW_DP, GAP_DP).dp
    val h = WeekGeometry.heightOf(slot.startPeriod, slot.endPeriod, ROW_DP, GAP_DP).dp
    val laneW = colWidth / slot.laneCount.toFloat()
    val x = laneW * slot.lane.toFloat()

    Box(
        Modifier
            .offset(x = x, y = top)
            .width((laneW - LANE_GAP_DP.dp).coerceAtLeast(1.dp))
            .height(h)
            .clip(RoundedCornerShape(8.dp))
            .background(if (picked) UbcmTheme.Brand100 else UbcmTheme.Brand100.copy(alpha = 0.92f))
            .border(
                width = if (picked) 1.5.dp else 0.dp,
                color = if (picked) UbcmTheme.Brand700 else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onPick)
            .padding(6.dp),
    ) {
        Column {
            Text(
                slot.session.name,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = UbcmTheme.Brand700,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (slot.span >= 2 && slot.session.location.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    slot.session.location,
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    color = UbcmColors.InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------- 日视图 ----------------

@Composable
private fun DayView(
    sessions: List<CourseSession>,
    slots: List<PeriodSlot>,
    firstWeekMonday: LocalDate,
    date: LocalDate,
    onPick: (WeekGrid.Slot) -> Unit,
) {
    val week = Timetable.weekOf(date, firstWeekMonday)
    val lessons = if (week < 1) emptyList() else {
        Timetable.sessionsOn(sessions, date, week)
            .mapNotNull { s -> Timetable.place(s, date, slots)?.let { t -> s to t } }
            .sortedBy { it.second.start }
    }

    if (lessons.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(UbcmColors.CardWhite)
                .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("今天没有课", fontSize = 15.sp, color = UbcmColors.Ink)
            Spacer(Modifier.height(6.dp))
            Text("可以左右滑动切换日期", fontSize = 12.sp, color = UbcmColors.Ash)
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lessons.forEach { (session, time) ->
            Row {
                // 左侧时间刻度
                Column(Modifier.width(52.dp).padding(top = 14.dp)) {
                    Text(
                        time.start.format(HM),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = UbcmColors.InkSoft,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(UbcmTheme.Brand100)
                        .clickable {
                            onPick(
                                WeekGrid.Slot(
                                    session = session,
                                    dayOfWeek = session.dayOfWeek,
                                    startPeriod = session.startPeriod,
                                    endPeriod = session.endPeriod,
                                )
                            )
                        }
                        .padding(14.dp),
                ) {
                    Text(
                        session.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = UbcmTheme.Brand700,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOf(
                            session.location,
                            session.teacher,
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 12.sp,
                        color = UbcmColors.InkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${time.start.format(HM)}–${time.end.format(HM)} · ${periodLabel(session)}",
                        fontSize = 11.sp,
                        color = UbcmColors.Ash,
                    )
                }
            }
        }
    }
}

// ---------------- 课程详情（底部 Sheet） ----------------

@Composable
private fun CourseSheet(slot: WeekGrid.Slot, st: HomeState, onClose: () -> Unit) {
    val s = slot.session
    // 外层遮罩：点空白处关闭
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)).clickable(onClick = onClose),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(UbcmColors.CardWhite)
                // 吃掉点击，避免点内容也关闭
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .navigationBarsPadding(),
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(UbcmColors.Line)
            )
            Spacer(Modifier.height(16.dp))
            Text(s.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink)
            Spacer(Modifier.height(14.dp))

            if (s.teacher.isNotBlank()) SheetRow("教师", s.teacher)
            SheetRow("星期 / 节次", "${DAY_CN[s.dayOfWeek - 1]} · ${periodLabel(s)}")
            SheetRow("周次", compressWeeks(s.weeks))
            if (s.location.isNotBlank()) SheetRow("地点", s.location)
            if (s.className.isNotBlank()) SheetRow("班级", s.className)

            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(UbcmColors.Cinnabar)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text("关闭", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SheetRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, fontSize = 13.sp, color = UbcmColors.Ash, modifier = Modifier.width(88.dp))
        Text(value, fontSize = 14.sp, color = UbcmColors.Ink, modifier = Modifier.weight(1f))
    }
}

// ---------------- 周次锚点（换学期必改，最后一道保险） ----------------

@Composable
private fun AnchorBar(firstMonday: LocalDate, onSave: (String) -> String?) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(firstMonday) { mutableStateOf(firstMonday.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        if (!editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "周次锚点 ${firstMonday}",
                    fontSize = 10.sp,
                    color = UbcmColors.Ash,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "改",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = UbcmTheme.Brand500,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable {
                            text = firstMonday.toString()
                            error = null
                            editing = true
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        } else {
            Column {
                Text(
                    "本学期第 1 周的周一（换学期时必改，否则周次全错）",
                    fontSize = 10.sp,
                    color = UbcmColors.InkSoft,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; error = null },
                        singleLine = true,
                        isError = error != null,
                        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.width(150.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "保存",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(UbcmColors.Cinnabar)
                            .clickable {
                                val err = onSave(text)
                                if (err == null) editing = false else error = err
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "取消",
                        fontSize = 11.sp,
                        color = UbcmColors.Ash,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { editing = false }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 10.sp, color = UbcmColors.Danger, lineHeight = 14.sp)
                }
            }
        }
    }
}
