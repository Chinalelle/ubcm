package com.kang.ubcm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/**
 * 待办编辑页（docs/22 §5）：新建与编辑共用。
 *
 * DDL 是本页的重点：
 *  - 框内**留空 = 不设 DDL**
 *  - 点右侧时钟 → 底部滚轮（时/分双列，停止自动吸附，选中行 brand-700）
 *  - 完成回填 `YYYY-MM-DD HH:mm`
 *  - 手动输入支持 `YYYY-MM-DD HH:mm` / `MM-DD HH:mm`，非法时保存会标红（不静默吞掉）
 */
@Composable
fun TodoEditorScreen(
    initial: TodoItem?,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalContext.current
    val editing = initial != null

    var title by remember(initial) { mutableStateOf(initial?.title ?: "") }
    var note by remember(initial) { mutableStateOf(initial?.note ?: "") }
    var ddlText by remember(initial) { mutableStateOf(TodoRules.formatDdl(initial?.dueAt)) }
    var ddlError by remember { mutableStateOf(false) }
    var showWheel by remember { mutableStateOf(false) }

    // 子待办：id → 文本（勾选状态保存在 TodoItem.subs 里，保存时再合并）
    var subRows by remember(initial) {
        mutableStateOf<List<Pair<String, String>>>(
            initial?.subs?.map { it.id to it.text } ?: emptyList()
        )
    }

    val canSave = title.isNotBlank()

    fun save() {
        if (!canSave) return
        if (!TodoRules.isDdlTextValid(ddlText)) {
            ddlError = true
            return
        }
        val dueAt = TodoRules.parseDdl(ddlText)
        val subsNow = subRows
            .filter { it.second.isNotBlank() }
            .map { (id, text) ->
                // 保留原有勾选状态
                val old = initial?.subs?.firstOrNull { it.id == id }
                SubTodo(id = id, text = text.trim(), done = old?.done ?: false)
            }
        val item = if (initial != null) {
            initial.copy(
                title = title.trim(),
                note = note.trim().ifBlank { null },
                dueAt = dueAt,
                subs = subsNow,
            )
        } else {
            TodoItem(
                id = "u-" + System.currentTimeMillis(),
                title = title.trim(),
                note = note.trim().ifBlank { null },
                dueAt = dueAt,
                done = false,
                subs = subsNow,
                createdAt = System.currentTimeMillis(),
            )
        }
        TodoStore.upsert(ctx, item)
        onSaved()
    }

    // DDL 滚轮弹层先消费返回键
    BackHandler(enabled = showWheel) { showWheel = false }

    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .align(Alignment.TopCenter)
                    .widthIn(max = 640.dp)
                    // 键盘弹起时整体上移，正在编辑的行不会被 IME 盖住（P9 修复）
                    .imePadding(),
            ) {
                // 顶栏：取消 / 标题 / 保存
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "取消",
                        fontSize = 15.sp,
                        color = UbcmColors.InkSoft,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onCancel).padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (editing) "编辑待办" else "新建待办",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = UbcmColors.Ink,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "保存",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (canSave) UbcmTheme.Brand700 else UbcmColors.Ash,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = canSave) { save() }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }

                // 可滚动内容（字段 + 子待办）。子待办多了也不会把页面撑死
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                var focusedSubIndex by remember { mutableStateOf<Int?>(null) }
                LaunchedEffect(focusedSubIndex) {
                    val idx = focusedSubIndex ?: return@LaunchedEffect
                    // 子待办 item 从 1 开始（0 是内容/备注等前导项），多留一格保证整行可见
                    listState.animateScrollToItem((idx + SUB_ITEM_OFFSET).coerceAtLeast(0))
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        EditorField(
                            value = title,
                            onValueChange = { title = it },
                            placeholder = "待办内容（必填）",
                            minLines = 1,
                            maxLines = 6,
                        )
                    }
                    item {
                        EditorField(
                            value = note,
                            onValueChange = { note = it },
                            placeholder = "备注（选填）",
                            minLines = 2,
                            maxLines = 8,
                        )
                    }
                    item {
                        Column {
                            Text("DDL", fontSize = 13.sp, color = UbcmColors.InkSoft)
                            Spacer(Modifier.height(6.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(UbcmColors.CardWhite)
                                    .border(
                                        width = if (ddlError) 1.5.dp else 1.dp,
                                        color = if (ddlError) UbcmColors.Danger else UbcmColors.Line,
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.weight(1f)) {
                                    if (ddlText.isEmpty()) {
                                        Text("留空 = 不设 DDL", fontSize = 15.sp, color = UbcmColors.Ash)
                                    }
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = ddlText,
                                        onValueChange = { ddlText = it; ddlError = false },
                                        singleLine = true,
                                        textStyle = TextStyle(fontSize = 15.sp, color = UbcmColors.Ink),
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(UbcmColors.Cinnabar),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                    )
                                }
                                if (ddlText.isNotEmpty()) {
                                    Text(
                                        "×",
                                        fontSize = 16.sp,
                                        color = UbcmColors.Ash,
                                        modifier = Modifier.clip(CircleShape)
                                            .clickable { ddlText = ""; ddlError = false }
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                                Icon(
                                    Icons.Filled.Schedule,
                                    contentDescription = "设置 DDL",
                                    tint = UbcmTheme.Brand700,
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .clickable { showWheel = true }
                                        .padding(3.dp),
                                )
                            }
                            if (ddlError) {
                                Spacer(Modifier.height(4.dp))
                                Text("格式：2026-11-24 20:00 或 11-24 20:00", fontSize = 11.sp, color = UbcmColors.Danger)
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("子待办", fontSize = 13.sp, color = UbcmColors.InkSoft)
                        }
                    }
                    subRows.forEachIndexed { i, (id, text) ->
                        item(key = "sub-$id") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    EditorField(
                                        value = text,
                                        onValueChange = { v ->
                                            subRows = subRows.toMutableList().also { it[i] = id to v }
                                        },
                                        placeholder = "子待办内容",
                                        minLines = 1,
                                        maxLines = 1,
                                        singleLine = true,
                                        onFocus = { focusedSubIndex = i },
                                        onImeDone = {
                                            // 键盘"确认"= 新增下一条，不是换行
                                            subRows = subRows + ("s-" + System.currentTimeMillis() to "")
                                            focusedSubIndex = subRows.lastIndex
                                        },
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                                        .border(1.dp, UbcmColors.Line, RoundedCornerShape(10.dp))
                                        .clickable { subRows = subRows.filterNot { it.first == id } },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("×", fontSize = 16.sp, color = UbcmColors.Ash)
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(4.dp)) }
                }

                // 固定区：「＋ 添加子待办」始终可见（不随列表滚走）
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "＋ 添加子待办",
                        fontSize = 13.sp,
                        color = UbcmTheme.Brand500,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                subRows = subRows + ("s-" + System.currentTimeMillis() to "")
                                focusedSubIndex = subRows.lastIndex
                            }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
                // 底部再留一点，避免被导航条贴住
                Spacer(Modifier.height(8.dp).navigationBarsPadding())
            }

            if (showWheel) {
                DdlWheelSheet(
                    initialMillis = TodoRules.parseDdl(ddlText),
                    onCancel = { showWheel = false },
                    onDone = { millis ->
                        ddlText = TodoRules.formatDdl(millis)
                        ddlError = false
                        showWheel = false
                    },
                    onClear = {
                        ddlText = ""
                        ddlError = false
                        showWheel = false
                    },
                )
            }
        }
    }
}

