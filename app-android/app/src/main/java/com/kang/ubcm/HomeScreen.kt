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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 首页：一屏看完四件事 —— 下一节课倒计时、卡余额、今日消费、未读待办。
 * 这是每天打开 App 的理由，所以排版优先级最高的是「下一节课」。
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpenProbe: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenWeek: () -> Unit,
    onLogout: () -> Unit = {},
) {
    val st by vm.state.collectAsStateWithLifecycle()

    // 首次进入若有凭证就静默刷新一次（缓存已先渲染，不会白屏）
    LaunchedEffect(Unit) {
        val s = vm.state.value
        if (s.hasJwt || s.hasCastgc) vm.refresh(false)
    }

    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        // 平板/折叠屏适配：避开状态栏与手势条，并把内容限宽居中，
        // 否则卡片会被拉成一整条横贯 3408px 的横带（平板实测）。
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxHeight().widthIn(max = 640.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TopBar(
                    st = st,
                    onRefresh = { vm.refresh(true) },
                    onOpenProbe = onOpenProbe,
                    onOpenLogin = onOpenLogin,
                    onOpenWeek = onOpenWeek,
                )
            }

            item { NextCourseCard(st, onOpenWeek) }

            if (st.today.isNotEmpty()) {
                item { TodayCard(st, onOpenWeek) }
            }

            item { MoneyRow(st) }

            item { TodoCard(st, onOpenLogin) }

            item { LibraryEntryCard(onOpenLibrary) }

            if (!st.hasJwt) {
                item { LoginHintCard(st, onOpenLogin) }
            }

            item { FootNote(st) }
            item { HomeLogoutRow(onLogout) }
        }
        }
    }
}

// ---------------- 顶部 ----------------

@Composable
private fun TopBar(
    st: HomeState,
    onRefresh: () -> Unit,
    onOpenProbe: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenWeek: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("UBcm", fontSize = 26.sp, fontWeight = FontWeight.Black, color = UbcmColors.Ink, letterSpacing = 1.sp)
                Text(
                    dateLine(st),
                    fontSize = 12.sp,
                    color = UbcmColors.InkSoft,
                    letterSpacing = .4.sp,
                )
            }
            WeekChip(st.weekText)
            Spacer(Modifier.width(6.dp))
            // JWT 4 小时过期、CASTGC 长效，所以「门户在线」和「一卡通在线」是两件事
            val authLabel = when {
                st.refreshing -> "刷新中"
                st.hasJwt && st.hasCastgc -> "已登录"
                st.jwtExpired -> "门户过期"
                st.hasCastgc -> "一卡通在线"
                else -> "未登录"
            }
            val authTone = when {
                st.refreshing -> UbcmColors.Amber
                st.hasJwt && st.hasCastgc -> UbcmColors.Jade
                st.jwtExpired -> UbcmColors.Cinnabar
                st.hasCastgc -> UbcmColors.Amber
                else -> UbcmColors.Cinnabar
            }
            SmallPill(
                text = authLabel,
                tone = authTone,
                onClick = {
                    when {
                        st.refreshing -> Unit
                        st.hasJwt && st.hasCastgc -> onRefresh()
                        else -> onOpenLogin()
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LinkText("课表") { onOpenWeek() }
            LinkText("数据体检 / 登录") { onOpenProbe() }
            LinkText("刷新") { onRefresh() }
        }
    }
}

private fun dateLine(st: HomeState): String {
    val d = st.now.toLocalDate()
    val w = d.dayOfWeek.value
    val wName = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(w - 1) { "" }
    val md = "${d.monthValue} 月 ${d.dayOfMonth} 日"
    val clock = "%02d:%02d".format(st.now.hour, st.now.minute)
    return "$md  $wName  $clock"
}

@Composable
private fun WeekChip(text: String?) {
    if (text == null) return
    Box(
        Modifier.clip(RoundedCornerShape(7.dp))
            .background(UbcmColors.Ink.copy(alpha = .06f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.InkSoft)
    }
}

@Composable
private fun SmallPill(text: String, tone: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape)
            .background(tone.copy(alpha = .12f))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tone)
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(text, fontSize = 11.sp, color = UbcmColors.InkSoft)
    }
}

// ---------------- 下一节课（主卡） ----------------

