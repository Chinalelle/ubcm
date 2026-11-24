package com.kang.ubcm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 首页所有跳转动作。参数打包，避免 HomeScreen 签名长到看不清。 */
data class HomeActions(
    val openCampus: () -> Unit = {},
    val openWeek: () -> Unit = {},
    val openLibrary: () -> Unit = {},
    val openEmptyRoom: () -> Unit = {},
    val openNotice: () -> Unit = {},
    val openTodoAll: () -> Unit = {},
    val openLogin: () -> Unit = {},
    val openProbe: () -> Unit = {},
    val openLogistics: () -> Unit = {},
    val openJw: () -> Unit = {},
    val openEcrad: () -> Unit = {},
    val logout: () -> Unit = {},
)

/** 待办 "≤24h" 的橙系浅底（docs/21 §6.3：gold-100 加深一档）。 */
private val UrgentBg = Color(0xFFFBEDE1)
/** 待办 "3 天内" 的浅黄底。 */
private val NearBg = UbcmTheme.Gold100.copy(alpha = 0.5f)

private val TABULAR = TextStyle(fontFeatureSettings = "tnum")

/**
 * 首页（docs/21）：问候 → 通知条 → 常用工具与信息 → 日程 → 待办。
 *
 * 时钟：整页共用一个秒级 now，倒计时/进行中进度都从它算，避免各卡片各跑一个 ticker。
 * 数据：HomeViewModel（余额/通知/课表/待办）+ 三个本地 Store（小组件布局/自建日程/待办）。
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    actions: HomeActions,
) {
    val ctx = LocalContext.current
    val st by vm.state.collectAsStateWithLifecycle()

    // 全页共用的秒级时钟
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowMillis = System.currentTimeMillis()
        }
    }

    // 首次进入：有凭证就静默刷新；同时重读本地待办
    LaunchedEffect(Unit) {
        val s = vm.state.value
        if (s.hasJwt || s.hasCastgc) vm.refresh(false)
        vm.reloadTodos()
    }

    // 小组件布局（编辑后立即落盘）
    var widgets by remember { mutableStateOf(HomeWidgetStore.load(ctx)) }
    var editing by remember { mutableStateOf(false) }

    // 自建日程
    var userAgenda by remember { mutableStateOf(AgendaStore.load(ctx)) }
    val nowDateTime = remember(nowMillis / 1000) { Agenda.dateTimeOf(nowMillis) }
    val derivedAgenda = remember(st.sessions, st.slots, st.firstWeekMonday, nowDateTime.toLocalDate()) {
        Agenda.fromTimetable(st.sessions, st.slots, nowDateTime, st.firstWeekMonday)
    }
    val agendaCards = remember(derivedAgenda, userAgenda, nowMillis) {
        Agenda.nearest(Agenda.merge(derivedAgenda, userAgenda), nowMillis)
    }

    // 待办前三条（排序规则在 TodoRules，界面不许自己排）
    val topTodos = remember(st.todos, nowMillis) { TodoRules.topOpen(st.todos, nowMillis) }

    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                Modifier.fillMaxHeight().widthIn(max = 640.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { Greeting(name = st.userName ?: "同学", nowMillis = nowMillis) }

                // ② 通知条：无通知且无错误时整条隐藏
                if (!st.noticeTitles.isNullOrEmpty() || st.noticeError != null) {
                    item {
                        NoticeBar(
                            titles = st.noticeTitles ?: emptyList(),
                            unread = st.noticeUnread,
                            error = st.noticeError,
                            onClick = actions.openNotice,
                        )
                    }
                }

                // ③ 常用工具与信息
                item {
                    WidgetsCard(
                        widgets = widgets,
                        editing = editing,
                        st = st,
                        onEdit = { editing = true },
                        onDone = { editing = false },
                        onDelete = { id ->
                            widgets = WidgetLayout.remove(widgets, id).also { HomeWidgetStore.save(ctx, it) }
                        },
                        onMove = { id, col, row ->
                            widgets = WidgetLayout.moveOrSwap(widgets, id, col, row)
                                .also { HomeWidgetStore.save(ctx, it) }
                        },
                        onAdd = { type ->
                            WidgetLayout.add(widgets, type)?.let {
                                widgets = it
                                HomeWidgetStore.save(ctx, it)
                            }
                        },
                        onReset = {
                            widgets = HomeWidgetStore.reset(ctx)
                        },
                        onClick = { w ->
                            when (w.type) {
                                WidgetType.BALANCE, WidgetType.ECARD -> actions.openEcrad()
                                WidgetType.LIBRARY -> actions.openLibrary()
                                WidgetType.EMPTY_ROOM -> actions.openEmptyRoom()
                                WidgetType.LOGISTICS -> actions.openLogistics()
                                WidgetType.JW -> actions.openJw()
                                WidgetType.GRADES -> actions.openWeek()
                                WidgetType.EXAM -> actions.openWeek()
                                WidgetType.NOTICE -> actions.openNotice()
                                WidgetType.TODO -> actions.openTodoAll()
                            }
                        },
                    )
                }

                // ④ 日程
                item {
                    AgendaSection(
                        cards = agendaCards,
                        nowMillis = nowMillis,
                        onAdd = { /* P3 接表单 */ },
                        onClick = { card ->
                            if (card.item.source == AgendaSource.TIMETABLE) actions.openWeek() else actions.openWeek()
                        },
                    )
                }

                // ⑤ 待办
                item {
                    TodoSection(
                        todos = topTodos,
                        totalOpen = st.todos.count { !it.done },
                        nowMillis = nowMillis,
                        onSeeAll = actions.openTodoAll,
                    )
                }
            }
        }
    }
}

