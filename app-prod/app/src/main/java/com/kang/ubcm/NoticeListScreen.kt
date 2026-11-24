package com.kang.ubcm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 通知列表页（P8 新增）。
 *
 * 数据全部来自 `PortalApi.campusInfo` 的真实返回（标题/来源/时间）；
 * 拉取失败时**只显示"获取失败 · 重试"，绝不显示占位或假标题**。
 */
@Composable
fun NoticeListScreen(
    items: List<NoticeItem>,
    error: String?,
    loading: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    var detail by remember { mutableStateOf<NoticeItem?>(null) }

    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.fillMaxSize().align(Alignment.TopCenter).widthIn(max = 640.dp)) {
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
                    Text("通知", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (loading) "刷新中…" else "刷新",
                        fontSize = 13.sp,
                        color = if (loading) UbcmColors.Ash else UbcmTheme.Brand500,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !loading, onClick = onRetry)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }

                when {
                    loading && items.isEmpty() -> CenterHint("正在获取通知…")
                    error != null && items.isEmpty() -> CenterHint("通知获取失败 · 重试", onClick = onRetry)
                    items.isEmpty() -> CenterHint("暂时没有新通知")
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(items.size) { i ->
                            val n = items[i]
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(UbcmColors.CardWhite)
                                    .border(1.dp, UbcmColors.Line, RoundedCornerShape(12.dp))
                                    .clickable { detail = n }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    n.title,
                                    fontSize = 15.sp,
                                    color = UbcmColors.Ink,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 21.sp,
                                )
                                if (n.source.isNotBlank() || n.time.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        listOf(n.source, prettyNoticeTime(n.time))
                                            .filter { it.isNotBlank() }
                                            .joinToString(" · "),
                                        fontSize = 11.sp,
                                        color = UbcmColors.Ash,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            detail?.let { n ->
                NoticeDetail(n = n, onClose = { detail = null })
            }
        }
    }
}

@Composable
private fun CenterHint(text: String, onClick: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            fontSize = 14.sp,
            color = if (onClick != null) UbcmTheme.Brand500 else UbcmColors.Ash,
            modifier = if (onClick != null) {
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            } else Modifier,
        )
    }
}

/** 通知详情：校园通知在门户里没有稳定可拼的原文链接，这里只展示已拿到的字段。 */
@Composable
private fun NoticeDetail(n: NoticeItem, onClose: () -> Unit) {
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
            Text(n.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink, lineHeight = 24.sp)
            Spacer(Modifier.height(10.dp))
            if (n.source.isNotBlank()) {
                Text("来源：${n.source}", fontSize = 12.sp, color = UbcmColors.InkSoft)
                Spacer(Modifier.height(4.dp))
            }
            if (n.time.isNotBlank()) {
                Text("时间：${prettyNoticeTime(n.time)}", fontSize = 12.sp, color = UbcmColors.InkSoft)
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "完整正文请到门户「消息」里查看（本应用只读列表）",
                fontSize = 11.sp,
                color = UbcmColors.Ash,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(UbcmColors.Cinnabar).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text("关闭", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }
    }
}

/**
 * 门户返回的时间是 ISO 串（`2026-11-24T08:38:28.000+0000`，UTC），
 * 直接显示又长又容易误读时区 —— 统一转成北京时间 `MM-dd HH:mm`。
 */
private fun prettyNoticeTime(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty() || t == "null") return ""
    return try {
        val fmt = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]Z")
        val odt = java.time.OffsetDateTime.parse(t, fmt)
        odt.atZoneSameInstant(Agenda.ZONE)
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    } catch (e: Exception) {
        // 兜底：已经是短格式就不动；否则原样返回
        t.replace("T", " ").take(16)
    }
}
