package com.kang.ubcm

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消费分布解析单测（docs/26 §2.3）：东院/西院、食堂楼层、超市/小卖部、开水/咖啡/打印。
 */
class SpendCategoryTest {

    @Test
    fun `东院食堂一层`() {
        assertEquals("东院食堂一层", SpendCategory.categorize("良乡东院食堂一层基本伙餐厅"))
    }

    @Test
    fun `西院食堂二层`() {
        assertEquals("西院食堂二层", SpendCategory.categorize("良乡西院食堂二层风味餐厅"))
    }

    @Test
    fun `西院超市`() {
        assertEquals("西院超市", SpendCategory.categorize("良乡西院超市"))
    }

    @Test
    fun `小卖部优先于食堂`() {
        // 「西院食堂一层小卖部」先判食堂就会错分
        assertEquals("西院小卖部", SpendCategory.categorize("良乡西院食堂一层小卖部"))
    }

    @Test
    fun `开水机与咖啡与打印`() {
        assertEquals("开水机", SpendCategory.categorize("良乡校区开水机"))
        assertEquals("东院咖啡", SpendCategory.categorize("良乡东院咖啡厅"))
        assertEquals("打印", SpendCategory.categorize("文印中心打印"))
    }

    @Test
    fun `没有校区前缀时只保留场所`() {
        assertEquals("超市", SpendCategory.categorize("校园超市"))
        assertEquals("其他", SpendCategory.categorize("未知商户"))
        assertEquals("其他", SpendCategory.categorize(""))
        assertEquals("其他", SpendCategory.categorize("null"))
    }

    @Test
    fun `负数流水取绝对值 正数充值不算消费`() {
        assertEquals(12.5, SpendCategory.spendYuan("-12.50"), 0.001)
        assertEquals(0.0, SpendCategory.spendYuan("100.00"), 0.001)
    }

    @Test
    fun `分类汇总按金额降序且 Top6 后并成其他`() {
        val now = 1_800_000_000_000L
        val day = 24 * 3600 * 1000L
        val spends = listOf(
            SpendCategory.Spend("良乡东院食堂一层基本伙餐厅", 38.0, now - day),
            SpendCategory.Spend("良乡西院食堂一层基本伙餐厅", 26.0, now - day),
            SpendCategory.Spend("良乡西院超市", 18.0, now - day),
            SpendCategory.Spend("良乡校区开水机", 9.0, now - day),
            SpendCategory.Spend("良乡西院小卖部", 6.0, now - day),
            SpendCategory.Spend("打印", 2.0, now - day),
            SpendCategory.Spend("洗衣", 1.0, now - day),
        )
        val slices = SpendCategory.topCategories(spends, now - 7 * day, limit = 5, nowMs = now)
        assertEquals(6, slices.size)                 // Top5 + 其他
        assertEquals("东院食堂一层", slices[0].label)
        assertEquals("其他", slices.last().label)
        assertEquals(3.0, slices.last().amountYuan, 0.001)
        assertTrue(slices[0].percent >= slices[1].percent)
    }

    @Test
    fun `按天汇总补零且只统计消费`() {
        val end = LocalDate.of(2026, 11, 24)
        val dayMs = 24 * 3600 * 1000L
        val noon = end.atTime(12, 0).atZone(SpendCategory.ZONE).toInstant().toEpochMilli()
        val spends = listOf(
            SpendCategory.Spend("良乡西院超市", 5.0, noon),
            SpendCategory.Spend("一卡通充值", -50.0, noon),   // 负数被 spendYuan 转正，但这里直接给正数表示消费
        )
        // 直接构造的 Spend 已经是"消费额为正"，充值不该以正数混进来；这里只验证 7 天都有桶
        val daily = SpendCategory.dailyTotals(spends, 7, end)
        assertEquals(7, daily.size)
        assertEquals(end, daily.last().first)
        assertEquals(5.0, daily.last().second, 0.001)
        assertEquals(0.0, daily.first().second, 0.001)
    }
}
