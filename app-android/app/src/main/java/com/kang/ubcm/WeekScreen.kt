package com.kang.ubcm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 课表周视图。
 *
 * 三条设计决定，都是有代价的选择：
 *
 * 1. **按节次等高等距排版，不按真实时间比例**。
 *    良乡作息里 4→5 节空 90 分钟（午休）、8→9 节空 60 分钟（晚饭），
 *    按时间比例画会把晚上两节课压成一条线。等距才是课表该有的样子。
 *
 * 2. **跨节块占 `end-start+1` 个行高**，而不是把课塞进「大节」格子。
 *    正常人体学Ⅰ 是周二 1–4 节，按大节分组会被切成「1–2」「3–4」两块，和实际不符。
 *
 * 3. **周次默认跟随今天**，用户手动翻走后才停住。
 *    每次进来都自动跳回本周，是用户真正想要的行为；但翻到第 3 周查课时不能被 ticker 拽回来。
 */
@Composable
fun WeekScreen(
    vm: HomeViewModel,
    onBack: () -> Unit,
    onOpenProbe: () -> Unit,
) {
    val st by vm.state.collectAsStateWithLifecycle()
    var picked by remember { mutableStateOf<WeekGrid.Slot?>(null) }

    val shownWeek = st.shownWeek
    // 换周时收起详情，否则会显示上一周的课
    LaunchedEffect(shownWeek) { picked = null }

    val grid = remember(st.sessions, shownWeek) { WeekGrid.build(st.sessions, shownWeek) }
    val range = remember(st.sessions) { WeekGrid.weekRange(st.sessions) }

    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.fillMaxSize().widthIn(max = MAX_WIDTH)) {
                WeekTopBar(
                    st = st,
                    grid = grid,
                    range = range,
                    onBack = onBack,
                    onOpenProbe = onOpenProbe,
                    onPrev = { vm.shiftWeek(-1) },
                    onNext = { vm.shiftWeek(1) },
                    onToday = { vm.selectWeek(null) },
                )

                if (st.sessions.isEmpty()) {
                    EmptyTimetable(Modifier.weight(1f))
                } else {
                    DayHeader(grid)
                    // 只有网格本体滚动，星期表头和顶部导航始终固定 ——
                    // 12 行在手机上装不下，滚走表头就不知道看的是星期几了。
                    Box(Modifier.weight(1f)) {
                        GridBody(
                            st = st,
                            grid = grid,
                            pickedName = picked?.let { key(it) },
                            // 点已选中的块再点一次 = 取消，符合"点开/点收"的直觉
                            onPick = { s ->
                                picked = if (picked != null && key(picked!!) == key(s)) null else s
                            },
                        )
                    }
                }

                picked?.let { s ->
                    SlotDetail(s, st, onClose = { picked = null })
                }

                AnchorBar(
                    firstMonday = st.firstWeekMonday,
                    onSave = { vm.setFirstWeekMonday(it) },
                )
            }
        }
    }
}

/** 同一个块在 recompose 前后要能认出「还是它」，用 课名+星期+节次 做 key */
private fun key(s: WeekGrid.Slot) =
    "${s.session.name}|${s.session.dayOfWeek}|${s.startPeriod}-${s.endPeriod}|${s.session.location}"

// ---------------- 顶部 ----------------

