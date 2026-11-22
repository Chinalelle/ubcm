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
    // 先分组再按比例重排：每张桌只保留「中心 + 朝向」来自真实坐标，
    // 桌内一律按 1 / 0.5 / 2 单位排（见 DeskGeometry 的说明）
    val placed = remember(seats) {
        DeskFinder.groupAll(seats).map { DeskGeometry.layout(it) }
    }
    val bounds = remember(placed) { DeskGeometry.boundsOf(placed) }

    // 初始放大到 1.8x：三层南区 x 跨度 92%（画布约 1080px），若按整图铺满，
    // 一个座位只有 9px，根本看不清空/占。放大后座位 ≈ 17px，靠拖动看全貌，
    // 双击回到 1.0 看整图（用户明确要求"像拖地图一样"）。
    var scale by remember(seats) { mutableFloatStateOf(1.8f) }
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
                // 拖动 / 缩放：**以两指中心为锚点**缩放，否则手指一捏图就往角上跑
                .pointerInput(placed) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val old = scale
                        val next = (scale * zoom).coerceIn(0.6f, 6f)
                        val f = next / old
                        // 让 centroid 处的内容在缩放前后落在同一点：
                        //   pan' = A*(1-f) + f*pan + pan.delta   （A = centroid - pad）
                        val ax = centroid.x - CANVAS_PAD
                        val ay = centroid.y - CANVAS_PAD
                        panX = ax * (1f - f) + f * panX + pan.x
                        panY = ay * (1f - f) + f * panY + pan.y
                        scale = next
                    }
                }
                // 点击命中座位
                .pointerInput(placed, scale, panX, panY) {
                    detectTapGestures(
                        onDoubleTap = { scale = 1f; panX = 0f; panY = 0f },   // 双击看全图
                        onTap = { tap ->
                            val hit = hitTest(
                                tap, placed, bounds,
                                size.width.toFloat(), size.height.toFloat(),
                                scale, panX, panY,
                            )
                            if (hit != null) onSeatClick(hit)
                        },
                    )
                }
        ) {
            val pad = CANVAS_PAD
            val baseScale = minOf(
                (size.width - pad * 2) / bounds.width,
                (size.height - pad * 2) / bounds.height,
            )
            val k = baseScale * scale
            fun sx(x: Float) = (x - bounds.left) * k + pad + panX
            fun sy(y: Float) = (y - bounds.top) * k + pad + panY

            val cornerPx = maxOf(1f, DeskGeometry.TABLE_CORNER * k)

            // 1) 桌子（实心棕，先画，压在座位下面）
            for (p in placed) {
                val t = p.table ?: continue
                drawRoundRect(
                    color = UBcmMapColors.table,
                    topLeft = Offset(sx(t.left), sy(t.top)),
                    size = Size(t.width * k, t.height * k),
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                )
            }

            // 2) 座位（实心方块，无描边）
            for (p in placed) {
                val deskFree = p.desk.allFreeDuring(criteria.slot)
                for (ps in p.seats) {
                    val b = ps.box
                    val free = SeatFilter.seatFreeAt(ps.seat, criteria)
                    drawRect(
                        color = if (free) UBcmMapColors.seatFree else UBcmMapColors.seatBusy,
                        topLeft = Offset(sx(b.left), sy(b.top)),
                        size = Size(b.width * k, b.height * k),
                    )
                    // 单人座可用：金框（整桌的在下面统一画）
                    if (free && p.desk.size == 1 && p.desk.nameRange !in highlighted) {
                        drawRoundRect(
                            color = UBcmMapColors.highlightEdge,
                            topLeft = Offset(sx(b.left - DeskGeometry.HIGHLIGHT_PAD), sy(b.top - DeskGeometry.HIGHLIGHT_PAD)),
                            size = Size((b.width + DeskGeometry.HIGHLIGHT_PAD * 2) * k, (b.height + DeskGeometry.HIGHLIGHT_PAD * 2) * k),
                            cornerRadius = CornerRadius(cornerPx, cornerPx),
                            style = Stroke(width = maxOf(1.5f, k * 0.1f)),
                        )
                    }
                    // 整桌空着的大桌：金框
                    if (free && p.desk.size > 1 && deskFree && p.desk.nameRange !in highlighted) {
                        // 只在第一圈画一次，靠下面的整桌循环处理
                    }
                }
            }

            // 3) 整桌可用（金框）/ 筛选命中（蓝框）：都框「桌子+全部座位」+ 0.25 单位
            for (p in placed) {
                val outer = p.outer ?: continue
                val isHit = p.desk.nameRange in highlighted
                val isFree = p.desk.allFreeDuring(criteria.slot)
                val tone = when {
                    isHit -> UBcmMapColors.selected            // 筛选命中：蓝
                    isFree -> UBcmMapColors.highlightEdge      // 整桌可用：金
                    else -> null
                } ?: continue
                val e = outer.expand(DeskGeometry.HIGHLIGHT_PAD)
                drawRoundRect(
                    color = tone,
                    topLeft = Offset(sx(e.left), sy(e.top)),
                    size = Size(e.width * k, e.height * k),
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                    style = Stroke(width = maxOf(1.5f, k * 0.11f)),
                )
            }
        }
    }
}

/** 点击命中：找最近的座位（半径放宽，手指点不准时也能选中） */
private fun hitTest(
    tap: Offset,
    placed: List<DeskGeometry.Placed>,
    bounds: DeskGeometry.Box,
    w: Float,
    h: Float,
    scale: Float,
    panX: Float,
    panY: Float,
): SeatApi.Seat? {
    val pad = CANVAS_PAD
    val base = minOf((w - pad * 2) / bounds.width, (h - pad * 2) / bounds.height)
    val k = base * scale
    var best: SeatApi.Seat? = null
    var bestD = Float.MAX_VALUE
    // 命中半径按座位大小放宽：手指点不准时也能选中最近的
    val limit = maxOf(DeskGeometry.SEAT_SIZE * k * 1.5f, 24f)
    for (p in placed) {
        for (ps in p.seats) {
            val px = (ps.box.centerX - bounds.left) * k + pad + panX
            val py = (ps.box.centerY - bounds.top) * k + pad + panY
            val dx = px - tap.x
            val dy = py - tap.y
            val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (dist < bestD) {
                bestD = dist
                best = ps.seat
            }
        }
    }
    return if (bestD <= limit) best else null
}

/** 画布内边距（px），手势的缩放锚点计算要和绘制用同一个值 */
private const val CANVAS_PAD = 8f

/** 座位图配色。刻意和主页的暖白风格区分开：这是"图纸"，用中性底色 */
private object UBcmMapColors {
    val paper = Color(0xFFF7F5F1)
    /** 桌子：深棕实心 */
    val table = Color(0xFF4F2D0D)
    /** 空闲座位：#2b9c1c */
    val seatFree = Color(0xFF2B9C1C)
    /** 有人座位：#c42727 */
    val seatBusy = Color(0xFFC42727)
    /** 可用高亮：金色边框（实心座位本身已经是绿的，框只做标示） */
    val highlightEdge = Color(0xFFD4A017)
    /** 筛选命中边框：#009dff */
    val selected = Color(0xFF009DFF)
}
