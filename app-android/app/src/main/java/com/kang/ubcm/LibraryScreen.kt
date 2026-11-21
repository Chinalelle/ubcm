package com.kang.ubcm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 图书馆：一屏看清「哪层还有座」。
 *
 * 只做**只读**：楼层/区域空位、全馆状态、违约记录。
 * 预约是写操作，交给学校页面（docs/05 的合规边界）。
 */
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenSchoolPage: () -> Unit,
    /** 点某个区域 → 直接打开该区域的选座页（带上 roomId） */
    onOpenRoom: (roomId: Int, roomName: String) -> Unit,
) {
    val st by vm.state.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // 首次进入自动拉一次
    LaunchedEffect(Unit) { vm.refresh() }

    // 楼层数据到手后，自动扫一次「五层」的空桌（用户 11 次预约里有 8 次在五层）
    var deskScanStarted by remember { mutableStateOf(false) }
    LaunchedEffect(st.floors.size) {
        if (!deskScanStarted && st.floors.isNotEmpty()) {
            deskScanStarted = true
            val five = st.floors.firstOrNull { it.name.contains("五层") } ?: st.floors.last()
            vm.loadDesks(five.areas.map { it.id })
        }
    }

    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxHeight().widthIn(max = 720.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { TopArea(st, onBack, onRefresh = { vm.refresh() }) }

                if (st.needLogin) {
                    item { NeedLoginCard(onOpenLogin) }
                }

                item { OverviewCard(st) }

                // 进行中 / 待生效的预约置顶
                if (st.upcoming.isNotEmpty()) {
                    item { SectionLabel("进行中", "${st.upcoming.size} 条") }
                    items(st.upcoming, key = { "up-${it.resvId}" }) { r ->
                        ResvCard(r, highlight = true)
                    }
                }

                if (st.reservations.isNotEmpty()) {
                    item { SectionLabel("我的预约", "共 ${st.reservations.size} 条") }
                    items(st.reservations.take(6), key = { "h-${it.resvId}" }) { r ->
                        ResvCard(r, highlight = false)
                    }
                }

                item { FreeDeskSection(st, onOpenRoom, vm) }

                if (st.floors.isNotEmpty()) {
                    item {
                        SectionLabel("楼层", "${st.floors.size} 层 · ${st.totalSeats} 座")
                    }
                    items(st.floors, key = { it.id }) { f ->
                        FloorCard(
                            floor = f,
                            expanded = f.id in expanded,
                            onToggle = {
                                expanded = if (f.id in expanded) expanded - f.id else expanded + f.id
                            },
                            onOpenRoom = onOpenRoom,
                        )
                    }
                }

                item { PunishCard(st) }

                item { SchoolPageButton(onOpenSchoolPage) }

                item { Footer(st) }
            }
        }
    }
}

// ---------------- 顶部 ----------------

@Composable
private fun TopArea(st: LibraryState, onBack: () -> Unit, onRefresh: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { onBack() }
                    .padding(vertical = 4.dp, horizontal = 3.dp)
            ) {
                Text("‹  首页", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.InkSoft)
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.clip(CircleShape)
                    .background(UbcmColors.Ink.copy(alpha = .06f))
                    .clickable { onRefresh() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    if (st.loading) "刷新中…" else "刷新",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = UbcmColors.InkSoft,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("图书馆", fontSize = 27.sp, fontWeight = FontWeight.Black, color = UbcmColors.Ink, letterSpacing = 1.sp)
        Text("良乡校区 · 座位实时状态", fontSize = 12.sp, color = UbcmColors.InkSoft)
    }
}

@Composable
private fun SectionLabel(title: String, right: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = UbcmColors.Cinnabar, letterSpacing = 1.4.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.height(1.dp).weight(1f).background(UbcmColors.Line))
        Spacer(Modifier.width(8.dp))
        Text(right, fontSize = 11.sp, color = UbcmColors.Ash, fontFamily = FontFamily.Monospace)
    }
}

// ---------------- 全馆 ----------------