@Composable
private fun WeekTopBar(
    st: HomeState,
    grid: WeekGrid.Grid,
    range: IntRange?,
    onBack: () -> Unit,
    onOpenProbe: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹  首页",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = UbcmColors.InkSoft,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onBack() }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text("课表", fontSize = 24.sp, fontWeight = FontWeight.Black, color = UbcmColors.Ink, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "数据体检",
                fontSize = 11.sp,
                color = UbcmColors.InkSoft,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onOpenProbe() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(10.dp))

        // 周次切换
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("‹", enabled = st.shownWeek > HomeViewModel.MIN_NAV_WEEK) { onPrev() }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (st.shownWeek >= 1) "第 ${st.shownWeek} 周" else "未开学",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = UbcmColors.Ink,
                    )
                    Spacer(Modifier.width(7.dp))
                    if (st.onCurrentWeek) {
                        Tag("本周", UbcmColors.Cinnabar)
                    } else {
                        Tag("非本周", UbcmColors.Ash)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        weekDateRange(grid.week, st.firstWeekMonday),
                        fontSize = 11.sp,
                        color = UbcmColors.InkSoft,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle(st, grid, range),
                    fontSize = 10.sp,
                    color = UbcmColors.Ash,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (!st.onCurrentWeek) {
                Text(
                    "回到本周",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = UbcmColors.Cinnabar,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp))
                        .background(UbcmColors.Cinnabar.copy(alpha = .10f))
                        .clickable { onToday() }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            StepButton("›") { onNext() }
        }
    }
}

private fun subtitle(st: HomeState, grid: WeekGrid.Grid, range: IntRange?): String {
    if (st.sessions.isEmpty()) return "还没有课表数据"
    val courses = grid.slots.map { it.session.name }.distinct().size
    val sessions = grid.slots.size
    val span = if (range != null) " · 全学期第 ${range.first}–${range.last} 周" else ""
    return "本周 $courses 门 / $sessions 次课$span"
}

private fun weekDateRange(week: Int, firstWeekMonday: LocalDate): String {
    if (week < 1) return ""
    val mon = Timetable.mondayOf(week, firstWeekMonday)
    val sun = mon.plusDays(6)
    val f = DateTimeFormatter.ofPattern("MM/dd")
    return "${f.format(mon)}–${f.format(sun)}"
}

@Composable
private fun StepButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) UbcmColors.CardWhite else UbcmColors.Line.copy(alpha = .4f))
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) UbcmColors.Ink else UbcmColors.Ash,
        )
    }
}

@Composable
private fun Tag(text: String, tone: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(5.dp)).background(tone.copy(alpha = .12f))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(text, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = tone)
    }
}

// ---------------- 星期表头 ----------------

@Composable
private fun DayHeader(grid: WeekGrid.Grid) {
    val today = LocalDate.now().dayOfWeek.value
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(Modifier.width(AXIS_W))
        grid.days.forEach { d ->
            val isToday = d == today
            Column(
                Modifier.weight(1f).padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    DAY_NAMES.getOrElse(d - 1) { "?" },
                    fontSize = 11.sp,
                    fontWeight = if (isToday) FontWeight.Black else FontWeight.SemiBold,
                    color = if (isToday) UbcmColors.Cinnabar else UbcmColors.InkSoft,
                )
            }
        }
    }
}

// ---------------- 网格本体 ----------------

@Composable
private fun GridBody(
    st: HomeState,
    grid: WeekGrid.Grid,
    pickedName: String?,
    onPick: (WeekGrid.Slot) -> Unit,
) {
    val scroll = rememberScrollState()
    val today = LocalDate.now().dayOfWeek.value
    val gridHeight = WeekGeometry.totalHeight(grid.periods, ROW_DP, GAP_DP).dp

    // 注意不能写 fillMaxSize()：fillMaxSize 会把高度钉死成视口高度，verticalScroll 就滚不动了。
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(scroll)) {
        // 左侧节次轴
        Column(Modifier.width(AXIS_W)) {
            for (p in 1..grid.periods) {
                val slot = Timetable.slotOf(p, st.slots)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(ROW_DP.dp)
                        .padding(top = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "$p",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = UbcmColors.InkSoft,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (slot != null) {
                        Text(
                            Timetable.minutesToLocalTime(slot.startMin),
                            fontSize = 8.sp,
                            color = UbcmColors.Ash,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                if (p < grid.periods) Spacer(Modifier.height(GAP_DP.dp))
            }
        }

        grid.days.forEach { d ->
            // 分栏要用真实列宽算横向偏移，所以每列单独取一次约束
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .height(gridHeight)
                    .padding(start = 2.dp)
            ) {
                val colW = maxWidth
                // 行背景：让"第几节"有实体感，也让跨节块看起来是压在格子上
                for (p in 1..grid.periods) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .offset(y = WeekGeometry.topOf(p, ROW_DP, GAP_DP).dp)
                            .height(ROW_DP.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                if (d == today) UbcmColors.Cinnabar.copy(alpha = .035f)
                                else UbcmColors.Ink.copy(alpha = .022f)
                            )
                    )
                }
                // 课程块
                grid.slotsOn(d).forEach { s ->
                    CourseBlock(
                        slot = s,
                        colWidth = colW,
                        isPicked = pickedName == key(s),
                        onPick = { onPick(s) },
                    )
                }
            }
        }
    }
}

