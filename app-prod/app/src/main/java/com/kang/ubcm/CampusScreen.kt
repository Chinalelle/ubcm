package com.kang.ubcm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate

/** 校园页跳转动作。 */
data class CampusActions(
    val openEcard: () -> Unit = {},
    val openLogistics: () -> Unit = {},
    val openJw: () -> Unit = {},
    val openLibrary: () -> Unit = {},
    val openSpace: () -> Unit = {},
    val openPortal: () -> Unit = {},
    val openEmptyRoom: () -> Unit = {},
    val openMore: () -> Unit = {},
)

private const val DAY_MS = 24L * 3600 * 1000

/**
 * 校园页（docs/26）：校园卡（余额/今日/折线/消费分布）+ 服务宫格。
 *
 * 图表全部自绘 Canvas，**没有引入图表库**（项目零第三方运行时依赖）。
 */
@Composable
fun CampusScreen(vm: CampusViewModel, actions: CampusActions) {
    val st by vm.state.collectAsStateWithLifecycle()
    var days by remember { mutableStateOf(7) }

    LaunchedEffect(Unit) { if (st.spends.isEmpty()) vm.refresh() }

    val now = System.currentTimeMillis()
    val sinceMs = now - days * DAY_MS
    val daily = remember(st.spends, days) {
        SpendCategory.dailyTotals(st.spends, days, LocalDate.now())
    }
    val slices = remember(st.spends, days) { SpendCategory.topCategories(st.spends, sinceMs, limit = 6) }
    val total = remember(st.spends, days) { SpendCategory.totalYuan(st.spends, sinceMs) }

    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            LazyColumn(
                Modifier.fillMaxHeight().align(Alignment.TopCenter).widthIn(max = 640.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text("校园", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink)
                }

                if (st.isDemo) {
                    item {
                        Text(
                            "示例数据 · 一卡通凭证已过期，真实余额/流水需重新登录",
                            fontSize = 10.sp,
                            color = UbcmColors.Warning,
                        )
                    }
                }

                item {
                    CardCard(
                        st = st,
                        days = days,
                        onDays = { days = it },
                        daily = daily,
                        slices = slices,
                        total = total,
                        onOpenEcard = actions.openEcard,
                    )
                }

                item { ServiceGrid(actions) }
            }
        }
    }
}

// ---------------- 校园卡 ----------------