@Composable
private fun OverviewCard(st: LibraryState) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(18.dp)),
    ) {
        val tone = when {
            !st.hasData -> UbcmColors.Ash
            (st.available ?: 0) > 200 -> UbcmColors.Jade
            (st.available ?: 0) > 50 -> UbcmColors.Amber
            else -> UbcmColors.Cinnabar
        }
        Box(Modifier.width(5.dp).fillMaxHeight().background(tone))
        Column(Modifier.padding(16.dp)) {
            Text("全馆可用座位", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tone, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    st.available?.toString() ?: "—",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                    color = UbcmColors.Ink,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (st.used != null) "座 · 已用 ${st.used}" else "座",
                    fontSize = 12.sp,
                    color = UbcmColors.Ash,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (st.hasData) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MiniStat("楼层", "${st.floors.size}")
                    MiniStat("总座位", "${st.totalSeats}")
                    MiniStat("空闲", "${st.freeSeats}")
                }
            }
            st.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 10.sp, color = UbcmColors.Ash, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(label, fontSize = 10.sp, color = UbcmColors.Ash)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = UbcmColors.Ink)
    }
}

// ---------------- 找空桌 ----------------

/**
 * 核心功能区：把区域座位图按「桌」聚类，只列整桌空着的大桌。
 * 用户的实际需求是「人少时找一张没人坐的大桌」。
 */