// ---------------- ① 问候 ----------------

@Composable
private fun Greeting(name: String, nowMillis: Long) {
    val date = remember(nowMillis / 3_600_000) {
        val d = Agenda.dateTimeOf(nowMillis).toLocalDate()
        val week = "星期" + "一二三四五六日"[d.dayOfWeek.value - 1]
        "${d.monthValue}月${d.dayOfMonth}日 $week"
    }
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Hi，", fontSize = 20.sp, color = UbcmColors.InkSoft)
            Text(
                name,
                fontSize = 25.sp,
                fontWeight = FontWeight.SemiBold,
                color = UbcmColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(date, fontSize = 13.sp, color = UbcmColors.Ash)
    }
}

// ---------------- ② 通知条 ----------------

@Composable
private fun NoticeBar(titles: List<String>, unread: Int?, error: String?, onClick: () -> Unit) {
    val hasError = titles.isEmpty() && error != null
    val list = if (hasError) listOf("通知获取失败 · 点这里重试") else titles
    if (list.isEmpty()) return

    var index by remember(list) { mutableStateOf(0) }
    val safeIndex = index.coerceIn(0, list.lastIndex)

    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(12.dp))
            .pointerInput(list) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        if (list.size > 1) {
                            if (dx < -60f) index = (safeIndex + 1) % list.size
                            else if (dx > 60f) index = (safeIndex - 1 + list.size) % list.size
                        }
                    },
                    onHorizontalDrag = { _, amount -> dx += amount },
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(
                Icons.Filled.Notifications,
                contentDescription = "通知",
                tint = UbcmColors.Cinnabar,
                modifier = Modifier.size(18.dp),
            )
            if ((unread ?: 0) > 0) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(UbcmColors.Danger)
                        .align(Alignment.TopEnd)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            list[safeIndex],
            fontSize = 15.sp,
            color = if (hasError) UbcmColors.Danger else UbcmColors.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!hasError && (unread ?: 0) > 0) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).background(UbcmTheme.Brand100)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("$unread", fontSize = 11.sp, color = UbcmColors.Cinnabar)
            }
        }
        Spacer(Modifier.width(6.dp))
        Text("›", fontSize = 18.sp, color = UbcmColors.Ash)
    }
}

// ---------------- ③ 常用工具与信息 ----------------

