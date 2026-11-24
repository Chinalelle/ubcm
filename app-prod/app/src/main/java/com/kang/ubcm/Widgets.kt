package com.kang.ubcm

/** 小组件规格（docs/21 §4.7）。1×1 / 2×1（横排）/ 2×2。 */
enum class WidgetSize(val cols: Int, val rows: Int) {
    S1x1(1, 1),
    S2x1(2, 1),
    S2x2(2, 2),
}

/** 可放上首页的组件类型。size 是该类型的固定规格，用户不能改。 */
enum class WidgetType(val label: String, val shortLabel: String, val size: WidgetSize) {
    BALANCE("校园卡余额", "余额", WidgetSize.S2x2),
    LIBRARY("图书馆", "图书馆", WidgetSize.S1x1),
    EMPTY_ROOM("空教室", "空教室", WidgetSize.S1x1),
    LOGISTICS("智慧后勤", "后勤", WidgetSize.S1x1),
    JW("智慧教务", "教务", WidgetSize.S1x1),
    ECARD("一卡通", "一卡通", WidgetSize.S1x1),
    GRADES("成绩查询", "成绩", WidgetSize.S1x1),
    EXAM("考试安排", "考试", WidgetSize.S1x1),
    NOTICE("通知公告", "通知", WidgetSize.S1x1),
    TODO("待办", "待办", WidgetSize.S1x1),
    ;

    companion object {
        fun ofOrDefault(raw: String?): WidgetType? =
            entries.firstOrNull { it.name == raw }
    }
}

/**
 * 一个小组件在 4×2 网格里的位置。
 *
 * 坐标从 1 开始（docs/21 §4.7），(col,row) 是**左上角单元**；
 * 实际占用 [col, col+size.cols) × [row, row+size.rows)。
 */
data class HomeWidget(
    val id: String,
    val type: WidgetType,
    val col: Int,
    val row: Int,
) {
    val size: WidgetSize get() = type.size
    fun cellKeys(): List<Int> {
        // 注意：buildList 的 receiver 是 MutableList，`size` 会被解析成列表长度，
        // 所以这里必须写 `type.size`（踩过）
        val sz = type.size
        return buildList {
            for (r in row until row + sz.rows) {
                for (c in col until col + sz.cols) add(WidgetLayout.cellKey(c, r))
            }
        }
    }
}

/**
 * 4×2 网格的纯布局算法（不碰 Android，可 JVM 单测）。
 *
 * 三条规则：
 * 1. 任何组件不许越界（4 列 / 2 行）；
 * 2. 任何组件不许重叠；
 * 3. 拖拽落点被占时**交换**（同尺寸直接换；不同尺寸只有对方能放进我原来的位置才换）。
 */
object WidgetLayout {
    const val COLS = 4
    const val ROWS = 2

    fun cellKey(col: Int, row: Int): Int = (row - 1) * COLS + col

    /** 默认布局（docs/21 §4.2）：左 2×2 余额 + 右侧 4 个 1×1。 */
    fun defaultLayout(): List<HomeWidget> = listOf(
        HomeWidget("w_balance", WidgetType.BALANCE, col = 1, row = 1),
        HomeWidget("w_library", WidgetType.LIBRARY, col = 3, row = 1),
        HomeWidget("w_empty_room", WidgetType.EMPTY_ROOM, col = 4, row = 1),
        HomeWidget("w_logistics", WidgetType.LOGISTICS, col = 3, row = 2),
        HomeWidget("w_jw", WidgetType.JW, col = 4, row = 2),
    )

    fun fits(w: HomeWidget): Boolean =
        w.col >= 1 && w.row >= 1 &&
            w.col + w.size.cols - 1 <= COLS &&
            w.row + w.size.rows - 1 <= ROWS

    /** 已占用的格（cellKey 集合）。 */
    fun occupied(list: List<HomeWidget>): Set<Int> =
        list.flatMap { it.cellKeys() }.toSet()

    /** 空位（cellKey 集合），编辑态用来画虚线占位框。 */
    fun freeCells(list: List<HomeWidget>): Set<Int> {
        val used = occupied(list)
        return (1..COLS * ROWS).filterNot { used.contains(it) }.toSet()
    }

    fun overlaps(a: HomeWidget, b: HomeWidget): Boolean =
        a !== b && a.id != b.id && a.cellKeys().any { b.cellKeys().contains(it) }

    /** 能否放下（越界 / 撞别的组件都算放不下）。[ignoreId] 用于"移动自己"时忽略自身。 */
    fun canPlace(list: List<HomeWidget>, w: HomeWidget, ignoreId: String? = null): Boolean {
        if (!fits(w)) return false
        return list.none { it.id != ignoreId && overlaps(w, it) }
    }

    /**
     * 移动 / 交换。返回**新列表**（不做原地修改，方便单测与状态回滚）。
     *
     * - 落点空 → 直接移动
     * - 落点被 1 个组件占住 → 交换（对方到我原来的位置；放不下就整单放弃）
     * - 落点被多个组件占住 → 不动（拖到缝隙里不该把布局搞乱）
     */
    fun moveOrSwap(list: List<HomeWidget>, id: String, col: Int, row: Int): List<HomeWidget> {
        val me = list.firstOrNull { it.id == id } ?: return list
        val target = me.copy(col = col, row = row)
        if (!fits(target)) return list

        val blockers = list.filter { it.id != id && overlaps(target, it) }
        if (blockers.isEmpty()) {
            return list.map { if (it.id == id) target else it }
        }
        if (blockers.size > 1) return list

        val other = blockers.first()
        // 对方要挪到我的原位置，必须放得下且不撞第三方
        val otherMoved = other.copy(col = me.col, row = me.row)
        val rest = list.filter { it.id != id && it.id != other.id }
        if (!fits(otherMoved) || rest.any { overlaps(otherMoved, it) }) return list

        return list.map {
            when (it.id) {
                id -> target
                other.id -> otherMoved
                else -> it
            }
        }
    }

    /** 删除组件。 */
    fun remove(list: List<HomeWidget>, id: String): List<HomeWidget> =
        list.filterNot { it.id == id }

    /**
     * 添加组件：按行优先找第一个放得下的位置。
     * 返回 null = 网格没地方了。
     */
    fun add(list: List<HomeWidget>, type: WidgetType): List<HomeWidget>? {
        if (list.any { it.type == type }) return null // 同类不重复添加
        for (r in 1..ROWS) {
            for (c in 1..COLS) {
                val w = HomeWidget("w_${type.name.lowercase()}", type, c, r)
                if (canPlace(list, w)) return list + w
            }
        }
        return null
    }

    /** 布局是否合法（持久化数据被手改坏时的兜底）。 */
    fun isValid(list: List<HomeWidget>): Boolean {
        if (list.isEmpty()) return true
        val seen = mutableSetOf<Int>()
        for (w in list) {
            if (!fits(w)) return false
            for (k in w.cellKeys()) if (!seen.add(k)) return false
        }
        return true
    }
}