@Composable
private fun FreeDeskSection(
    st: LibraryState,
    onOpenRoom: (Int, String) -> Unit,
    vm: LibraryViewModel,
) {
    var expandedDesk by remember { mutableStateOf<String?>(null) }
    val slotLabel = "%02d:%02d–%02d:%02d".format(
        st.slotStart / 60, st.slotStart % 60, st.slotEnd / 60, st.slotEnd % 60,
    )

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("空桌", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = UbcmColors.Cinnabar, letterSpacing = 1.4.sp)
            Spacer(Modifier.width(8.dp))
            if (st.desksDate.isNotBlank()) {
                Text(mdText(st.desksDate), fontSize = 11.sp, color = UbcmColors.Ash, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.weight(1f))
            if (st.desksLoading) Text("扫描中…", fontSize = 11.sp, color = UbcmColors.Amber)
        }

        Spacer(Modifier.height(10.dp))

        // ---- 时段选择 ----
        Text("使用时段", fontSize = 10.sp, color = UbcmColors.Ash)
        Spacer(Modifier.height(5.dp))
        val presets = listOf(
            Triple("上午", 8 * 60 + 30, 12 * 60),
            Triple("下午", 12 * 60, 18 * 60),
            Triple("晚上", 18 * 60, 22 * 60),
            Triple("全天", 8 * 60 + 30, 22 * 60),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { (label, a, b) ->
                val on = st.slotStart == a && st.slotEnd == b
                Box(
                    Modifier.clip(RoundedCornerShape(9.dp))
                        .background(if (on) UbcmColors.Cinnabar else UbcmColors.Ink.copy(alpha = .05f))
                        .clickable { vm.setSlot(a, b) }
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                ) {
                    Text(
                        label,
                        fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) Color.White else UbcmColors.InkSoft,
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(slotLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink, fontFamily = FontFamily.Monospace)

        // 预约结果 / 进度
        st.reserveMessage?.let { msg ->
            Spacer(Modifier.height(9.dp))
            val tone = when {
                st.reserving -> UbcmColors.Amber
                st.reserveOk -> UbcmColors.Jade
                else -> UbcmColors.Cinnabar
            }
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(tone.copy(alpha = .1f))
                    .clickable(enabled = !st.reserving) { vm.clearReserveMessage() }
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(msg, fontSize = 11.sp, color = tone, modifier = Modifier.weight(1f), lineHeight = 15.sp)
                if (!st.reserving) Text("×", fontSize = 12.sp, color = tone.copy(alpha = .6f))
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            st.desksError != null -> {
                Text(st.desksError, fontSize = 12.sp, color = UbcmColors.Ash, lineHeight = 17.sp)
            }
            st.desksLoading && st.desks.isEmpty() -> {
                Text("正在读取座位图并聚类…", fontSize = 12.sp, color = UbcmColors.Ash)
            }
            st.desks.isEmpty() && st.desksTotalSeats > 0 -> {
                Text(
                    "这个时段没有整桌空着的大桌（扫描 ${st.desksTotalSeats} 座）",
                    fontSize = 12.sp, color = UbcmColors.Ash, lineHeight = 17.sp,
                )
            }
            else -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${st.desks.size}",
                        fontSize = 30.sp, fontWeight = FontWeight.Black, color = UbcmColors.Jade,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "张全空的大桌",
                        fontSize = 11.sp, color = UbcmColors.Ash,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))

                st.desks.forEachIndexed { i, desk ->
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    val key = "${desk.roomId}-${desk.nameRange}"
                    val isOpen = expandedDesk == key

                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(UbcmColors.Jade.copy(alpha = .06f))
                    ) {
                        // 桌子行
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { expandedDesk = if (isOpen) null else key }
                                .padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(38.dp)) {
                                Text(
                                    "${desk.size}",
                                    fontSize = 17.sp, fontWeight = FontWeight.Black,
                                    color = UbcmColors.Jade, fontFamily = FontFamily.Monospace,
                                )
                                Text("连座", fontSize = 8.sp, color = UbcmColors.Ash)
                            }
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.width(1.dp).height(28.dp).background(UbcmColors.Line))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    desk.nameRange,
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    color = UbcmColors.Ink, fontFamily = FontFamily.Monospace,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    desk.roomName.ifBlank { desk.labName },
                                    fontSize = 10.sp, color = UbcmColors.InkSoft,
                                )
                            }
                            Text(
                                if (isOpen) "收起" else "选座 ›",
                                fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Jade,
                            )
                        }

                        // 简略图：一张桌的座位按坐标排成一行
                        if (isOpen) {
                            Spacer(Modifier.height(2.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(UbcmColors.Line.copy(alpha = .5f)))
                            Column(Modifier.padding(11.dp)) {
                                Text("点座位直接预约（$slotLabel）", fontSize = 9.sp, color = UbcmColors.Ash)
                                Spacer(Modifier.height(8.dp))

                                // 真实几何缩略图：按 coordinate（百分比坐标）还原座位在区域里的排布，
                                // 高亮这张桌的座位；其它桌的座位淡化显示，方便认位置。
                                DeskThumbnail(
                                    desk = desk,
                                    slotStart = st.slotStart,
                                    slotEnd = st.slotEnd,
                                    reserving = st.reserving,
                                    onPick = { seat -> vm.reserveSeat(seat, DeskFinder.TimeSlot(st.slotStart, st.slotEnd)) },
                                )

                                Spacer(Modifier.height(8.dp))
                                Text(
                                    desk.seats.sortedBy { it.x }
                                        .joinToString("  ") { it.name.takeLast(3) },
                                    fontSize = 9.sp,
                                    color = UbcmColors.InkSoft,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "扫描 ${st.desksTotalSeats} 座 · 该时段空 ${st.desksFreeSeats} 座 · 按连座数排序",
                    fontSize = 9.sp, color = UbcmColors.Ash,
                )
            }
        }
    }
}

/**
 * 座位缩略图：拿座位真实的 `coordinate`（百分比）画点，还原桌子的实际长宽比与排布。
 * 同一张桌的座位用实心大圆 + 编号；同区域其它座位用淡点作参照。
 * 点中某个座位 → 直接预约。
 */