@Composable
private fun WidgetsCard(
    widgets: List<HomeWidget>,
    editing: Boolean,
    st: HomeState,
    onEdit: () -> Unit,
    onDone: () -> Unit,
    onDelete: (String) -> Unit,
    onMove: (String, Int, Int) -> Unit,
    onAdd: (WidgetType) -> Unit,
    onReset: () -> Unit,
    onClick: (HomeWidget) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "常用工具与信息",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = UbcmColors.Ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (editing) "完成" else "编辑",
                fontSize = 13.sp,
                color = UbcmTheme.Brand500,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { if (editing) onDone() else onEdit() }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(12.dp))

        if (widgets.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("还没有小组件", fontSize = 13.sp, color = UbcmColors.Ash)
                Spacer(Modifier.height(8.dp))
                Text(
                    "恢复默认组件",
                    fontSize = 13.sp,
                    color = UbcmTheme.Brand500,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onReset() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        } else {
            WidgetGrid(
                widgets = widgets,
                editing = editing,
                st = st,
                onMove = onMove,
                onDelete = onDelete,
                onClick = onClick,
            )
        }

        if (editing) {
            Spacer(Modifier.height(12.dp))
            AddWidgetPanel(widgets = widgets, onAdd = onAdd, onReset = onReset)
        }
    }
}

