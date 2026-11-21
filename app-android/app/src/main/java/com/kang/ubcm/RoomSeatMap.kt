package com.kang.ubcm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 区域座位图（按官方布局还原）。
 *
 * 渲染规则（对齐学校页面 + 用户指定）：
 * - **桌子**：棕色实心圆角矩形（[DeskGeometry] 从座位坐标反推）
 * - **座位**：实心方形
 *   - 该时段空闲 → 绿边框 + 浅绿填充
 *   - 该时段被占 → 红边框 + 淡红填充
 * - **可用高亮**：空闲单座 / 整桌全空的大桌 → 金色边框 + 浅蓝透明填充
 * - **筛选命中**：整张桌套一个空心深蓝圆角矩形
 *
 * 交互：
 * - 单指拖动平移、双指缩放（手机上区域图按原始比例铺不下，必须能拖）
 * - 点座位回调 [onSeatClick]
 *
 * 绘制坐标全部用**百分比坐标**（接口原样），再乘一个缩放系数映射到画布，
 * 这样不同区域的图能用同一套代码，不会因为区域大小不同而变形。
 */
@Composable
fun RoomSeatMap(
    seats: List<SeatApi.Seat>,
    criteria: SeatFilter.Criteria,
    /** 筛选命中的桌（用 nameRange 作 key） */
    highlighted: Set<String>,
    modifier: Modifier = Modifier,
    /** 固定高度；传 null 表示填满父容器（全屏页用） */
    heightDp: Int? = 420,
    onSeatClick: (SeatApi.Seat) -> Unit = {},
) {
    // 分组：这里要画**所有**座位，所以用 groupAll（findFreeDesks 只会返回全空的桌）
    val desks = remember(seats) { DeskFinder.groupAll(seats) }
    val bounds = remember(seats) { DeskGeometry.boundsOf(seats) }

    var scale by remember(seats) { mutableFloatStateOf(1f) }
    var panX by remember(seats) { mutableFloatStateOf(0f) }
    var panY by remember(seats) { mutableFloatStateOf(0f) }

    // 嵌在列表里时用固定高度；全屏页用 fillMaxSize（否则 420dp 撑不满，双指缩放也没空间）
    val sizeMod = if (heightDp != null) Modifier.fillMaxWidth().height(heightDp.dp) else Modifier.fillMaxSize()

    if (seats.isEmpty() || bounds == null) {
        Box(modifier.then(sizeMod).clip(RoundedCornerShape(14.dp)).background(UBcmMapColors.paper))
        return
    }

    Box(
        modifier
            .then(sizeMod)
            .clip(RoundedCornerShape(14.dp))
            .background(UBcmMapColors.paper)
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                // 拖动/缩放
                .pointerInput(seats) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.6f, 6f)
                        panX += pan.x
                        panY += pan.y
                    }
                }
                // 点击命中座位
                .pointerInput(seats, desks, scale, panX, panY) {
                    detectTapGestures(
                        // 双击重置视图：拖丢之后不用重新加载区域
                        onDoubleTap = { scale = 1f; panX = 0f; panY = 0f },
                        onTap = { tap ->
                        val hit = hitTest(
                            tap, desks, bounds,
                            size.width.toFloat(), size.height.toFloat(),
                            scale, panX, panY,
                        )
                        if (hit != null) onSeatClick(hit)
                        }
                    )
                }
        ) {
            // 画布 → 百分比坐标的换算：等比铺满并留一点边
            val pad = 8f
            val baseScale = minOf(
                (size.width - pad * 2) / bounds.width,
                (size.height - pad * 2) / bounds.height,
            )
            val k = baseScale * scale
            fun sx(x: Float) = (x - bounds.left) * k + pad + panX
            fun sy(y: Float) = (y - bounds.top) * k + pad + panY

            // 1) 桌子（先画，压在座位下面）
            for (d in desks) {
                val box = DeskGeometry.boxOf(d).table ?: continue
                drawRoundRect(
                    color = UBcmMapColors.table,
                    topLeft = Offset(sx(box.left), sy(box.top)),
                    size = Size(box.width * k, box.height * k),
                    cornerRadius = CornerRadius(
                        x = maxOf(3f, box.width * k * 0.18f),
                        y = maxOf(3f, box.height * k * 0.18f),
                    ),
                )
            }

            // 2) 座位
            for (d in desks) {
                val deskFree = d.allFreeDuring(criteria.slot)
                for (s in d.seats) {
                    val b = DeskGeometry.seatBox(s)
                    val free = SeatFilter.seatFreeAt(s, criteria)
                    val fill = if (free) UBcmMapColors.seatFreeFill else UBcmMapColors.seatBusyFill
                    val edge = if (free) UBcmMapColors.seatFreeEdge else UBcmMapColors.seatBusyEdge
                    val left = sx(b.left)
                    val top = sy(b.top)
                    val w = b.width * k
                    val h = b.height * k
                    drawRect(fill, Offset(left, top), Size(w, h))
                    drawRect(edge, Offset(left, top), Size(w, h), style = Stroke(width = maxOf(1f, k * 0.12f)))

                    // 3) 单人座可用高亮：金框 + 浅蓝填充（大桌在桌面那一层统一高亮）
                    if (free && d.size == 1) {
                        val grow = k * 0.3f
                        drawRoundRect(
                            color = UBcmMapColors.highlightFill,
                            topLeft = Offset(left - grow, top - grow),
                            size = Size(w + grow * 2, h + grow * 2),
                            cornerRadius = CornerRadius(4f, 4f),
                        )
                        drawRoundRect(
                            color = UBcmMapColors.highlightEdge,
                            topLeft = Offset(left - grow, top - grow),
                            size = Size(w + grow * 2, h + grow * 2),
                            cornerRadius = CornerRadius(4f, 4f),
                            style = Stroke(width = maxOf(1.5f, k * 0.16f)),
                        )
                    }
                }
            }

            // 3.5) 整桌全空的大桌：金框 + 浅蓝填充，**圈住桌子 + 全部座位**
            //      （只圈桌面的话座位露在框外，看着不像"这张桌"）
            val smallCorner = CornerRadius(x = 3f, y = 3f)
            for (d in desks) {
                if (d.size <= 1) continue
                if (!d.allFreeDuring(criteria.slot)) continue
                val outer = DeskGeometry.outerBoxOf(d) ?: continue
                val grow = k * 0.12f
                val tl = Offset(sx(outer.left) - grow, sy(outer.top) - grow)
                val ts = Size(outer.width * k + grow * 2, outer.height * k + grow * 2)
                drawRoundRect(UBcmMapColors.highlightFill, tl, ts, smallCorner)
                drawRoundRect(
                    color = UBcmMapColors.highlightEdge,
                    topLeft = tl, size = ts, cornerRadius = smallCorner,
                    style = Stroke(width = maxOf(1.8f, k * 0.16f)),
                )
            }

            // 4) 筛选命中的整张桌：空心深蓝框
            for (d in desks) {
                if (d.nameRange !in highlighted) continue
                // 圈「桌子 + 全部座位」，圆角要小（大圆角会把矩形的直角吃掉，看着像胶囊）
                val outer = DeskGeometry.outerBoxOf(d) ?: continue
                // 明显外扩：金色是「这张桌可用」、深蓝是「筛选命中」，两层要能一眼分开
                val grow = if (d.size == 1) k * 1.1f else k * 1.8f
                drawRoundRect(
                    color = UBcmMapColors.selected,
                    topLeft = Offset(sx(outer.left) - grow, sy(outer.top) - grow),
                    size = Size(outer.width * k + grow * 2, outer.height * k + grow * 2),
                    cornerRadius = CornerRadius(x = 3f, y = 3f),
                    style = Stroke(width = maxOf(2f, k * 0.2f)),
                )
            }
        }
    }
}