@Composable
private fun NextCourseCard(st: HomeState, onOpenWeek: () -> Unit) {
    val next = st.next
    val hasTimetable = st.timetableFetchedAt > 0L

    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(18.dp))
            // 点主卡进整周课表：这是首页最自然的一个入口
            .clickable { onOpenWeek() },
    ) {
        val accent = when {
            next == null -> UbcmColors.Ash
            next.phase == CoursePhase.ONGOING -> UbcmColors.Jade
            else -> UbcmColors.Cinnabar
        }
        Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        next == null && !hasTimetable -> "还没有课表"
                        next == null -> "近期没有课"
                        next.phase == CoursePhase.ONGOING -> "正在上课"
                        else -> "下一节课"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    letterSpacing = 1.4.sp,
                )
                Spacer(Modifier.weight(1f))
                Text("整周课表 ›", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.InkSoft)
            }
            Spacer(Modifier.height(6.dp))

            if (next == null) {
                Text(
                    if (!hasTimetable) "登录教务后自动拉取课表" else "课表里这个时间段没有排课",
                    fontSize = 13.sp,
                    color = UbcmColors.InkSoft,
                )
                return@Column
            }

            // 倒计时大字
            Text(
                bigCountdown(st, next),
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                color = UbcmColors.Ink,
                letterSpacing = .5.sp,
            )
            Spacer(Modifier.height(10.dp))

            Text(
                next.time.session.name,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = UbcmColors.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                buildString {
                    append(dayLabel(next.time.date, st.now.toLocalDate()))
                    append("  ")
                    append(periodLabel(next.time.session))
                    append("  ")
                    append(clock(next.time.start, next.time.end))
                },
                fontSize = 12.sp,
                color = UbcmColors.InkSoft,
            )
            if (next.time.session.location.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "${next.time.session.location}${if (next.time.session.teacher.isNotBlank()) "  ·  ${next.time.session.teacher}" else ""}",
                    fontSize = 12.sp,
                    color = UbcmColors.InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 主卡那句大字：UPSIDE = 「还有 8 小时 24 分」，ONGOING = 「还剩 45 分钟」 */
private fun bigCountdown(st: HomeState, next: NextCourse): String {
    val mins = if (next.phase == CoursePhase.ONGOING) next.time.minutesUntilEnd(st.now)
    else next.time.minutesUntilStart(st.now)
    if (mins <= 0) return if (next.phase == CoursePhase.ONGOING) "即将下课" else "即将开始"
    return humanMinutes(mins)
}

fun humanMinutes(m: Long): String {
    val d = m / (60 * 24)
    val h = (m % (60 * 24)) / 60
    val mi = m % 60
    return when {
        d > 0 -> "${d} 天 ${h} 小时"
        h > 0 -> "${h} 小时 ${mi} 分"
        else -> "${mi} 分钟"
    }
}

fun dayLabel(date: LocalDate, today: LocalDate): String {
    val diff = ChronoUnit.DAYS.between(today, date)
    if (diff == 0L) return "今天"
    if (diff == 1L) return "明天"
    if (diff == 2L) return "后天"
    val w = date.dayOfWeek.value
    return listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(w - 1) { "" }
}

fun periodLabel(s: CourseSession): String =
    if (s.startPeriod == s.endPeriod) "第 ${s.startPeriod} 节"
    else "第 ${s.startPeriod}–${s.endPeriod} 节"

fun clock(start: java.time.LocalDateTime, end: java.time.LocalDateTime): String =
    "%02d:%02d–%02d:%02d".format(start.hour, start.minute, end.hour, end.minute)

// ---------------- 今天的课 ----------------

@Composable
private fun TodayCard(st: HomeState, onOpenWeek: () -> Unit) {
    CardShell(modifier = Modifier.clickable { onOpenWeek() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("今天", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = UbcmColors.Cinnabar, letterSpacing = 1.4.sp)
            Spacer(Modifier.width(8.dp))
            Text("${st.today.size} 节", fontSize = 11.sp, color = UbcmColors.Ash)
        }
        Spacer(Modifier.height(10.dp))
        st.today.forEachIndexed { i, c ->
            if (i > 0) {
                Spacer(Modifier.height(9.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(UbcmColors.Line.copy(alpha = .6f)))
                Spacer(Modifier.height(9.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tone = when (c.phase) {
                    CoursePhase.ENDED -> UbcmColors.Ash
                    CoursePhase.ONGOING -> UbcmColors.Jade
                    CoursePhase.UPCOMING -> UbcmColors.Ink
                }
                Box(Modifier.size(6.dp).clip(CircleShape).background(tone))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        c.time.session.name,
                        fontSize = 14.sp,
                        fontWeight = if (c.phase == CoursePhase.ENDED) FontWeight.Normal else FontWeight.SemiBold,
                        color = tone,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${periodLabel(c.time.session)}  ${clock(c.time.start, c.time.end)}" +
                            if (c.time.session.location.isNotBlank()) "  ${c.time.session.location}" else "",
                        fontSize = 11.sp,
                        color = UbcmColors.InkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (c.phase == CoursePhase.ONGOING) {
                    SmallPill("上课中", UbcmColors.Jade) {}
                }
            }
        }
    }
}

// ---------------- 钱 ----------------

@Composable
private fun MoneyRow(st: HomeState) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) {
            MoneyCard(
                label = "一卡通余额",
                value = st.balanceYuan?.let { "￥$it" },
                sub = if (st.cardError != null) st.cardError else null,
                tone = if (st.balanceYuan != null) UbcmColors.Ink else UbcmColors.Ash,
            )
        }
        Box(Modifier.weight(1f)) {
            MoneyCard(
                label = "今日消费",
                value = st.todaySpendYuan?.let { if (it == "0.00") "￥0" else "￥$it" },
                sub = if (st.todaySpendCount > 0) "${st.todaySpendCount} 笔" else null,
                tone = if (st.todaySpendYuan != null) UbcmColors.Cinnabar else UbcmColors.Ash,
            )
        }
    }
}

@Composable
private fun MoneyCard(label: String, value: String?, sub: String?, tone: androidx.compose.ui.graphics.Color) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Text(label, fontSize = 11.sp, color = UbcmColors.InkSoft)
        Spacer(Modifier.height(7.dp))
        Text(
            value ?: "—",
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            color = if (value != null) tone else UbcmColors.Ash,
        )
        if (sub != null) {
            Spacer(Modifier.height(3.dp))
            Text(sub, fontSize = 10.sp, color = UbcmColors.Ash, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------- 待办 ----------------

@Composable
private fun TodoCard(st: HomeState, onOpenLogin: () -> Unit) {
    val needLogin = st.jwtExpired || st.todoError != null
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp))
            .clickable(enabled = needLogin) { if (needLogin) onOpenLogin() }
            .padding(15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("未读待办", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = UbcmColors.Cinnabar, letterSpacing = 1.4.sp)
            Spacer(Modifier.weight(1f))
            if (needLogin) {
                Text("续登 ›", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Cinnabar)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                st.todoUnread?.toString() ?: "—",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                color = if (st.todoUnread != null && st.todoUnread > 0) UbcmColors.Cinnabar else UbcmColors.Ink,
            )
            Spacer(Modifier.width(5.dp))
            when {
                st.todoTotal != null -> Text(
                    "条 / 共 ${st.todoTotal}",
                    fontSize = 11.sp, color = UbcmColors.Ash,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                st.todoError != null -> Text(
                    st.todoError,
                    fontSize = 11.sp, color = UbcmColors.Ash,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                else -> Text(
                    "条待处理",
                    fontSize = 11.sp, color = UbcmColors.Ash,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        st.todoLatest?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, fontSize = 11.sp, color = UbcmColors.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------- 收尾 ----------------

@Composable
private fun LibraryEntryCard(onOpen: () -> Unit) {
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
            Text("图书馆", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = UbcmColors.Ink)
            Text("各层空位 · 违约记录 · 预约入口", fontSize = 11.sp, color = UbcmColors.InkSoft)
        }
        Text("查看 ›", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Cinnabar)
    }
}

@Composable
private fun LoginHintCard(st: HomeState, onOpenLogin: () -> Unit) {
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
            Text(
                if (st.jwtExpired) "门户登录已过期" else "登录后解锁",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = UbcmColors.Ink,
            )
            Text(
                if (st.jwtExpired) "JWT 只有 4 小时，点这里续登（课表/余额靠 CASTGC 仍可用）"
                else "课表 · 余额 · 今日消费 · 待办",
                fontSize = 11.sp, color = UbcmColors.InkSoft,
            )
        }
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(UbcmColors.Cinnabar).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(if (st.jwtExpired) "续登" else "登录", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White)
        }
    }
}

@Composable
private fun FootNote(st: HomeState) {
    val age = if (st.timetableFetchedAt > 0L) {
        val mins = (System.currentTimeMillis() - st.timetableFetchedAt) / 60000
        when {
            mins < 1 -> "刚刚更新"
            mins < 60 -> "$mins 分钟前更新"
            else -> "${mins / 60} 小时前更新"
        }
    } else "尚未拉取课表"
    Text(
        "课表 $age${if (st.timetableIsStale) "（已过期，建议刷新）" else ""}\n数据只在本机 · 不代登录 · 不存密码",
        fontSize = 10.sp,
        color = UbcmColors.Ash,
        lineHeight = 15.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun CardShell(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp))
            .padding(15.dp),
        content = content,
    )
}

/**
 * 首页底部的退出登录。和体检页同一个交互：**点两次确认**（不弹窗）。
 * 误触会清空全部凭证、要重新输密码，值得挡一下。
 */
@Composable
private fun HomeLogoutRow(onLogout: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (!confirming) {
            Text(
                "退出登录",
                fontSize = 11.sp,
                color = UbcmColors.Cinnabar,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { confirming = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("清空所有凭证？", fontSize = 11.sp, color = UbcmColors.InkSoft)
                Spacer(Modifier.width(6.dp))
                Text(
                    "确认退出",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(UbcmColors.Cinnabar)
                        .clickable { onLogout() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "取消",
                    fontSize = 11.sp,
                    color = UbcmColors.Ash,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { confirming = false }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}