@Composable
private fun WidgetGrid(
    widgets: List<HomeWidget>,
    editing: Boolean,
    st: HomeState,
    onMove: (String, Int, Int) -> Unit,
    onDelete: (String) -> Unit,
    onClick: (HomeWidget) -> Unit,
) {
    val gap = 8.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cell: Dp = (maxWidth - gap * 3) / 4
        val gridHeight = cell * 2 + gap
        val density = LocalDensity.current

        var draggingId by remember { mutableStateOf<String?>(null) }
        var dragPx by remember { mutableStateOf(Offset.Zero) }

        Box(Modifier.fillMaxWidth().height(gridHeight)) {
            widgets.forEach { w ->
                val x = (cell + gap) * (w.col - 1)
                val y = (cell + gap) * (w.row - 1)
                val wd = cell * w.size.cols + gap * (w.size.cols - 1)
                val hd = cell * w.size.rows + gap * (w.size.rows - 1)
                val dragging = draggingId == w.id
                val dx = if (dragging) with(density) { dragPx.x.toDp() } else 0.dp
                val dy = if (dragging) with(density) { dragPx.y.toDp() } else 0.dp

                Box(
                    Modifier
                        .offset(x + dx, y + dy)
                        .size(wd, hd)
                        .zIndex(if (dragging) 2f else 0f)
                        .then(
                            if (editing) {
                                Modifier.pointerInput(w.id, cell, gap) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingId = w.id
                                            dragPx = Offset.Zero
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragPx += amount
                                        },
                                        onDragEnd = {
                                            val step = cell.toPx() + gap.toPx()
                                            val cx = x.toPx() + wd.toPx() / 2 + dragPx.x
                                            val cy = y.toPx() + hd.toPx() / 2 + dragPx.y
                                            val col = (cx / step).toInt() + 1
                                            val row = (cy / step).toInt() + 1
                                            onMove(w.id, col.coerceIn(1, WidgetLayout.COLS), row.coerceIn(1, WidgetLayout.ROWS))
                                            draggingId = null
                                            dragPx = Offset.Zero
                                        },
                                        onDragCancel = {
                                            draggingId = null
                                            dragPx = Offset.Zero
                                        },
                                    )
                                }
                            } else Modifier
                        ),
                ) {
                    WidgetContent(w, st, onClick)
                    if (editing) {
                        // 2dp brand-300 虚线描边（docs/21 §4.6）
                        Box(
                            Modifier.matchParentSize().drawDashedBorder(
                                color = UbcmTheme.Brand300,
                                radius = 12.dp,
                            )
                        )
                        DeleteBadge(
                            onClick = { onDelete(w.id) },
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetContent(w: HomeWidget, st: HomeState, onClick: (HomeWidget) -> Unit) {
    when (w.type) {
        WidgetType.BALANCE -> BalanceWidget(st = st, onClick = { onClick(w) })
        else -> EntranceWidget(type = w.type, onClick = { onClick(w) })
    }
}

@Composable
private fun BalanceWidget(st: HomeState, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(UbcmTheme.Brand50)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("校园卡余额", fontSize = 14.sp, color = UbcmColors.InkSoft)
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).background(UbcmTheme.Gold100)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text("一卡通", fontSize = 9.sp, color = UbcmTheme.Gold600)
            }
        }

        Column {
            if (st.balanceYuan == null) {
                Text("—", fontSize = 31.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ash)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("¥", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink)
                    Text(
                        st.balanceYuan,
                        fontSize = 31.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = UbcmColors.Ink,
                        style = TABULAR,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    st.cardError != null -> "点击重试"
                    st.weekSpendYuan != null -> "近 7 天消费 ¥${st.weekSpendYuan}"
                    else -> "近 7 天消费 —"
                },
                fontSize = 11.sp,
                color = if (st.cardError != null) UbcmColors.Danger else UbcmColors.Ash,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EntranceWidget(type: WidgetType, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(type.icon(), contentDescription = type.label, tint = UbcmColors.Cinnabar, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(4.dp))
        Text(type.shortLabel, fontSize = 11.sp, color = UbcmColors.InkSoft, maxLines = 1)
    }
}

private fun WidgetType.icon(): ImageVector = when (this) {
    WidgetType.BALANCE, WidgetType.ECARD -> Icons.Filled.CreditCard
    WidgetType.LIBRARY -> Icons.Filled.MenuBook
    WidgetType.EMPTY_ROOM -> Icons.Filled.MeetingRoom
    WidgetType.LOGISTICS -> Icons.Filled.Build
    WidgetType.JW -> Icons.Filled.School
    WidgetType.GRADES -> Icons.Filled.Assignment
    WidgetType.EXAM -> Icons.Filled.Event
    WidgetType.NOTICE -> Icons.Filled.Notifications
    WidgetType.TODO -> Icons.Filled.Checklist
}

@Composable
private fun DeleteBadge(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .offset(x = 4.dp, y = (-4).dp)
            .size(18.dp)
            .clip(CircleShape)
            .background(UbcmColors.Danger)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("×", fontSize = 12.sp, color = Color.White)
    }
}

@Composable
private fun AddWidgetPanel(
    widgets: List<HomeWidget>,
    onAdd: (WidgetType) -> Unit,
    onReset: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val available = WidgetType.entries.filter { t ->
        t != WidgetType.BALANCE && widgets.none { it.type == t }
    }

    Column {
        Text(
            "＋ 添加小组件",
            fontSize = 13.sp,
            color = UbcmTheme.Brand700,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 6.dp),
        )
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            // 默认布局正好占满 8 格：没空位时点了没反应会让人以为坏了，直接说清楚
            if (WidgetLayout.freeCells(widgets).isEmpty()) {
                Text(
                    "位置已满：先点组件右上角 × 删一个，再加新的",
                    fontSize = 11.sp,
                    color = UbcmColors.Danger,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                Spacer(Modifier.height(4.dp))
            }
            available.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { type ->
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(UbcmTheme.Brand50)
                                .clickable { onAdd(type) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(type.icon(), null, tint = UbcmTheme.Brand700, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(type.label, fontSize = 12.sp, color = UbcmColors.Ink, maxLines = 1)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
            }
            if (available.isEmpty()) {
                Text("能加的组件都加上了", fontSize = 11.sp, color = UbcmColors.Ash)
            }
            Text(
                "恢复默认",
                fontSize = 12.sp,
                color = UbcmTheme.Brand500,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        expanded = false
                        onReset()
                    }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

/** 虚线描边（编辑态）。用 drawBehind 而不是 border，因为 border 不支持虚线。 */
private fun Modifier.drawDashedBorder(color: Color, radius: Dp): Modifier = this.then(
    Modifier.drawBehind {
        drawRoundRect(
            color = color,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
            style = Stroke(
                width = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
            ),
        )
    }
)

// ---------------- ④ 日程 ----------------

@Composable
private fun AgendaSection(
    cards: List<AgendaCard>,
    nowMillis: Long,
    onAdd: () -> Unit,
    onClick: (AgendaCard) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "日程",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = UbcmColors.Ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                "+ 添加",
                fontSize = 13.sp,
                color = UbcmTheme.Brand500,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        if (cards.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(UbcmColors.CardWhite)
                    .border(1.dp, UbcmColors.Line, RoundedCornerShape(12.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("近三天没有安排", fontSize = 13.sp, color = UbcmColors.Ash)
            }
        } else {
            cards.forEachIndexed { i, card ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                AgendaCardView(card = card, nowMillis = nowMillis, onClick = { onClick(card) })
            }
        }
    }
}

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun AgendaCardView(card: AgendaCard, nowMillis: Long, onClick: () -> Unit) {
    val item = card.item
    val start = Agenda.dateTimeOf(item.startMillis)
    val end = item.endMillis?.let { Agenda.dateTimeOf(it) }

    val borderWidth: Dp
    val borderColor: Color
    val background: Color
    when (card.phase) {
        AgendaPhase.ONGOING -> {
            borderWidth = 1.5.dp
            borderColor = UbcmTheme.Gold500
            background = UbcmTheme.Gold100.copy(alpha = 0.4f)
        }
        AgendaPhase.UPCOMING -> {
            borderWidth = 1.5.dp
            borderColor = UbcmTheme.Brand500
            background = UbcmTheme.Brand100.copy(alpha = 0.4f)
        }
        AgendaPhase.NORMAL -> {
            borderWidth = 1.dp
            borderColor = UbcmColors.Line
            background = UbcmColors.CardWhite
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Row {
            // 左时间列
            Column(Modifier.width(46.dp)) {
                Text(
                    start.format(HM),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = UbcmColors.Ink,
                    style = TABULAR,
                )
                if (end != null) {
                    Text(end.format(HM), fontSize = 11.sp, color = UbcmColors.Ash, style = TABULAR)
                }
            }
            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        fontSize = 15.sp,
                        color = UbcmColors.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    SourceTag(item.source)
                    if (card.phase == AgendaPhase.ONGOING) {
                        Spacer(Modifier.width(8.dp))
                        Text("进行中", fontSize = 11.sp, color = UbcmTheme.Gold600)
                    }
                }
                item.location?.let { loc ->
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Place,
                            contentDescription = "地点",
                            tint = UbcmColors.Ash,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(loc, fontSize = 12.sp, color = UbcmColors.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // 即将到来：距开始（≤10 分钟变红）
            if (card.phase == AgendaPhase.UPCOMING) {
                val soon = item.startMillis - nowMillis <= Agenda.UPCOMING_SOON_MS
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("距开始", fontSize = 10.sp, color = UbcmColors.Ash)
                    Text(
                        Agenda.countdown(item.startMillis, nowMillis),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (soon) UbcmColors.Danger else UbcmTheme.Brand500,
                        style = TABULAR,
                    )
                }
            }
        }

        if (card.phase == AgendaPhase.ONGOING) {
            Spacer(Modifier.height(10.dp))
            val pct = Agenda.progressPercent(item, nowMillis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "距结束 ${Agenda.countdown(item.endMillis ?: item.startMillis, nowMillis)}",
                    fontSize = 11.sp,
                    color = UbcmColors.InkSoft,
                    style = TABULAR,
                )
                Spacer(Modifier.weight(1f))
                Text("$pct%", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = UbcmTheme.Gold600, style = TABULAR)
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(UbcmColors.Line)
            ) {
                if (pct > 0) {
                    Box(
                        Modifier.fillMaxWidth(pct / 100f).fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp)).background(UbcmTheme.Gold500)
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceTag(source: AgendaSource) {
    val bg = if (source == AgendaSource.TIMETABLE) UbcmTheme.Brand100 else UbcmTheme.Gold100
    val fg = if (source == AgendaSource.TIMETABLE) UbcmTheme.Brand700 else UbcmTheme.Gold600
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(if (source == AgendaSource.TIMETABLE) "课" else "自建", fontSize = 10.sp, color = fg)
    }
}

// ---------------- ⑤ 待办 ----------------

@Composable
private fun TodoSection(todos: List<TodoItem>, totalOpen: Int, nowMillis: Long, onSeeAll: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "待办",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = UbcmColors.Ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                "查看全部",
                fontSize = 13.sp,
                color = UbcmTheme.Brand500,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(10.dp))

        if (todos.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(UbcmColors.CardWhite)
                    .border(1.dp, UbcmColors.Line, RoundedCornerShape(12.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("暂无待办", fontSize = 13.sp, color = UbcmColors.Ash)
                Spacer(Modifier.weight(1f))
                if (totalOpen > 0) Text("共 $totalOpen 条", fontSize = 11.sp, color = UbcmColors.Ash)
            }
        } else {
            todos.forEachIndexed { i, todo ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                TodoCard(todo = todo, nowMillis = nowMillis)
            }
        }
    }
}

@Composable
private fun TodoCard(todo: TodoItem, nowMillis: Long) {
    val level = TodoRules.levelOf(todo.dueAt, nowMillis)
    val cardBg = when (level) {
        DueLevel.URGENT -> UrgentBg
        DueLevel.NEAR -> NearBg
        else -> UbcmColors.CardWhite
    }
    val borderColor = when (level) {
        DueLevel.URGENT -> UbcmTheme.Gold500.copy(alpha = 0.45f)
        DueLevel.NEAR -> UbcmTheme.Gold500.copy(alpha = 0.3f)
        else -> UbcmColors.Line
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TodoCheckbox(todo.done)
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                todo.title,
                fontSize = 15.sp,
                color = if (todo.done) UbcmColors.Ash else UbcmColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (todo.subs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SubProgressBar(subs = todo.subs, cardColor = cardBg)
            }
        }

        Spacer(Modifier.width(10.dp))

        // DDL 固定宽 64dp（docs/21 §6.2）
        Column(Modifier.width(64.dp), horizontalAlignment = Alignment.End) {
            TodoRules.countdown(todo.dueAt, nowMillis)?.let {
                Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Danger, style = TABULAR)
            }
            TodoRules.dueDayText(todo.dueAt)?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = if (level == DueLevel.URGENT) UbcmColors.Ink else UbcmColors.InkSoft,
                    style = TABULAR,
                )
            }
            TodoRules.dueTimeText(todo.dueAt)?.let {
                Text(it, fontSize = 11.sp, color = UbcmColors.Ash, style = TABULAR)
            }
        }
    }
}

@Composable
private fun TodoCheckbox(done: Boolean) {
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (done) UbcmTheme.Success else Color.Transparent)
            .border(2.dp, if (done) UbcmTheme.Success else UbcmColors.Ash, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(Icons.Filled.Check, contentDescription = "已完成", tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * 子待办进度条（docs/21 §6.4）：
 * 纯色 brand-500 填充（无渐变）+ line 轨道 + 等分圆形节点。
 * **节点处轨道断开**：先用卡片底色画一个大一点的圆盖住轨道，再画节点本身。
 */
@Composable
private fun SubProgressBar(subs: List<SubTodo>, cardColor: Color) {
    val track = UbcmColors.Line
    val fill = UbcmTheme.Brand500
    val ring = UbcmColors.Ash

    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val n = subs.size
        if (n == 0) return@Canvas
        val cy = size.height / 2f
        val r = 4.dp.toPx()
        val lineW = 4.dp.toPx()
        val seg = size.width / n

        // 轨道（未完成部分）
        drawLine(track, Offset(0f, cy), Offset(size.width, cy), lineW, StrokeCap.Round)

        // 已完成填充：到最后一个已完成节点的圆心
        val doneCount = subs.count { it.done }
        if (doneCount > 0) {
            val endX = seg * (doneCount - 1) + seg / 2f
            drawLine(fill, Offset(0f, cy), Offset(endX, cy), lineW, StrokeCap.Round)
        }

        // 节点：先盖掉轨道，再画实心 / 描边
        for (i in 0 until n) {
            val x = seg * i + seg / 2f
            drawCircle(cardColor, radius = r + 1.dp.toPx(), center = Offset(x, cy),)
            if (subs[i].done) {
                drawCircle(fill, radius = r, center = Offset(x, cy))
            } else {
                drawCircle(cardColor, radius = r, center = Offset(x, cy))
                drawCircle(ring, radius = r, center = Offset(x, cy), style = Stroke(width = 1.5.dp.toPx()))
            }
        }
    }
}


// ---------------- 供 WeekScreen 复用的格式化工具 ----------------

fun periodLabel(s: CourseSession): String =
    if (s.startPeriod == s.endPeriod) "第 ${s.startPeriod} 节"
    else "第 ${s.startPeriod}–${s.endPeriod} 节"

fun clock(start: java.time.LocalDateTime, end: java.time.LocalDateTime): String =
    "%02d:%02d–%02d:%02d".format(start.hour, start.minute, end.hour, end.minute)