/** 白色圆角输入框（内容/备注/子待办共用）。 */
@Composable
private fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    minLines: Int,
    maxLines: Int,
    singleLine: Boolean = false,
    onFocus: (() -> Unit)? = null,
    onImeDone: (() -> Unit)? = null,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, fontSize = 15.sp, color = UbcmColors.Ash)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(fontSize = 15.sp, color = UbcmColors.Ink, lineHeight = 22.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(UbcmColors.Cinnabar),
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            maxLines = if (singleLine) 1 else maxLines,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = if (onImeDone != null) androidx.compose.ui.text.input.ImeAction.Done
                else androidx.compose.ui.text.input.ImeAction.Default,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { onImeDone?.invoke() },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onFocus != null) {
                        Modifier.onFocusChanged { if (it.isFocused) onFocus() }
                    } else Modifier
                ),
        )
    }
}

// ---------------- DDL 滚轮 ----------------

private val HOURS = (0..23).map { "%02d".format(it) }

/** 子待办在 LazyColumn 里的起始下标（前面有 内容/备注/DDL 三个 item） */
private const val SUB_ITEM_OFFSET = 3
private val MINUTES = (0..59).map { "%02d".format(it) }

@Composable
private fun DdlWheelSheet(
    initialMillis: Long?,
    onCancel: () -> Unit,
    onDone: (Long) -> Unit,
    onClear: () -> Unit,
) {
    val base = Agenda.dateTimeOf(initialMillis ?: System.currentTimeMillis())
    var date by remember { mutableStateOf(base.toLocalDate()) }
    var hour by remember { mutableStateOf(base.hour) }
    var minute by remember { mutableStateOf(base.minute) }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)).clickable(onClick = onCancel),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(UbcmColors.CardWhite)
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            // 顶部
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("取消", fontSize = 14.sp, color = UbcmColors.InkSoft, modifier = Modifier.clickable(onClick = onCancel))
                Spacer(Modifier.weight(1f))
                Text("设置 DDL", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink)
                Spacer(Modifier.weight(1f))
                Text(
                    "完成",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = UbcmTheme.Brand700,
                    modifier = Modifier.clickable { onDone(TodoRules.wheelToMillis(date, hour, minute)) },
                )
            }

            // 日期行（左右切天）
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "‹",
                    fontSize = 16.sp,
                    color = UbcmTheme.Brand700,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .clickable { date = date.minusDays(1) }.padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Text(
                    "${date.year}-%02d-%02d".format(date.monthValue, date.dayOfMonth),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = UbcmColors.Ink,
                )
                Text(
                    "›",
                    fontSize = 16.sp,
                    color = UbcmTheme.Brand700,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .clickable { date = date.plusDays(1) }.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            // 时 : 分 双滚轮
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WheelColumn(HOURS, hour) { hour = it }
                Text(":", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink)
                Spacer(Modifier.width(12.dp))
                WheelColumn(MINUTES, minute) { minute = it }
            }

            // 清除
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(UbcmTheme.Brand50)
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Text("清除 DDL", fontSize = 14.sp, color = UbcmColors.Danger)
            }
        }
    }
}