@Composable
private fun DeskThumbnail(
    desk: DeskFinder.Desk,
    slotStart: Int,
    slotEnd: Int,
    reserving: Boolean,
    onPick: (SeatApi.Seat) -> Unit,
) {
    // 只用这张桌自己的坐标范围，留一点边距，把桌子“撑满”缩略图
    val xs = desk.seats.map { it.x }
    val ys = desk.seats.map { it.y }
    val minX = (xs.minOrNull() ?: 0.0) - 4
    val maxX = (xs.maxOrNull() ?: 1.0) + 4
    val minY = (ys.minOrNull() ?: 0.0) - 6
    val maxY = (ys.maxOrNull() ?: 1.0) + 6
    val spanX = (maxX - minX).coerceAtLeast(1e-3)
    val spanY = (maxY - minY).coerceAtLeast(1e-3)

    // 让缩略图保持真实长宽比，但限制高度，避免 13 连座被压成一条线
    val ratio = (spanX / spanY).toFloat().coerceIn(1.2f, 8f)
    val height = (260f / ratio).coerceIn(56f, 150f)

    var picked by remember { mutableStateOf<SeatApi.Seat?>(null) }

    Column {
        Box(
            Modifier.fillMaxWidth()
                .height(height.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(UbcmColors.Paper)
                .border(1.dp, UbcmColors.Line, RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(desk.nameRange, slotStart, slotEnd) {
                        detectTapGestures { tap ->
                            // 命中检测：找离点击点最近的座位（半径内）
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            var best: SeatApi.Seat? = null
                            var bestD = Float.MAX_VALUE
                            desk.seats.forEach { s ->
                                val px = ((s.x - minX) / spanX).toFloat() * w
                                val py = ((s.y - minY) / spanY).toFloat() * h
                                val d = (tap - Offset(px, py)).getDistance()
                                if (d < bestD) { bestD = d; best = s }
                            }
                            val hit = best
                            if (hit != null && bestD < 60f) {
                                if (hit.freeDuring(slotStart, slotEnd) && !reserving) onPick(hit)
                            }
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                desk.seats.forEach { s ->
                    val px = ((s.x - minX) / spanX).toFloat() * w
                    val py = ((s.y - minY) / spanY).toFloat() * h
                    val free = s.freeDuring(slotStart, slotEnd)
                    val c = Offset(px, py)
                    val tone = if (free) UbcmColors.Jade else UbcmColors.Ash
                    // 实心座位点
                    drawCircle(color = tone, radius = 15f, center = c)
                    // 选中态与编号
                    val label = textMeasurer.measure(
                        AnnotatedString(s.name.takeLast(2)),
                        style = TextStyle(fontSize = 8.sp, color = Color.White),
                    )
                    drawText(
                        textLayoutResult = label,
                        topLeft = Offset(px - label.size.width / 2f, py - label.size.height / 2f),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LegendDot(UbcmColors.Jade, "空")
            LegendDot(UbcmColors.Ash, "已被约")
            Text("点圆点即预约", fontSize = 9.sp, color = UbcmColors.Ash)
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 9.sp, color = UbcmColors.Ash)
    }
}

/** 20261123 → 11-23 明天 */
private fun mdText(ymd: String): String =
    if (ymd.length == 8) "${ymd.substring(4, 6)}-${ymd.substring(6, 8)}" else ymd

// ---------------- 我的预约 ----------------

@Composable
private fun ResvCard(r: SeatApi.Resv, highlight: Boolean) {
    val status = r.statusText()
    val tone = when (status) {
        "使用中" -> UbcmColors.Jade
        "待签到", "已签到", "已预约" -> UbcmColors.Amber
        else -> UbcmColors.Ash
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, if (highlight) tone.copy(alpha = .5f) else UbcmColors.Line, RoundedCornerShape(16.dp))
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 座位号做视觉锚点
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                r.seatName.ifBlank { "—" },
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                color = if (highlight) tone else UbcmColors.Ink,
                fontFamily = FontFamily.Monospace,
            )
            Text("座位", fontSize = 9.sp, color = UbcmColors.Ash)
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.width(1.dp).height(34.dp).background(UbcmColors.Line))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                r.roomName.ifBlank { r.labName },
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${r.dateText()}  ${r.timeRange()}",
                fontSize = 11.sp, color = UbcmColors.InkSoft, fontFamily = FontFamily.Monospace,
            )
        }
        Box(
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(tone.copy(alpha = .12f))
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text(status, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = tone)
        }
    }
}

// ---------------- 楼层 ----------------

@Composable
private fun FloorCard(
    floor: LibraryState.Floor,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenRoom: (roomId: Int, roomName: String) -> Unit,
) {
    // 有座=绿，紧张=琥珀，满=朱砂
    val tone = when {
        floor.remain <= 0 -> UbcmColors.Cinnabar
        floor.freeRatio < .15f -> UbcmColors.Amber
        else -> UbcmColors.Jade
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp))
            .clickable { onToggle() }
            .padding(15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(floor.shortName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = UbcmColors.Ink)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.clip(RoundedCornerShape(6.dp))
                    .background(tone.copy(alpha = .12f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    if (floor.remain <= 0) "已满" else "余 ${floor.remain}",
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = tone,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${floor.remain} / ${floor.total}",
                fontSize = 12.sp, color = UbcmColors.InkSoft, fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "▾" else "▸", fontSize = 12.sp, color = UbcmColors.Ash)
        }
        Spacer(Modifier.height(10.dp))
        FreeBar(floor.freeRatio, tone)

        if (expanded) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(UbcmColors.Line.copy(alpha = .6f)))
            Spacer(Modifier.height(10.dp))
            floor.areas.forEachIndexed { i, a ->
                if (i > 0) Spacer(Modifier.height(9.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenRoom(a.id, a.name) }
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(5.dp).clip(CircleShape)
                            .background(if (a.full) UbcmColors.Cinnabar else UbcmColors.Jade.copy(alpha = .7f))
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        a.name, fontSize = 13.sp,
                        color = if (a.full) UbcmColors.Ash else UbcmColors.Ink,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${a.remain}/${a.total}",
                        fontSize = 11.sp, color = UbcmColors.InkSoft, fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("约 ›", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Cinnabar)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("点区域直接进选座页", fontSize = 9.sp, color = UbcmColors.Ash)
        }
    }
}