@Composable
private fun CardCard(
    st: CampusViewModel.State,
    days: Int,
    onDays: (Int) -> Unit,
    daily: List<Pair<LocalDate, Double>>,
    slices: List<SpendCategory.Slice>,
    total: Double,
    onOpenEcard: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        // 标题 + 打开一卡通
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("校园卡", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = UbcmColors.Ink)
            Spacer(Modifier.weight(1f))
            Text(
                "打开一卡通 ›",
                fontSize = 13.sp,
                color = UbcmTheme.Brand500,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpenEcard).padding(horizontal = 4.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(8.dp))

        // 余额 + 今日消费
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("¥", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink)
                    Text(
                        st.balanceYuan ?: "—",
                        fontSize = 31.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = UbcmColors.Ink,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    )
                }
                Text("余额", fontSize = 11.sp, color = UbcmColors.Ash)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("今日消费", fontSize = 13.sp, color = UbcmColors.InkSoft)
                Spacer(Modifier.height(2.dp))
                Text(
                    "¥${st.todaySpendYuan ?: "—"}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = UbcmColors.Ink,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(UbcmColors.Line))
        Spacer(Modifier.height(12.dp))

        // 分段（近 7 / 近 30）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(RoundedCornerShape(999.dp)).background(UbcmTheme.Brand50).padding(3.dp)) {
                SegPill("近 7 天", days == 7) { onDays(7) }
                SegPill("近 30 天", days == 30) { onDays(30) }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "近 $days 天 ¥%.2f".format(total),
                fontSize = 12.sp,
                color = UbcmColors.InkSoft,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
            )
        }
        Spacer(Modifier.height(10.dp))

        // 折线
        if (st.spends.isEmpty() && !st.loading) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("暂无消费记录", fontSize = 12.sp, color = UbcmColors.Ash)
            }
        } else {
            // 纵轴量标 + 可点击选中的折线（P9 新增交互）
            var picked by remember(days) { mutableStateOf<Int?>(null) }
            val maxV = (daily.maxOfOrNull { it.second } ?: 0.0).coerceAtLeast(1.0)

            Row(Modifier.fillMaxWidth()) {
                // 纵轴：max / mid / 0
                Column(
                    Modifier.height(120.dp).width(34.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    Text("¥%.0f".format(maxV), fontSize = 9.sp, color = UbcmColors.Ash)
                    Text("¥%.0f".format(maxV / 2), fontSize = 9.sp, color = UbcmColors.Ash)
                    Text("¥0", fontSize = 9.sp, color = UbcmColors.Ash)
                }
                Spacer(Modifier.width(6.dp))
                SpendLineChart(
                    values = daily,
                    picked = picked,
                    onPick = { idx -> picked = if (picked == idx) null else idx },
                    modifier = Modifier.weight(1f).height(120.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                axisLabels(daily).forEach { i ->
                    val d = daily[i].first
                    Text("${d.monthValue}/${d.dayOfMonth}", fontSize = 10.sp, color = UbcmColors.Ash)
                }
            }
            // 选中那天的金额（点空白处取消）
            picked?.takeIf { it in daily.indices }?.let { i ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "${daily[i].first.monthValue}/${daily[i].first.dayOfMonth} 消费 ¥%.2f".format(daily[i].second),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = UbcmTheme.Brand700,
                    modifier = Modifier.padding(start = 40.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(UbcmColors.Line))
        Spacer(Modifier.height(12.dp))

        // 消费分布
        Text("消费分布", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = UbcmColors.Ink)
        Spacer(Modifier.height(10.dp))
        if (slices.isEmpty()) {
            Text("暂无消费记录", fontSize = 12.sp, color = UbcmColors.Ash)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DonutChart(
                    slices = slices,
                    centerLabel = if (days == 7) "7 天" else "30 天",
                )
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    slices.forEachIndexed { i, s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(8.dp).clip(RoundedCornerShape(2.dp))
                                    .background(donutColors()[i % donutColors().size])
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                s.label,
                                fontSize = 12.sp,
                                color = UbcmColors.Ink,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Text(
                                "${s.percent}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = UbcmColors.Ink,
                                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) UbcmTheme.Brand700 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) Color.White else UbcmColors.InkSoft,
        )
    }
}

/** X 轴刻度：7 天取 4 个、30 天取 5 个（docs/26 §2.2）。 */
private fun axisLabels(daily: List<Pair<LocalDate, Double>>): List<Int> {
    if (daily.isEmpty()) return emptyList()
    val n = daily.size
    return if (n <= 7) listOf(0, n / 3, n * 2 / 3, n - 1).distinct()
    else listOf(0, n / 4, n / 2, n * 3 / 4, n - 1).distinct()
}

@Composable
private fun SpendLineChart(
    values: List<Pair<LocalDate, Double>>,
    picked: Int?,
    onPick: (Int) -> Unit,
    modifier: Modifier,
) {
    val line = UbcmTheme.Brand500
    val top = UbcmTheme.Brand500.copy(alpha = 0.22f)
    val clear = UbcmTheme.Brand500.copy(alpha = 0.0f)
    val maxV = (values.maxOfOrNull { it.second } ?: 0.0).coerceAtLeast(1.0)

    Canvas(
        modifier.pointerInput(values) {
            detectTapGestures { pos ->
                if (values.size < 2) return@detectTapGestures
                val idx = ((pos.x / size.width) * (values.size - 1)).roundToInt()
                    .coerceIn(0, values.lastIndex)
                onPick(idx)
            }
        }
    ) {
        if (values.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val padTop = 8.dp.toPx()
        val usable = h - padTop - 2.dp.toPx()

        fun x(i: Int): Float = w * i / (values.size - 1).toFloat()
        fun y(v: Double): Float = padTop + usable * (1.0 - v / maxV).toFloat()

        val path = Path()
        values.forEachIndexed { i, (_, v) ->
            if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
        }

        val area = Path().apply {
            addPath(path)
            lineTo(x(values.lastIndex), h)
            lineTo(x(0), h)
            close()
        }
        drawPath(
            path = area,
            brush = Brush.verticalGradient(listOf(top, clear), startY = padTop, endY = h),
        )
        drawPath(
            path = path,
            color = line,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // 选中的那天：横向参考线 + 高亮点
        picked?.takeIf { it in values.indices }?.let { i ->
            val v = values[i].second
            val vy = y(v)
            drawLine(
                color = UbcmColors.Ash.copy(alpha = 0.7f),
                start = Offset(0f, vy),
                end = Offset(w, vy),
                strokeWidth = 1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    floatArrayOf(6f, 6f), 0f
                ),
            )
            drawCircle(line, radius = 4.dp.toPx(), center = Offset(x(i), vy))
            drawCircle(Color.White, radius = 1.8.dp.toPx(), center = Offset(x(i), vy))
        }

        values.forEachIndexed { i, (_, v) ->
            if (i != picked) drawCircle(line, radius = 2.5.dp.toPx(), center = Offset(x(i), y(v)))
        }
    }
}

/** 环形图配色：brand-700 → 500 → 300 → 100 + gold-500 一块 + line 一块（docs/26 §2.3）。 */
private fun donutColors(): List<Color> = listOf(
    UbcmTheme.Brand700,
    UbcmTheme.Brand500,
    UbcmTheme.Brand300,
    UbcmTheme.Brand100,
    UbcmTheme.Gold500,
    UbcmColors.Line,
    // Top6 + 其他最多 7 块；给第 7 块一个独立的中性色，避免颜色循环回第一个（真机踩过）
    UbcmColors.Ash.copy(alpha = 0.55f),
)

@Composable
private fun DonutChart(slices: List<SpendCategory.Slice>, centerLabel: String) {
    val colors = donutColors()
    val total = slices.sumOf { it.amountYuan }.coerceAtLeast(0.0001)
    Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val r = (size.minDimension - stroke) / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            var start = -90f
            slices.forEachIndexed { i, s ->
                val sweep = (s.amountYuan / total * 360.0).toFloat()
                drawArc(
                    color = colors[i % colors.size],
                    startAngle = start,
                    sweepAngle = (sweep - 1.2f).coerceAtLeast(0.6f),
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(r * 2, r * 2),
                )
                start += sweep
            }
        }
        Text(centerLabel, fontSize = 11.sp, color = UbcmColors.InkSoft)
    }
}

// ---------------- 服务宫格 ----------------

private data class ServiceItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun ServiceGrid(actions: CampusActions) {
    val items = listOf(
        ServiceItem("智慧后勤", Icons.Filled.Build, actions.openLogistics),
        ServiceItem("智慧教务", Icons.Filled.School, actions.openJw),
        ServiceItem("图书馆", Icons.Filled.MenuBook, actions.openLibrary),
        ServiceItem("一卡通", Icons.Filled.CreditCard, actions.openEcard),
        ServiceItem("空间预约", Icons.Filled.EventSeat, actions.openSpace),
        ServiceItem("数智北中医", Icons.Filled.Apps, actions.openPortal),
        ServiceItem("空教室", Icons.Filled.MeetingRoom, actions.openEmptyRoom),
        ServiceItem("更多", Icons.Filled.MoreHoriz, actions.openMore),
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text("服务", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = UbcmColors.Ink)
        Spacer(Modifier.height(14.dp))
        items.chunked(4).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth()) {
                row.forEach { item ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = item.onClick)
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(item.icon, contentDescription = item.label, tint = UbcmTheme.Brand700, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(item.label, fontSize = 11.sp, color = UbcmColors.InkSoft, maxLines = 1)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