/**
 * 单列滚轮：`LazyColumn` + 停止后自动吸附到中间项。
 *
 * 没引第三方滚轮库：40dp 行高 + 60dp 上下 padding 让目标项正好居中，
 * `firstVisibleItemScrollOffset` 过半就认为中心换到了下一项。
 */
@Composable
private fun WheelColumn(items: List<String>, initialIndex: Int, onSettled: (Int) -> Unit) {
    val itemH = 40.dp
    val density = LocalDensity.current
    val itemPx = with(density) { itemH.toPx() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex.coerceIn(0, items.lastIndex))
    val centerIndex by remember {
        derivedStateOf {
            (state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0)
                .coerceIn(0, items.lastIndex)
        }
    }

    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress) {
            // 停手后吸附：把中心项滚到 contentPadding 之后（正好居中）
            if (state.firstVisibleItemScrollOffset != 0) {
                state.animateScrollToItem(centerIndex)
            }
            onSettled(centerIndex)
        }
    }

    Box(Modifier.height(160.dp).width(72.dp), contentAlignment = Alignment.Center) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(items.size) { i ->
                Box(Modifier.height(itemH).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        items[i],
                        fontSize = if (i == centerIndex) 15.sp else 13.sp,
                        fontWeight = if (i == centerIndex) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (i == centerIndex) UbcmTheme.Brand700 else UbcmColors.Ash,
                    )
                }
            }
        }
        // 中间两条淡淡的选中线
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(40.dp)
                .border(1.dp, UbcmColors.Line.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
        )
    }
}