/**
 * 一个课程块。位置和高度完全由节次算出来（[WeekGeometry]），不靠流式布局 ——
 * 这是「1–3 节必须是一整块」能成立的前提。
 *
 * 横向：同一天的课若有冲突（见 [WeekGrid.assignLanes]），按 lane/laneCount 均分列宽并排。
 */
@Composable
private fun CourseBlock(
    slot: WeekGrid.Slot,
    colWidth: androidx.compose.ui.unit.Dp,
    isPicked: Boolean,
    onPick: () -> Unit,
) {
    val tone = courseTone(slot.session.name)
    val top = WeekGeometry.topOf(slot.startPeriod, ROW_DP, GAP_DP).dp
    val h = WeekGeometry.heightOf(slot.startPeriod, slot.endPeriod, ROW_DP, GAP_DP).dp
    val laneW = colWidth / slot.laneCount.toFloat()
    val x = laneW * slot.lane.toFloat()

    Row(
        Modifier
            .offset(x = x, y = top)
            .width((laneW - LANE_GAP_DP.dp).coerceAtLeast(1.dp))
            .height(h)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isPicked) tone.copy(alpha = .26f) else tone.copy(alpha = .13f))
            .border(1.dp, tone.copy(alpha = if (isPicked) .80f else .42f), RoundedCornerShape(6.dp))
            .clickable { onPick() },
    ) {
        // 左侧实色竖条。整块背景是淡的（暖白纸面风格），
        // 光靠淡背景区分「中医基础学」和「正常人体学Ⅰ」几乎分不出来 —— 加一条实色就一眼能认。
        Box(Modifier.width(3.dp).fillMaxHeight().background(tone.copy(alpha = .88f)))
        Column(Modifier.padding(start = 4.dp, end = 3.dp, top = 2.dp, bottom = 2.dp)) {
            Text(
                slot.session.name,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                color = tone,
                maxLines = if (slot.span >= 2) 3 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (slot.span >= 2 && slot.session.location.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    slot.session.location,
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    color = UbcmColors.InkSoft,
                    maxLines = if (slot.span >= 3) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------- 详情 ----------------

@Composable
private fun SlotDetail(s: WeekGrid.Slot, st: HomeState, onClose: () -> Unit) {
    val tone = courseTone(s.session.name)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.width(4.dp).height(38.dp).clip(RoundedCornerShape(2.dp)).background(tone))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    s.session.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = UbcmColors.Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${periodLabel(s.session)}  ${slotClock(s, st.slots)}",
                    fontSize = 11.sp,
                    color = UbcmColors.InkSoft,
                )
            }
            Text(
                "收起",
                fontSize = 11.sp,
                color = UbcmColors.Ash,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onClose() }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(9.dp))
        DetailLine("教室", s.session.location.ifBlank { "未排教室" })
        if (s.session.teacher.isNotBlank()) DetailLine("教师", s.session.teacher)
        if (s.session.className.isNotBlank()) DetailLine("班级", s.session.className)
        DetailLine("周次", if (s.session.weeks.isEmpty()) "—" else compressWeeks(s.session.weeks))
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, fontSize = 11.sp, color = UbcmColors.Ash, modifier = Modifier.width(40.dp))
        Text(value, fontSize = 12.sp, color = UbcmColors.Ink, lineHeight = 16.sp)
    }
}

