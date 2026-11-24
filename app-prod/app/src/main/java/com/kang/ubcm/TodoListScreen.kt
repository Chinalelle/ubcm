package com.kang.ubcm

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** 待办"≤24h"橙系浅底（与首页一致）。 */
private val URGENT_BG = Color(0xFFFBEDE1)
private val NEAR_BG = UbcmTheme.Gold100.copy(alpha = 0.5f)

private enum class TodoTab { OPEN, DONE }

/**
 * 待办完整页（docs/22）：列表 / 已完成分段 + 底部固定添加按钮 + ⋯ 菜单 + 编辑页（同页 push）。
 *
 * 完成动画四阶段（对勾 → 浅绿闪 2 次 → 收缩淡出 → 真正写入完成），
 * 动画期间卡片仍在列表里，写完才消失 —— 避免"点一下瞬间消失"的失重感。
 */
@Composable
fun TodoListScreen(onBack: () -> Unit, onChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var todos by remember { mutableStateOf(TodoStore.load(ctx)) }
    var tab by remember { mutableStateOf(TodoTab.OPEN) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var editorOpen by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<TodoItem?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClearDone by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    // 完成动画的三个阶段标记
    var checking by remember { mutableStateOf<Set<String>>(emptySet()) }
    var flashing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var collapsing by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowMillis = System.currentTimeMillis()
        }
    }

    if (editorOpen) {
        TodoEditorScreen(
            initial = editingItem,
            onCancel = {
                editorOpen = false
                editingItem = null
            },
            onSaved = {
                editorOpen = false
                editingItem = null
                todos = TodoStore.load(ctx)
                onChanged()
            },
        )
        return
    }

    val open = remember(todos, nowMillis) { TodoRules.sorted(todos.filterNot { it.done }, nowMillis) }
    val done = remember(todos) { TodoRules.completedSorted(todos) }
    val list = if (tab == TodoTab.OPEN) open else done

    fun startComplete(id: String) {
        if (id in checking) return
        scope.launch {
            checking = checking + id
            delay(320)                       // ① 对勾
            flashing = flashing + id
            delay(700)                       // ② 浅绿闪 2 次
            collapsing = collapsing + id
            delay(360)                       // ③ 收缩淡出
            todos = TodoStore.complete(ctx, id)  // ④ 真正完成
            checking = checking - id
            flashing = flashing - id
            collapsing = collapsing - id
            onChanged()
        }
    }

    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.fillMaxSize()) {
                // 顶栏
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "‹",
                        fontSize = 22.sp,
                        color = UbcmColors.Ink,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onBack).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("待办", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "⋯",
                        fontSize = 20.sp,
                        color = UbcmColors.Ink,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { menuOpen = true }.padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                }

                // 日期 + 分段
                Column(Modifier.padding(horizontal = 16.dp)) {
                    val date = Agenda.dateTimeOf(nowMillis).toLocalDate()
                    Text(
                        "${date.monthValue}月${date.dayOfMonth}日 周${"一二三四五六日"[date.dayOfWeek.value - 1]}",
                        fontSize = 13.sp,
                        color = UbcmColors.Ash,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.clip(RoundedCornerShape(999.dp)).background(UbcmColors.CardWhite).padding(3.dp)) {
                        SegTab("待办", tab == TodoTab.OPEN, Modifier.weight(1f)) { tab = TodoTab.OPEN }
                        SegTab("已完成", tab == TodoTab.DONE, Modifier.weight(1f)) { tab = TodoTab.DONE }
                    }
                }

                // 列表
                Box(Modifier.weight(1f)) {
                    if (list.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                if (tab == TodoTab.OPEN) "暂无待办" else "还没有完成的待办",
                                fontSize = 14.sp,
                                color = UbcmColors.Ash,
                            )
                            if (tab == TodoTab.OPEN) {
                                Spacer(Modifier.height(6.dp))
                                Text("点下方「＋ 添加待办」开始", fontSize = 12.sp, color = UbcmColors.Ash)
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(list.size) { i ->
                                val todo = list[i]
                                TodoRowItem(
                                    todo = todo,
                                    nowMillis = nowMillis,
                                    doneMode = tab == TodoTab.DONE,
                                    checking = todo.id in checking,
                                    flashing = todo.id in flashing,
                                    collapsing = todo.id in collapsing,
                                    onToggle = { startComplete(todo.id) },
                                    onRestore = {
                                        todos = TodoStore.restore(ctx, todo.id)
                                        onChanged()
                                    },
                                    onEdit = {
                                        editingItem = todo
                                        editorOpen = true
                                    },
                                    onToggleSub = { subId ->
                                        val next = todo.copy(
                                            subs = todo.subs.map {
                                                if (it.id == subId) it.copy(done = !it.done) else it
                                            }
                                        )
                                        // 全部子待办完成 = 父任务自动完成（docs/22 §6）
                                        val autoDone = TodoRules.allSubsDone(next)
                                        todos = TodoStore.upsert(
                                            ctx,
                                            if (autoDone) next.copy(done = true, completedAt = System.currentTimeMillis())
                                            else next,
                                        )
                                        onChanged()
                                    },
                                )
                            }
                        }
                    }
                }

                // 底部固定按钮
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(UbcmColors.Cinnabar)
                            .clickable {
                                editingItem = null
                                editorOpen = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("＋ 添加待办", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                }
            }

            // ⋯ 菜单
            if (menuOpen) {
                MenuSheet(
                    onClearDone = { menuOpen = false; confirmClearDone = true },
                    onClearAll = { menuOpen = false; confirmClearAll = true },
                    onAbout = { menuOpen = false; showAbout = true },
                    onClose = { menuOpen = false },
                )
            }
            if (showAbout) {
                ConfirmSheet(
                    title = "关于待办",
                    message = "待办数据仅存本机，不上传、不联网同步。",
                    confirmText = "知道了",
                    danger = false,
                    onConfirm = { showAbout = false },
                    onClose = { showAbout = false },
                )
            }
            if (confirmClearDone) {
                ConfirmSheet(
                    title = "清空已完成？",
                    message = "会删除所有已完成待办，不可恢复。",
                    confirmText = "清空",
                    danger = true,
                    onConfirm = {
                        todos = TodoStore.clearCompleted(ctx)
                        confirmClearDone = false
                        onChanged()
                    },
                    onClose = { confirmClearDone = false },
                )
            }
            if (confirmClearAll) {
                ConfirmSheet(
                    title = "清空全部待办？",
                    message = "待办和已完成都会被删除，不可恢复。",
                    confirmText = "全部清空",
                    danger = true,
                    onConfirm = {
                        todos = TodoStore.clearAll(ctx)
                        confirmClearAll = false
                        onChanged()
                    },
                    onClose = { confirmClearAll = false },
                )
            }
        }
    }
}

