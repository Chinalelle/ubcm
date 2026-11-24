package com.kang.ubcm

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 消费分布的**本地解析**（docs/26 §2.3）。
 *
 * 数据来自流水 `mername`（如"良乡东院食堂一层基本伙餐厅"），不依赖任何新接口。
 * 这是纯函数（不碰 Android / org.json），可 JVM 单测。
 */
object SpendCategory {

    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 一条消费（只留统计要用的三个字段）。 */
    data class Spend(val merchant: String, val amountYuan: Double, val timeMs: Long)

    /** 一个分类的合计。 */
    data class Slice(val label: String, val amountYuan: Double, val percent: Int)

    /**
     * 商户名 → 分类标签（"校区 + 场所"）。
     *
     * 解析顺序有讲究：**先判小卖部/超市，再判食堂**。
     * 真实数据里"西院食堂一层小卖部"这种名字，先判食堂会错分成食堂。
     */
    fun categorize(merchant: String): String {
        val m = clean(merchant)
        if (m.isEmpty()) return "其他"

        val campus = when {
            m.contains("东院") -> "东院"
            m.contains("西院") -> "西院"
            else -> ""
        }

        val place = when {
            m.contains("小卖部") -> "小卖部"
            m.contains("超市") -> "超市"
            m.contains("开水") -> "开水机"
            m.contains("咖啡") -> "咖啡"
            m.contains("打印") -> "打印"
            m.contains("洗衣") -> "洗衣"
            m.contains("食堂") && m.contains("二层") -> "食堂二层"
            m.contains("食堂") && m.contains("一层") -> "食堂一层"
            m.contains("食堂") && m.contains("三层") -> "食堂三层"
            m.contains("食堂") -> "食堂"
            else -> "其他"
        }

        return when {
            campus.isEmpty() && place == "其他" -> "其他"
            campus.isEmpty() -> place
            place == "其他" -> campus
            else -> campus + place
        }
    }

    private fun clean(raw: String): String {
        val t = raw.trim()
        return if (t == "null") "" else t
    }

    /** 消费金额（负数流水取绝对值；正数（充值）不计入消费）。 */
    fun spendYuan(amountYuan: String): Double =
        amountYuan.toDoubleOrNull()?.let { if (it < 0) -it else 0.0 } ?: 0.0

    /** 过滤出消费（负数）且落在 [sinceMs, nowMs] 的流水。 */
    fun spendsSince(spends: List<Spend>, sinceMs: Long, nowMs: Long = Long.MAX_VALUE): List<Spend> =
        spends.filter { it.amountYuan > 0 && it.timeMs >= sinceMs && it.timeMs <= nowMs }

    fun totalYuan(spends: List<Spend>, sinceMs: Long, nowMs: Long = Long.MAX_VALUE): Double =
        spendsSince(spends, sinceMs, nowMs).sumOf { it.amountYuan }

    /** 按天汇总（含没有消费的天，补 0），返回按日期升序。 */
    fun dailyTotals(spends: List<Spend>, days: Int, endDate: LocalDate): List<Pair<LocalDate, Double>> {
        val start = endDate.minusDays((days - 1).toLong())
        val buckets = LinkedHashMap<LocalDate, Double>()
        for (i in 0 until days) buckets[start.plusDays(i.toLong())] = 0.0
        spendsSince(
            spends,
            start.atStartOfDay(ZONE).toInstant().toEpochMilli(),
            endDate.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli() - 1,
        ).forEach { s ->
            val d = Instant.ofEpochMilli(s.timeMs).atZone(ZONE).toLocalDate()
            if (buckets.containsKey(d)) buckets[d] = (buckets[d] ?: 0.0) + s.amountYuan
        }
        return buckets.map { it.key to it.value }
    }

    /**
     * 分类汇总：按金额降序，Top [limit] + "其他"。
     * 百分比按四舍五入后的整数；合计不足 100 时由"其他"吸收（这里不做强制凑整，交给 UI 显示）。
     */
    fun topCategories(
        spends: List<Spend>,
        sinceMs: Long,
        limit: Int = 6,
        nowMs: Long = Long.MAX_VALUE,
    ): List<Slice> {
        val filtered = spendsSince(spends, sinceMs, nowMs)
        if (filtered.isEmpty()) return emptyList()
        val total = filtered.sumOf { it.amountYuan }
        if (total <= 0) return emptyList()

        val grouped = filtered.groupBy { categorize(it.merchant) }
            .mapValues { (_, v) -> v.sumOf { it.amountYuan } }

        // "其他"必须先摘出来：否则它既可能出现在 Top N 里，又被尾部合并再算一次，
        // 图例上就会出现两个"其他"（真机踩过）
        val otherAmount = grouped.filterKeys { it == "其他" }.values.sum()
        val named = grouped.filterKeys { it != "其他" }
            .toList()
            .sortedByDescending { it.second }

        val head = named.take(limit)
        val rest = named.drop(limit).sumOf { it.second } + otherAmount
        val rows = if (rest > 0) head + ("其他" to rest) else head

        return rows
            .map { (label, amount) ->
                Slice(
                    label = label,
                    amountYuan = amount,
                    percent = Math.round(amount / total * 100).toInt(),
                )
            }
            // 占比 0% 的行对用户没有信息量（除了"其他"）
            .filter { it.percent > 0 || it.label == "其他" }
    }
}