/** 这节具体是几点到几点（跨节按首末节算） */
private fun slotClock(s: WeekGrid.Slot, slots: List<PeriodSlot>): String {
    val a = Timetable.slotOf(s.startPeriod, slots) ?: return ""
    val b = Timetable.slotOf(s.endPeriod, slots) ?: a
    return "${Timetable.minutesToLocalTime(a.startMin)}–${Timetable.minutesToLocalTime(b.endMin)}"
}

// ---------------- 空状态 ----------------

@Composable
private fun EmptyTimetable(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("还没有课表", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = UbcmColors.Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "去「数据体检 / 登录」用教务系统登录一次，\n课表会自动拉取并按本地缓存离线可用。",
            fontSize = 12.sp,
            color = UbcmColors.InkSoft,
            lineHeight = 18.sp,
        )
    }
}

// ---------------- 配色与常量 ----------------

/**
 * 课程配色：按课名稳定散列到一组柔和的色板。
 * 同一门课在周视图、今日卡片里永远同一个颜色，用户靠颜色就能认课。
 *
 * 用 floorMod 而不是 abs(hashCode)：hashCode 可能是 Int.MIN_VALUE，abs 会溢出为负数，
 * 取下标直接 ArrayIndexOutOfBounds。
 */
private val COURSE_TONES = listOf(
    Color(0xFFB33A2B), // 朱砂
    Color(0xFF2E7D5B), // 玉
    Color(0xFFC98A1F), // 琥珀
    Color(0xFF3E6B94), // 靛
    Color(0xFF7A5C9E), // 紫
    Color(0xFFA8553F), // 赭
    Color(0xFF4F7A4A), // 苔
    Color(0xFF9B5A7C), // 茜
)

fun courseTone(name: String): Color =
    COURSE_TONES[Math.floorMod(name.hashCode(), COURSE_TONES.size)]

/** 行高 / 节间距（dp）。46 + 4 让 12 节在手机上刚好一屏多一点。 */
private const val ROW_DP = 46f
private const val GAP_DP = 4f

/** 左侧节次轴宽度。40dp 够放两行（节次号 + 起始时间） */
private val AXIS_W = 40.dp

/**
 * 内容最大宽度。
 *
 * 首页是卡片流，640dp 就够；**课表不一样**：它是横向密集内容，
 * 限太窄在平板（3408px）上两边会空出大半屏，7 列被挤成细条。
 * 1100dp 是「平板铺得开、手机自动变窄」的折中。
 */
private val MAX_WIDTH = 1100.dp

/** 分栏并排时两块之间留的缝，否则颜色块会连成一片 */
private const val LANE_GAP_DP = 1.5f

private val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 周次锚点设置条。
 *
 * 为什么需要它：周次 = floorDiv(今天 − 锚点, 7) + 1，锚点是**写死的常量**。
 * 换学期不更新，首页倒计时会静默算错（显示"第 3 周"其实是第 1 周），
 * 周视图更惨 —— 整张表全是错的，而且看不出错在哪。
 *
 * 自动化（从教务首页读"第 X 周"）还没做，但至少用户能自己改，
 * 不用等发新版。这里是最后一道保险，不是最终方案。
 */
@Composable
private fun AnchorBar(firstMonday: LocalDate, onSave: (String) -> String?) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(firstMonday) { mutableStateOf(firstMonday.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
                    color = UbcmColors.Cinnabar,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).clickable {
                        text = firstMonday.toString()
                        error = null
                        editing = true
                    }.padding(horizontal = 6.dp, vertical = 2.dp),
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
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; error = null },
                        singleLine = true,
                        isError = error != null,
                        textStyle = LocalTextStyle.current.copy(
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
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
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
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .clickable { editing = false }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 10.sp, color = UbcmColors.Cinnabar, lineHeight = 14.sp)
                }
            }
        }
    }
}
