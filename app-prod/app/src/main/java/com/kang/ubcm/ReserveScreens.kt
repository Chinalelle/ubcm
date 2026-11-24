package com.kang.ubcm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.LocalDateTime

// ============================================================
// 共用：日期 + 时段
// ============================================================

private val DAY_CN2 = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 常见可约时段（学校 08:30–22:00，最短 30 分钟） */
private val SLOT_PRESETS = listOf(
    8 * 60 + 30 to 10 * 60 + 30,
    10 * 60 to 12 * 60,
    12 * 60 to 14 * 60,
    14 * 60 to 16 * 60,
    16 * 60 to 18 * 60,
    18 * 60 to 20 * 60,
    20 * 60 to 22 * 60,
)

private fun slotLabel(start: Int, end: Int): String =
    "%02d:%02d–%02d:%02d".format(start / 60, start % 60, end / 60, end % 60)

@Composable
private fun DateSlotBar(
    date: LocalDate,
    onShiftDay: (Int) -> Unit,
    startMin: Int,
    endMin: Int,
    onPickSlot: (Int, Int) -> Unit,
    trailing: @Composable () -> Unit = {},
    /** false = 时段不可点（空教室按节次选） */
    slotSelectable: Boolean = true,
) {
    var picking by remember { mutableStateOf(false) }
    val dayCn = DAY_CN2[date.dayOfWeek.value - 1]

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 日期
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(UbcmColors.CardWhite)
                    .border(1.dp, UbcmColors.Line, RoundedCornerShape(10.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "‹",
                    fontSize = 15.sp,
                    color = UbcmTheme.Brand700,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable { onShiftDay(-1) }.padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Text(
                    "${date.monthValue}月${date.dayOfMonth}日 $dayCn",
                    fontSize = 13.sp,
                    color = UbcmColors.Ink,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                Text(
                    "›",
                    fontSize = 15.sp,
                    color = UbcmTheme.Brand700,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable { onShiftDay(1) }.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            // 时段
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(UbcmColors.CardWhite)
                    .border(1.dp, UbcmColors.Line, RoundedCornerShape(10.dp))
                    .clickable(enabled = slotSelectable) { picking = !picking }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(slotLabel(startMin, endMin), fontSize = 13.sp, color = UbcmColors.Ink)
                if (slotSelectable) {
                    Spacer(Modifier.width(4.dp))
                    Text("▾", fontSize = 9.sp, color = UbcmColors.Ash)
                }
            }
            Spacer(Modifier.weight(1f))
            trailing()
        }

        if (picking) {
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(UbcmColors.CardWhite)
                    .border(1.dp, UbcmColors.Line, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SLOT_PRESETS.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (s, e) ->
                            val selected = s == startMin && e == endMin
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) UbcmTheme.Brand100 else UbcmTheme.Brand50)
                                    .clickable {
                                        onPickSlot(s, e)
                                        picking = false
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    slotLabel(s, e),
                                    fontSize = 12.sp,
                                    color = if (selected) UbcmTheme.Brand700 else UbcmColors.InkSoft,
                                )
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BackTitle(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "‹",
            fontSize = 20.sp,
            color = UbcmColors.Ink,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink)
    }
}

/** 每排 4 个的方格子（空间预约 / 空教室共用）。 */
private data class GridCell(val id: String, val title: String, val sub: String, val occupied: Boolean)

@Composable
private fun RoomGrid(cells: List<GridCell>, onCell: (GridCell) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { c ->
                    val bg = if (c.occupied) Color(0xFFFDECEC) else UbcmTheme.Brand100
                    val fg = if (c.occupied) UbcmColors.Danger else UbcmTheme.Brand700
                    Column(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(bg)
                            .then(
                                if (c.occupied) {
                                    Modifier.border(1.5.dp, UbcmColors.Danger, RoundedCornerShape(12.dp))
                                } else {
                                    Modifier.border(1.dp, UbcmTheme.Brand300.copy(alpha = .5f), RoundedCornerShape(12.dp))
                                }
                            )
                            .clickable(enabled = !c.occupied) { onCell(c) }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(c.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text(c.sub, fontSize = 10.sp, color = if (c.occupied) UbcmColors.Danger else UbcmTheme.Brand700.copy(alpha = .75f))
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ============================================================
// 座位预约（docs/25 §2）
// ============================================================

/**
 * 座位预约页：日期 + 时段 → 楼层折叠（同一时间只展开一层）→ 区域 → 座位图。
 *
 * 数据口径（用户已定）：楼层/区域用 `seatMenu` 的 `totalCount`（所有支持预约的座位）。
 * ⚠️ `seatMenu` 不带日期/时段参数，所以楼层空座始终是**当前实时值**；改日期时段只是
 * 重新拉一次（接口不支持按未来时段取），页面上不承诺"按所选时段计算空位"。
 */
@Composable
fun SeatReserveScreen(
    vm: LibraryViewModel,
    onBack: () -> Unit,
) {
    val st by vm.state.collectAsStateWithLifecycle()
    var date by remember { mutableStateOf(LocalDate.now()) }
    var startMin by remember { mutableStateOf(st.slotStart) }
    var endMin by remember { mutableStateOf(st.slotEnd) }
    var expanded by remember { mutableStateOf<Int?>(null) }
    var mapRoom by remember { mutableStateOf<Pair<Int, String>?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }

    if (mapRoom != null) {
        SeatMapPage(vm = vm, room = mapRoom!!, onBack = { mapRoom = null })
        return
    }

    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.fillMaxSize().widthIn(max = 720.dp).padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(8.dp))
                    BackTitle("图书馆 · 座位预约", onBack)
                    Spacer(Modifier.height(12.dp))

                    DateSlotBar(
                        date = date,
                        onShiftDay = { delta ->
                            date = date.plusDays(delta.toLong())
                            vm.refresh()
                        },
                        startMin = startMin,
                        endMin = endMin,
                        onPickSlot = { s, e ->
                            startMin = s
                            endMin = e
                            vm.setSlot(s, e)
                            vm.refresh()
                        },
                    )

                    Spacer(Modifier.height(12.dp))

                    // debug：座位 cookie 过期时用示例数据把楼层结构画出来（页面会标注"示例数据"）
                    val demoFloors = if (DebugInjection.enabled) DebugInjection.demoFloors else emptyList()
                    val floors = if (st.floors.isNotEmpty()) st.floors else demoFloors
                    val isDemo = st.floors.isEmpty() && demoFloors.isNotEmpty()

                    if (st.needLogin && floors.isEmpty()) {
                        HintCard("需要登录图书馆", "到「我的 → 凭证与登录」补齐座位 cookie")
                    } else if (floors.isEmpty() && st.loading) {
                        HintCard("正在查空座…", "")
                    } else if (floors.isEmpty()) {
                        HintCard("没有楼层数据", st.error ?: "点这里重试", onRetry = { vm.refresh() })
                    } else {
                        if (isDemo) {
                            Text(
                                "示例数据 · 座位凭证已过期，真实空座需在「我的 → 凭证与登录」网页登录一次",
                                fontSize = 10.sp,
                                color = UbcmColors.Warning,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        LazyColumn(
                            Modifier.fillMaxHeight(),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(floors.size) { i ->
                                val floor = floors[i]
                                FloorCard(
                                    floor = floor,
                                    expanded = expanded == floor.id,
                                    onToggle = { expanded = if (expanded == floor.id) null else floor.id },
                                    onArea = { a -> mapRoom = a.id to "${floor.shortName} ${a.name}" },
                                )
                            }
                            item {
                                Text(
                                    "空座为当前实时数据（接口不支持按未来时段查询）；时段仅用于选座筛选",
                                    fontSize = 10.sp,
                                    color = UbcmColors.Ash,
                                    modifier = Modifier.padding(2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloorCard(
    floor: LibraryState.Floor,
    expanded: Boolean,
    onToggle: () -> Unit,
    onArea: (LibraryState.Area) -> Unit,
) {
    // 空座占比 → 三档颜色（docs/25 §2.2）：≥50% brand-500 / <15% warning / 0 danger
    val ratio = if (floor.total > 0) floor.remain.toFloat() / floor.total else 0f
    val barColor = when {
        floor.total == 0 -> UbcmColors.Ash
        floor.remain == 0 -> UbcmColors.Danger
        ratio < 0.15f -> UbcmColors.Warning
        ratio < 0.5f -> UbcmTheme.Brand300
        else -> UbcmTheme.Brand500
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(floor.shortName, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = UbcmColors.Ink)
            Spacer(Modifier.weight(1f))
            Text(
                "${floor.remain}/${floor.total}",
                fontSize = 13.sp,
                color = UbcmColors.InkSoft,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                // 0 空座时轨道本身染成淡红：进度条宽度为 0，否则用户看不出"满了"
                .background(if (floor.total > 0 && floor.remain == 0) UbcmColors.Danger.copy(alpha = .25f) else UbcmColors.Line)
        ) {
            if (ratio > 0f) {
                Box(Modifier.fillMaxWidth(ratio).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(barColor))
            }
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            floor.areas.forEach { a ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onArea(a) }
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(a.name, fontSize = 14.sp, color = UbcmColors.Ink, modifier = Modifier.weight(1f))
                    Text(
                        "${a.remain}/${a.total}",
                        fontSize = 12.sp,
                        color = UbcmColors.InkSoft,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("›", fontSize = 16.sp, color = UbcmColors.Ash)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(UbcmColors.Line.copy(alpha = .5f)))
            }
        }
    }
}

@Composable
private fun HintCard(title: String, sub: String, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(title, fontSize = 15.sp, color = UbcmColors.Ink)
        if (sub.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(sub, fontSize = 12.sp, color = UbcmColors.Ash)
        }
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "重试",
                fontSize = 13.sp,
                color = UbcmTheme.Brand500,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onRetry)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 全屏座位图（与 LibraryScreen 里的一致：拖动/缩放/点座）。
 * 只读展示；选座预约仍是写操作，不在 App 内提交。
 */
@Composable
private fun SeatMapPage(vm: LibraryViewModel, room: Pair<Int, String>, onBack: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(room.first) { vm.loadMapRoom(room.first, room.second) }

    val criteria = SeatFilter.Criteria(st.slotStart, st.slotEnd, st.filterType, st.filterOnlyFree)
    val highlighted = remember(st.mapSeats, st.filterType, st.filterOnlyFree, st.slotStart, st.slotEnd) {
        vm.highlightedRanges()
    }

    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "‹",
                    fontSize = 20.sp,
                    color = UbcmColors.Ink,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(room.second, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink)
                    Text(
                        "${slotLabel(st.slotStart, st.slotEnd)} · ${st.mapSeats.count { it.freeDuring(st.slotStart, st.slotEnd) }} 个空座",
                        fontSize = 11.sp,
                        color = UbcmColors.Ash,
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                when {
                    st.mapLoading && st.mapSeats.isEmpty() -> Text("加载座位图…", fontSize = 12.sp, color = UbcmColors.Ash, modifier = Modifier.align(Alignment.Center))
                    st.mapError != null -> Text(st.mapError!!, fontSize = 12.sp, color = UbcmColors.Danger, modifier = Modifier.align(Alignment.Center))
                    st.mapSeats.isEmpty() -> Text("这个区域暂时没有座位数据", fontSize = 12.sp, color = UbcmColors.Ash, modifier = Modifier.align(Alignment.Center))
                    else -> RoomSeatMap(
                        seats = st.mapSeats,
                        criteria = criteria,
                        highlighted = highlighted,
                        modifier = Modifier.fillMaxSize(),
                        heightDp = null,
                    )
                }
            }
            Text(
                "只读查看：预约/签到/退座仍在学校页面完成",
                fontSize = 10.sp,
                color = UbcmColors.Ash,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

// ============================================================
// 空间预约（docs/25 §4）
// ============================================================

/**
 * 空间预约：日期时段 + **类型分段**（研讨间/静音仓/朗读亭）+ 每排 4 个房间网格。
 *
 * 空间没有楼层/区域层级（实测结论），所以是「类型 → 房间」。
 * 🔴 只读：提交接口未实测，这里不提供任何"预约/占房"按钮。
 */
@Composable
fun SpaceReserveScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var kinds by remember { mutableStateOf<List<SpaceApi.Kind>>(emptyList()) }
    var rooms by remember { mutableStateOf<List<SpaceApi.Room>>(emptyList()) }
    var kindId by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    var date by remember { mutableStateOf(LocalDate.now()) }
    var startMin by remember { mutableStateOf(14 * 60) }
    var endMin by remember { mutableStateOf(16 * 60) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        val cookie = Session.loadSeatCookie(ctx)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (cookie.isNullOrBlank()) {
                error = "需要登录图书馆（座位 ic-cookie）"
                loading = false
                return@withContext
            }
            val (ks, kErr) = SpaceApi.kinds(cookie)
            val (rs, rErr) = SpaceApi.rooms(cookie)
            kinds = ks
            rooms = rs
            if (kindId == null && ks.isNotEmpty()) kindId = ks.first().id
            error = kErr ?: rErr
            loading = false
        }
    }

    val demoKinds = if (DebugInjection.enabled) DebugInjection.demoKinds else emptyList()
    val demoRooms = if (DebugInjection.enabled) DebugInjection.demoRooms else emptyList()
    val shownKinds = if (kinds.isNotEmpty()) kinds else demoKinds
    val shownRooms = if (rooms.isNotEmpty()) rooms else demoRooms
    val isDemo = rooms.isEmpty() && demoRooms.isNotEmpty()

    val start = date.atTime(startMin / 60, startMin % 60)
    val end = date.atTime(endMin / 60, endMin % 60)

    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.fillMaxSize().widthIn(max = 720.dp).padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(8.dp))
                    BackTitle("空间预约", onBack)
                    Spacer(Modifier.height(12.dp))

                    DateSlotBar(
                        date = date,
                        onShiftDay = { delta ->
                            date = date.plusDays(delta.toLong())
                            reload++
                        },
                        startMin = startMin,
                        endMin = endMin,
                        onPickSlot = { s, e ->
                            startMin = s
                            endMin = e
                        },
                        trailing = {
                            Text(
                                "刷新",
                                fontSize = 12.sp,
                                color = UbcmTheme.Brand500,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                    .clickable { reload++ }.padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        },
                    )

                    Spacer(Modifier.height(12.dp))

                    // 类型分段
                    if (shownKinds.isNotEmpty()) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(UbcmColors.CardWhite)
                                .padding(3.dp),
                        ) {
                            shownKinds.forEach { k ->
                                val selected = k.id == kindId
                                val count = shownRooms.count { it.kindId == k.id }
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (selected) UbcmTheme.Brand100 else Color.Transparent)
                                        .clickable { kindId = k.id }
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        if (count > 0) "${k.name} $count" else k.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                        color = if (selected) UbcmTheme.Brand700 else UbcmColors.InkSoft,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    when {
                        loading && shownRooms.isEmpty() -> HintCard("正在查房间…", "")
                        shownRooms.isEmpty() -> HintCard(
                            "拿不到房间数据",
                            error ?: "点重试再拉一次",
                            onRetry = { reload++ },
                        )
                        else -> {
                            if (isDemo) {
                                Text(
                                    "示例数据 · 座位凭证已过期，真实房间需网页登录一次",
                                    fontSize = 10.sp,
                                    color = UbcmColors.Warning,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            }
                            val visible = shownRooms.filter { kindId == null || it.kindId == kindId || it.kindId < 0 }
                            val occupied = visible.count { SpaceApi.isOccupied(it, start, end) }
                            Text(
                                "所选时段 ${slotLabel(startMin, endMin)}：${visible.size - occupied} 间空闲 / ${visible.size} 间",
                                fontSize = 11.sp,
                                color = UbcmColors.Ash,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            LazyColumn(
                                Modifier.fillMaxHeight(),
                                contentPadding = PaddingValues(bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                item {
                                    RoomGrid(
                                        cells = SpaceApi.sorted(visible).map { r ->
                                            val occ = SpaceApi.isOccupied(r, start, end)
                                            GridCell(
                                                id = r.devId.toString(),
                                                title = r.name,
                                                sub = if (occ) "占用" else "空闲",
                                                occupied = occ,
                                            )
                                        },
                                        onCell = { /* 只读：不做任何预约动作 */ },
                                    )
                                }
                                item {
                                    Text(
                                        "占用判定：房间当天预约区间与所选时段重叠。resvStatus 1093/1027/1029 暂统一按占用处理（语义待确认）",
                                        fontSize = 10.sp,
                                        color = UbcmColors.Ash,
                                    )
                                }
                                item {
                                    Text(
                                        "只读查看：空间预约提交需先抓真实请求，本轮不提供",
                                        fontSize = 10.sp,
                                        color = UbcmColors.Ash,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 空教室（docs/27，只读）
// ============================================================

/**
 * 空教室：日期 + 当前节次 + 教学楼分段 + 每排 4 个教室网格。
 *
 * ⚠️ 这是**查询**不是预约：教务只提供空闲教室查询（HTML、非实时），没有预约接口。
 * 接口参数未实测（docs/27 §5），拿不到就显示"暂无数据"，不崩。
 */
@Composable
fun EmptyRoomScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var date by remember { mutableStateOf(LocalDate.now()) }
    var periodStart by remember { mutableStateOf(currentPeriod().first) }
    var periodEnd by remember { mutableStateOf(currentPeriod().second) }
    var rooms by remember { mutableStateOf<List<JwApi.EmptyRoom>>(emptyList()) }
    var building by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var updatedAt by remember { mutableStateOf(0L) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload, date, periodStart, periodEnd) {
        loading = true
        error = null
        val jw = Session.loadJwSession(ctx)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (jw.isNullOrBlank()) {
                error = "需要教务会话（我的 → 凭证与登录）"
                loading = false
                return@withContext
            }
            val firstMonday = Timetable.parseFirstWeekMonday(Session.loadFirstWeekMonday(ctx))
                ?: LocalDate.parse(Timetable.DEFAULT_FIRST_WEEK_MONDAY)
            val week = Timetable.weekOf(date, firstMonday)
            val resp = try {
                JwApi.emptyRoomPage(
                    jsessionCookie = jw,
                    week = week.toString(),
                    day = date.dayOfWeek.value.toString(),
                    period = "$periodStart-$periodEnd",
                )
            } catch (e: Exception) {
                error = "${e.javaClass.simpleName}: ${e.message}"
                loading = false
                return@withContext
            }
            val parsed = JwApi.parseEmptyRooms(resp.body)
            rooms = parsed
            updatedAt = System.currentTimeMillis()
            error = when {
                parsed.isNotEmpty() -> null
                JwApi.isLoggedIn(resp.body) -> null
                else -> "当前时段没有可用数据（教务可能未登录或接口参数需调整）"
            }
            loading = false
        }
    }

    val demoRooms = if (DebugInjection.enabled) DebugInjection.demoEmptyRooms else emptyList()
    val allRooms = if (rooms.isNotEmpty()) rooms else demoRooms
    val isDemo = rooms.isEmpty() && demoRooms.isNotEmpty()
    val buildings = remember(allRooms) { allRooms.map { it.building.ifBlank { "其他" } }.distinct() }
    val shown = allRooms.filter { building == null || it.building.ifBlank { "其他" } == building }

    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.fillMaxSize().widthIn(max = 720.dp).padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(8.dp))
                    BackTitle("空教室", onBack)
                    Spacer(Modifier.height(12.dp))

                    DateSlotBar(
                        date = date,
                        onShiftDay = { delta -> date = date.plusDays(delta.toLong()) },
                        startMin = periodStartMin(periodStart),
                        endMin = periodEndMin(periodEnd),
                        onPickSlot = { _, _ -> },
                        slotSelectable = false,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "‹",
                                    fontSize = 15.sp,
                                    color = UbcmTheme.Brand700,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            val p = (periodStart - 1).coerceAtLeast(1)
                                            periodStart = p
                                            periodEnd = (p + 1).coerceAtMost(12)
                                        }.padding(horizontal = 6.dp, vertical = 6.dp),
                                )
                                Text(
                                    if (periodStart == periodEnd) "第 $periodStart 节"
                                    else "第 $periodStart–$periodEnd 节",
                                    fontSize = 13.sp, color = UbcmColors.Ink,
                                )
                                Text(
                                    "›",
                                    fontSize = 15.sp,
                                    color = UbcmTheme.Brand700,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            val p = (periodStart + 1).coerceAtMost(11)
                                            periodStart = p
                                            periodEnd = (p + 1).coerceAtMost(12)
                                        }.padding(horizontal = 6.dp, vertical = 6.dp),
                                )
                            }
                        },
                    )

                    Spacer(Modifier.height(12.dp))

                    if (buildings.size > 1) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(UbcmColors.CardWhite)
                                .padding(3.dp),
                        ) {
                            buildings.forEach { b ->
                                val selected = b == (building ?: buildings.first())
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (selected) UbcmTheme.Brand100 else Color.Transparent)
                                        .clickable { building = b }
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        b,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                        color = if (selected) UbcmTheme.Brand700 else UbcmColors.InkSoft,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    when {
                        loading -> HintCard("正在查询空教室…", "")
                        shown.isEmpty() -> HintCard(
                            if (error != null) "暂时拿不到空教室数据" else "当前时段没有可用数据",
                            error ?: "教务接口参数可能需调整，稍后再试",
                            onRetry = { reload++ },
                        )
                        else -> LazyColumn(
                            Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (isDemo) {
                                item {
                                    Text(
                                        "示例数据 · 教务会话已过期，真实空教室需重新登录教务",
                                        fontSize = 10.sp,
                                        color = UbcmColors.Warning,
                                    )
                                }
                            }
                            item {
                                RoomGrid(
                                    cells = shown.take(80).map { r ->
                                        GridCell(
                                            id = r.room,
                                            title = r.room,
                                            sub = if (r.free) "空闲" else "占用",
                                            occupied = !r.free,
                                        )
                                    },
                                    onCell = { },
                                )
                            }
                        }
                    }

                    Text(
                        "非实时 · 更新于 ${if (updatedAt > 0) fmtClock(updatedAt) else "--:--"} · 仅作参考，不提供预约",
                        fontSize = 10.sp,
                        color = UbcmColors.Ash,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private fun fmtClock(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(Agenda.ZONE)
        .toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

/** 当前该看哪几节（含课间时顺延到下一节）。 */
private fun currentPeriod(): Pair<Int, Int> {
    val now = LocalDateTime.now()
    val min = now.hour * 60 + now.minute
    val slots = Timetable.FALLBACK_SLOTS
    val hit = slots.firstOrNull { min >= it.startMin && min < it.endMin }
    if (hit != null) return hit.period to minOf(hit.period + 1, 12)
    val next = slots.firstOrNull { it.startMin > min } ?: slots.last()
    return next.period to minOf(next.period + 1, 12)
}

private fun periodStartMin(p: Int): Int =
    Timetable.FALLBACK_SLOTS.firstOrNull { it.period == p }?.startMin ?: (8 * 60)

private fun periodEndMin(p: Int): Int =
    Timetable.FALLBACK_SLOTS.firstOrNull { it.period == p }?.endMin ?: (22 * 60)