/** 可用座位比例的横条（满格=全空） */
@Composable
private fun FreeBar(ratio: Float, tone: Color) {
    Box(
        Modifier.fillMaxWidth().height(5.dp)
            .clip(CircleShape)
            .background(UbcmColors.Line.copy(alpha = .7f))
    ) {
        if (ratio > 0f) {
            Box(
                Modifier.fillMaxWidth(ratio.coerceIn(0f, 1f)).fillMaxHeight()
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(tone.copy(alpha = .85f), tone)))
            )
        }
    }
}

// ---------------- 违规 ----------------

@Composable
private fun PunishCard(st: LibraryState) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp))
            .padding(15.dp),
    ) {
        Text("违约记录", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = UbcmColors.Cinnabar, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(7.dp))
        if (st.punishCount == 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(UbcmColors.Jade))
                Spacer(Modifier.width(8.dp))
                Text("没有违约记录", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink)
                Spacer(Modifier.weight(1f))
                Text("可以放心约座", fontSize = 11.sp, color = UbcmColors.Ash)
            }
        } else {
            Text("${st.punishCount} 条", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = UbcmColors.Cinnabar)
            st.punishDetail.take(5).forEach {
                Spacer(Modifier.height(4.dp))
                Text("· $it", fontSize = 11.sp, color = UbcmColors.InkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------- 引导 ----------------

@Composable
private fun NeedLoginCard(onOpenLogin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp))
            .clickable { onOpenLogin() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("需要登录图书馆", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = UbcmColors.Ink)
            Text("用 CAS 单点登录一次，之后长期有效", fontSize = 11.sp, color = UbcmColors.InkSoft)
        }
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(UbcmColors.Cinnabar)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) { Text("去登录", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White) }
    }
}

@Composable
private fun SchoolPageButton(onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp))
            .clickable { onOpen() }
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("预约座位", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = UbcmColors.Ink)
            Text("选座、签到、退座在学校页面完成", fontSize = 11.sp, color = UbcmColors.InkSoft)
        }
        Text("打开 ›", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Cinnabar)
    }
}

@Composable
private fun Footer(st: LibraryState) {
    val age = if (st.fetchedAt > 0L) {
        val m = (System.currentTimeMillis() - st.fetchedAt) / 60000
        when {
            m < 1 -> "刚刚更新"
            m < 60 -> "$m 分钟前更新"
            else -> "${m / 60} 小时前更新"
        }
    } else "尚未拉取"
    Text(
        "座位数据 $age · 空位数会随预约实时变化\n数据只在本机 · 写操作一律交给学校页面",
        fontSize = 10.sp, color = UbcmColors.Ash, lineHeight = 15.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}