/** 点击命中：找最近的座位（半径放宽，手指点不准时也能选中） */
private fun hitTest(
    tap: Offset,
    desks: List<DeskFinder.Desk>,
    bounds: DeskGeometry.Box,
    w: Float,
    h: Float,
    scale: Float,
    panX: Float,
    panY: Float,
): SeatApi.Seat? {
    val pad = 8f
    val base = minOf((w - pad * 2) / bounds.width, (h - pad * 2) / bounds.height)
    val k = base * scale
    var best: SeatApi.Seat? = null
    var bestD = Float.MAX_VALUE
    val limit = maxOf(DeskGeometry.SEAT_SIZE * k * 1.6f, 26f)
    for (d in desks) {
        for (s in d.seats) {
            val px = (s.x.toFloat() - bounds.left) * k + pad + panX
            val py = (s.y.toFloat() - bounds.top) * k + pad + panY
            val dx = px - tap.x
            val dy = py - tap.y
            val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (dist < bestD) {
                bestD = dist
                best = s
            }
        }
    }
    return if (bestD <= limit) best else null
}

/** 座位图配色。刻意和主页的暖白风格区分开：这是"图纸"，用中性底色 */
private object UBcmMapColors {
    val paper = Color(0xFFF7F5F1)
    /** 桌子：棕色实心 */
    val table = Color(0xFF9A6B3F)
    /** 空闲座位：绿边 + 浅绿填充 */
    val seatFreeEdge = Color(0xFF2E7D32)
    val seatFreeFill = Color(0xFFA5D6A7)
    /** 被占座位：红边 + 淡红填充 */
    val seatBusyEdge = Color(0xFFC62828)
    val seatBusyFill = Color(0xFFEF9A9A)
    /** 可用高亮：金色边框 + 浅蓝透明填充 */
    val highlightEdge = Color(0xFFD4A017)
    val highlightFill = Color(0x2A00B0FF)
    /** 筛选命中：空心深蓝 */
    val selected = Color(0xFF1565C0)
}