@Composable
private fun TodoRowItem(
    todo: TodoItem,
    nowMillis: Long,
    doneMode: Boolean,
    checking: Boolean,
    flashing: Boolean,
    collapsing: Boolean,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
    onEdit: () -> Unit,
    onToggleSub: (String) -> Unit,
) {
    // 放在独立 composable 里：否则会解析到外层 Column 的 ColumnScope.AnimatedVisibility
    androidx.compose.animation.AnimatedVisibility(
        visible = !collapsing,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(360)) +
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(360)),
    ) {
        TodoFullCard(
            todo = todo,
            nowMillis = nowMillis,
            doneMode = doneMode,
            checking = checking,
            flashing = flashing,
            onToggle = onToggle,
            onRestore = onRestore,
            onEdit = onEdit,
            onToggleSub = onToggleSub,
        )
    }
}

@Composable
private fun SegTab(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) UbcmColors.Cinnabar else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) Color.White else UbcmColors.InkSoft,
        )
    }
}

/**
 * 完整卡片：勾选框 + 标题 + 子待办进度条 + DDL；已完成模式灰化 + ↩ 撤销 + 完成时间。
 */
@Composable
private fun TodoFullCard(
    todo: TodoItem,
    nowMillis: Long,
    doneMode: Boolean,
    checking: Boolean,
    flashing: Boolean,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
    onEdit: () -> Unit,
    onToggleSub: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val level = TodoRules.levelOf(todo.dueAt, nowMillis)
    val cardBg = when {
        doneMode -> UbcmColors.CardWhite
        level == DueLevel.URGENT -> URGENT_BG
        level == DueLevel.NEAR -> NEAR_BG
        else -> UbcmColors.CardWhite
    }
    val borderColor = when {
        doneMode -> UbcmColors.Line
        level == DueLevel.URGENT -> UbcmTheme.Gold500.copy(alpha = .45f)
        level == DueLevel.NEAR -> UbcmTheme.Gold500.copy(alpha = .3f)
        else -> UbcmColors.Line
    }

    // 浅绿闪 2 次
    val flash = remember { Animatable(0f) }
    LaunchedEffect(flashing) {
        if (flashing) {
            repeat(2) {
                flash.animateTo(1f, tween(180))
                flash.animateTo(0f, tween(180))
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        if (flash.value > 0f) {
            Box(
                Modifier.matchParentSize()
                    .background(UbcmTheme.Success.copy(alpha = flash.value * 0.14f))
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TodoTickBox(
                done = todo.done || checking,
                animated = checking,
                onClick = { if (!doneMode) onToggle() },
            )
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    todo.title,
                    fontSize = 15.sp,
                    color = if (doneMode) UbcmColors.Ash else UbcmColors.Ink,
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (todo.subs.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    SubTodoBar(todo.subs, cardBg)
                }
                if (expanded) {
                    todo.note?.takeIf { it.isNotBlank() }?.let { note ->
                        Spacer(Modifier.height(8.dp))
                        Text(note, fontSize = 12.sp, color = UbcmColors.InkSoft, lineHeight = 18.sp)
                    }
                    if (todo.subs.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        todo.subs.forEach { sub ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onToggleSub(sub.id) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(14.dp).clip(RoundedCornerShape(4.dp))
                                        .background(if (sub.done) UbcmTheme.Success else Color.Transparent)
                                        .border(1.5.dp, if (sub.done) UbcmTheme.Success else UbcmColors.Ash, RoundedCornerShape(4.dp))
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    sub.text,
                                    fontSize = 12.sp,
                                    color = if (sub.done) UbcmColors.Ash else UbcmColors.Ink,
                                    textDecoration = if (sub.done) TextDecoration.LineThrough else TextDecoration.None,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(10.dp))
            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.End) {
                if (doneMode) {
                    Text(
                        "↩",
                        fontSize = 18.sp,
                        color = UbcmTheme.Brand500,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onRestore).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    todo.completedAt?.let {
                        val d = Instant.ofEpochMilli(it).atZone(Agenda.ZONE).toLocalDate()
                        Text("${d.monthValue}/${d.dayOfMonth} 完成", fontSize = 10.sp, color = UbcmColors.Ash)
                    }
                } else {
                    TodoRules.countdown(todo.dueAt, nowMillis)?.let {
                        Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Danger)
                    }
                    TodoRules.dueDayText(todo.dueAt)?.let {
                        Text(it, fontSize = 13.sp, color = if (level == DueLevel.URGENT) UbcmColors.Ink else UbcmColors.InkSoft)
                    }
                    TodoRules.dueTimeText(todo.dueAt)?.let {
                        Text(it, fontSize = 11.sp, color = UbcmColors.Ash)
                    }
                }
            }
        }
    }
}

/** 勾选框：完成动画里用 Canvas 画部分对勾（320ms）。 */
@Composable
private fun TodoTickBox(done: Boolean, animated: Boolean, onClick: () -> Unit) {
    val progress by animateFloatAsState(
        targetValue = if (done) 1f else 0f,
        animationSpec = tween(if (animated) 320 else 0),
        label = "tick",
    )
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (progress > 0f) UbcmTheme.Success else Color.Transparent)
            .border(2.dp, if (progress > 0f) UbcmTheme.Success else UbcmColors.Ash, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (progress > 0f) {
            Canvas(Modifier.size(14.dp)) {
                val w = size.width
                val h = size.height
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.15f, h * 0.55f)
                    lineTo(w * 0.42f, h * 0.8f)
                    lineTo(w * 0.85f, h * 0.25f)
                }
                val measure = androidx.compose.ui.graphics.PathMeasure()
                measure.setPath(path, false)
                val seg = androidx.compose.ui.graphics.Path()
                measure.getSegment(0f, measure.length * progress, seg, true)
                drawPath(seg, Color.White, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

/** 子待办进度条（与首页一致：纯蓝填充 + 节点处轨道断开）。 */
@Composable
private fun SubTodoBar(subs: List<SubTodo>, cardColor: Color) {
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
        drawLine(track, Offset(0f, cy), Offset(size.width, cy), lineW, StrokeCap.Round)
        val doneCount = subs.count { it.done }
        if (doneCount > 0) {
            drawLine(fill, Offset(0f, cy), Offset(seg * (doneCount - 1) + seg / 2f, cy), lineW, StrokeCap.Round)
        }
        for (i in 0 until n) {
            val x = seg * i + seg / 2f
            drawCircle(cardColor, radius = r + 1.dp.toPx(), center = Offset(x, cy))
            if (subs[i].done) {
                drawCircle(fill, radius = r, center = Offset(x, cy))
            } else {
                drawCircle(cardColor, radius = r, center = Offset(x, cy))
                drawCircle(ring, radius = r, center = Offset(x, cy), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

// ---------------- 底部弹层 ----------------

@Composable
private fun MenuSheet(
    onClearDone: () -> Unit,
    onClearAll: () -> Unit,
    onAbout: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)).clickable(onClick = onClose)) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(UbcmColors.CardWhite)
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding()
                .padding(vertical = 8.dp),
        ) {
            SheetItem("清空已完成") {
                onClearDone()
            }
            SheetItem("清空全部待办数据", danger = true) {
                onClearAll()
            }
            SheetItem("关于待办") {
                onAbout()
            }
            Box(Modifier.fillMaxWidth().height(8.dp).background(UbcmColors.Paper))
            SheetItem("取消") {
                onClose()
            }
        }
    }
}

@Composable
private fun ConfirmSheet(
    title: String,
    message: String,
    confirmText: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)).clickable(onClick = onClose)) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(UbcmColors.CardWhite)
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding()
                .padding(20.dp),
        ) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink)
            Spacer(Modifier.height(8.dp))
            Text(message, fontSize = 13.sp, color = UbcmColors.InkSoft)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                        .background(UbcmTheme.Brand50).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("取消", fontSize = 14.sp, color = UbcmColors.InkSoft)
                }
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (danger) UbcmColors.Danger else UbcmColors.Cinnabar)
                        .clickable(onClick = onConfirm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(confirmText, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun SheetItem(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, color = if (danger) UbcmColors.Danger else UbcmColors.Ink)
    }
}
