package com.kang.ubcm

/**
 * 座位图的三维筛选：**时段** × **桌子类型** × **是否整桌全空**。
 *
 * 纯逻辑（不碰 Android / Compose），因为「筛出来的是不是用户想要的那张桌」
 * 全靠这一层，错了 UI 做得再漂亮也没用。
 *
 * 类型按座位数分档，刻意**覆盖全部**而不是只认 4/6：
 * 真实数据里二层北区就有 10 座桌、三层南区有 13 座长桌、五层南区还有 2 座和 7 座，
 * 如果只做「四人桌 / 六人桌」两档，其余桌子会在筛选时凭空消失（用户会以为没座位）。
 */
object SeatFilter {

    enum class DeskType(val label: String) {
        ALL("全部"),
        SINGLE("单人座"),
        FOUR("四人桌"),
        SIX("六人桌"),
        LONG("长桌"),
        ;

        /** 档位对应的座位数区间 */
        fun accepts(size: Int): Boolean = when (this) {
            ALL -> true
            SINGLE -> size == 1
            FOUR -> size in 2..4
            SIX -> size in 5..6
            LONG -> size >= 7
        }

        /** 给人看的区间说明 */
        fun rangeText(): String = when (this) {
            ALL -> "不限"
            SINGLE -> "1 座"
            FOUR -> "2–4 座"
            SIX -> "5–6 座"
            LONG -> "7 座以上"
        }
    }

    data class Criteria(
        /** 使用时段（当天分钟数） */
        val startMin: Int,
        val endMin: Int,
        val type: DeskType = DeskType.ALL,
        /** 只显示「整个时段整桌都空」的桌 */
        val onlyFullyFree: Boolean = true,
    ) {
        val slot: DeskFinder.TimeSlot
            get() = DeskFinder.TimeSlot(startMin, endMin)

        val valid: Boolean get() = slot.valid
    }

    /** 这张桌是否满足全部条件 */
    fun matches(desk: DeskFinder.Desk, c: Criteria): Boolean {
        if (!c.type.accepts(desk.size)) return false
        return if (!c.onlyFullyFree) {
            // 不看「全空」时，只要时段内还有空位就算
            desk.seats.any { it.freeDuring(c.startMin, c.endMin) }
        } else {
            desk.allFreeDuring(c.slot)
        }
    }

    /** 筛出符合条件的桌，按座位数降序 */
    fun apply(desks: List<DeskFinder.Desk>, c: Criteria): List<DeskFinder.Desk> =
        desks.filter { matches(it, c) }.sortedWith(
            compareByDescending<DeskFinder.Desk> { it.size }.thenBy { it.nameRange }
        )

    /**
     * 座位在指定时段是否「空闲」。
     *
     * 注意：`Seat.free` 是「当天完全没人约」，而这里要的是「**这个时段**没人约」——
     * 两者不同：座位可能上午被约了、下午空着。
     */
    fun seatFreeAt(seat: SeatApi.Seat, c: Criteria): Boolean =
        seat.freeDuring(c.startMin, c.endMin)
}
