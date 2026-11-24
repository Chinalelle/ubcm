package com.kang.ubcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周视图网格逻辑的测试。
 *
 * 夹具是 [TestTimetable] 里的**真实课表**（2026–2027 学年第 1 学期，岐黄班）。
 * 断言写的是「教务系统里真实存在的排课」，不是编出来的数字 ——
 * 这样一旦有人改坏了跨节/周次过滤，失败信息本身就能说明哪门课画错了。
 *
 * 交叉验证：本文件对第 11 周周五「2 门课」、第 6 周周五「含心理健康」、
 * 第 14 周周五「中医基础学只有第 1 节」的断言，与 [TimetableTest] 完全一致。
 * 两个文件从不同角度（「下一节课」 vs 「整周网格」）打同一份数据，能互相兜底。
 */
class WeekGridTest {

    private val sessions = TestTimetable.SESSIONS

    private fun grid(week: Int) = WeekGrid.build(sessions, week)

    private fun names(week: Int, day: Int) = grid(week).slotsOn(day).map { it.session.name }

    // ---------- 周次范围 ----------

    @Test
    fun `周次范围覆盖体育的1到17周`() {
        val r = WeekGrid.weekRange(sessions)
        assertNotNull(r)
        assertEquals(1, r!!.first)    // 体育Ⅰ 从第 1 周开始
        assertEquals(17, r.last)      // 方药学/思政/古汉语都排到第 17 周
    }

    @Test
    fun `空课表的周次范围是null`() {
        assertNull(WeekGrid.weekRange(emptyList()))
    }

    // ---------- 周末列 ----------

    @Test
    fun `岐黄班整学期周末无课所以只显示五天`() {
        // TestTimetable 里 dayOfWeek 最大是 5（周五），确实没有周六周日的课
        assertFalse(WeekGrid.hasWeekend(sessions))
        assertEquals(listOf(1, 2, 3, 4, 5), grid(11).days)
    }

    @Test
    fun `有周末课时显示七天`() {
        val withSat = sessions + CourseSession(
            name = "周末实验", teacher = "某老师", location = "实验室",
            dayOfWeek = 6, startPeriod = 1, endPeriod = 2, weeks = listOf(11),
        )
        assertTrue(WeekGrid.hasWeekend(withSat))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), WeekGrid.build(withSat, 11).days)
    }

    // ---------- 行数 ----------

    @Test
    fun `行数至少12节`() {
        // 真实课表最晚只到第 11 节，但仍要画满 12 行（完整作息）
        assertEquals(11, WeekGrid.maxPeriod(sessions))
        assertEquals(12, WeekGrid.periodCount(sessions))
        assertEquals(12, grid(11).periods)
    }

    @Test
    fun `有更晚的课时行数跟随`() {
        val late = sessions + CourseSession(
            name = "晚课", teacher = "", location = "",
            dayOfWeek = 2, startPeriod = 13, endPeriod = 14, weeks = listOf(11),
        )
        assertEquals(14, WeekGrid.periodCount(late))
        assertEquals(14, WeekGrid.build(late, 11).periods)
    }

    // ---------- 周次过滤（这一项错了整周都是错的） ----------

    @Test
    fun `第11周周一有三门课`() {
        // 中医基础学 1-3 节（4-15 周） / 思政 5-6 节（仅第 11 周） / 人工智能概论 9-11 节（4-14 周）
        assertEquals(listOf("中医基础学", "思想政治理论课Ⅰ", "人工智能概论"), names(11, 1))
    }

    @Test
    fun `第11周周二只有正常人体学和高等数学`() {
        // 大学英语Ⅰ-Ⅱ 周二场次只到第 10 周，第 11 周不该出现
        assertEquals(listOf("正常人体学Ⅰ", "高等数学"), names(11, 2))
    }

    @Test
    fun `第11周周三有三门课且方药学还没开始`() {
        assertEquals(listOf("古汉语基础", "思想政治理论课Ⅰ", "中医基础学"), names(11, 3))
        assertFalse("第 11 周方药学还没开课（13 周起）", names(11, 3).contains("方药学"))
    }

    @Test
    fun `第11周周四有三门课`() {
        assertEquals(listOf("正常人体学Ⅰ", "体育Ⅰ", "高等数学"), names(11, 4))
    }

    @Test
    fun `第11周周五有两门课且心理健康教育被过滤掉`() {
        assertEquals(listOf("中医基础学", "古汉语基础"), names(11, 5))
        assertFalse(names(11, 5).any { it.contains("心理健康") })
    }

    @Test
    fun `第6周周五出现心理健康教育`() {
        val n = names(6, 5)
        assertEquals(2, n.size)
        assertTrue(n.any { it.contains("心理健康") })
        assertFalse("古汉语周五场次第 8 周才开始", n.contains("古汉语基础"))
    }

    @Test
    fun `第10周周三没有思政课`() {
        // 真实教务数据里周三这场思政**缺第 10 周**（国庆调课）。
        // 夹具如果写成 4-17 连续，这里会多出一门课 —— 这就是"拿子集当真实数据"的隐患，
        // 2026-11-21 用平板实机缓存校准后才发现的。
        assertEquals(listOf("古汉语基础", "中医基础学"), names(10, 3))
    }

    @Test
    fun `人工智能概论拆成两段周次后仍只画一块`() {
        // 真实数据把 4-10,13,14 和 11,12 拆成两条。第 11 周只能出现一条，
        // 不能因为"两条 weeks 不重叠"就画成两块叠在一起。
        val ai = grid(11).slotsOn(1).filter { it.session.name == "人工智能概论" }
        assertEquals(1, ai.size)
        assertEquals(9, ai[0].startPeriod)
        assertEquals(11, ai[0].endPeriod)
    }

    @Test
    fun `第14周周五中医基础学只剩一节`() {
        val slots = grid(14).slotsOn(5)
        val zj = slots.first { it.session.name == "中医基础学" }
        assertEquals(1, zj.startPeriod)
        assertEquals(1, zj.endPeriod)
        assertEquals(1, zj.span)
        assertEquals("良东316教", zj.session.location)
    }

    @Test
    fun `第1周只有体育课`() {
        val all = (1..7).flatMap { grid(1).slotsOn(it) }
        assertEquals(listOf("体育Ⅰ"), all.map { it.session.name })
    }

    @Test
    fun `学期外的周次是空网格`() {
        assertTrue(grid(0).isEmpty)
        assertTrue(grid(99).isEmpty)
    }

    // ---------- 跨节：不能按「两节一组」分组 ----------

    @Test
    fun `周一的中医基础学是1到3节整整三节`() {
        val zj = grid(11).slotsOn(1).first { it.session.name == "中医基础学" }
        assertEquals(1, zj.startPeriod)
        assertEquals(3, zj.endPeriod)
        assertEquals(3, zj.span)     // 关键：1-3 节是一整块，不是 1-2 + 3
    }

    @Test
    fun `周二正常人体学是1到4节占四节`() {
        val rt = grid(11).slotsOn(2).first { it.session.name == "正常人体学Ⅰ" }
        assertEquals(1, rt.startPeriod)
        assertEquals(4, rt.endPeriod)
        assertEquals(4, rt.span)
    }

    @Test
    fun `周三中医基础学是5到7节跨午饭和下午`() {
        val zj = grid(11).slotsOn(3).first { it.session.name == "中医基础学" }
        assertEquals(5, zj.startPeriod)
        assertEquals(7, zj.endPeriod)
        assertEquals(3, zj.span)
    }

    @Test
    fun `单节课的span是1`() {
        // 大学英语Ⅰ-Ⅱ：第 4-9 周是周二 5-6 节，第 10 周改成只有第 5 节。
        // 同一门课在周次上"缩水"，是周视图最容易画错的一类数据。
        val w9 = WeekGrid.build(sessions, 9).slotsOn(2).first { it.session.name == "大学英语Ⅰ-Ⅱ" }
        assertEquals(5, w9.startPeriod)
        assertEquals(6, w9.endPeriod)
        assertEquals(2, w9.span)

        val w10 = WeekGrid.build(sessions, 10).slotsOn(2).first { it.session.name == "大学英语Ⅰ-Ⅱ" }
        assertEquals(5, w10.startPeriod)
        assertEquals(5, w10.endPeriod)
        assertEquals(1, w10.span)
    }

    // ---------- 重叠分栏 ----------

    @Test
    fun `不冲突的课各自占满整列`() {
        for (s in grid(11).slots) {
            assertEquals("${s.session.name} 在周${s.dayOfWeek} 第${s.startPeriod}节", 1, s.laneCount)
            assertEquals(0, s.lane)
        }
    }

    @Test
    fun `完全重叠的两门课并排各占一半`() {
        val a = CourseSession("甲课", "", "A", 2, 1, 2, listOf(11))
        val b = CourseSession("乙课", "", "B", 2, 1, 2, listOf(11))
        val g = WeekGrid.build(listOf(a, b), 11)
        val slots = g.slotsOn(2)
        assertEquals(2, slots.size)
        assertEquals(setOf(0, 1), slots.map { it.lane }.toSet())
        assertTrue(slots.all { it.laneCount == 2 })
    }

    @Test
    fun `部分重叠的课也并排`() {
        // 甲 1-3 节、乙 2-4 节：区间相交，必须分栏，否则会叠着画
        val a = CourseSession("甲课", "", "A", 3, 1, 3, listOf(11))
        val b = CourseSession("乙课", "", "B", 3, 2, 4, listOf(11))
        val slots = WeekGrid.build(listOf(a, b), 11).slotsOn(3)
        assertEquals(2, slots.size)
        assertTrue(slots.all { it.laneCount == 2 })
    }

    @Test
    fun `首尾相接不算冲突`() {
        // 甲 1-2 节、乙 3-4 节：中间隔开，不该分栏（这是最常见的情况，分错就整列变窄）
        val a = CourseSession("甲课", "", "A", 4, 1, 2, listOf(11))
        val b = CourseSession("乙课", "", "B", 4, 3, 4, listOf(11))
        val slots = WeekGrid.build(listOf(a, b), 11).slotsOn(4)
        assertEquals(2, slots.size)
        assertTrue("首尾相接应各占满宽", slots.all { it.laneCount == 1 && it.lane == 0 })
    }

    @Test
    fun `不同天的同一节次不算冲突`() {
        // 周一 1-3 和周三 5-7 当然不冲突；这里特意用"同节次不同天"
        val a = CourseSession("甲课", "", "A", 1, 1, 3, listOf(11))
        val b = CourseSession("乙课", "", "B", 3, 1, 3, listOf(11))
        val slots = WeekGrid.build(listOf(a, b), 11).slots
        assertTrue("跨天绝不能分栏（踩过的坑：座位聚类也不能跨区混算）", slots.all { it.laneCount == 1 })
    }

    @Test
    fun `三个连成一条的冲突块分成两栏就够`() {
        // 甲 1-2 / 乙 2-3 / 丙 3-4：任意相邻两两相交，但只需要 2 栏（1-2 与 3-4 可共用栏）
        val a = CourseSession("甲", "", "A", 2, 1, 2, listOf(11))
        val b = CourseSession("乙", "", "B", 2, 2, 3, listOf(11))
        val c = CourseSession("丙", "", "C", 2, 3, 4, listOf(11))
        val slots = WeekGrid.build(listOf(a, b, c), 11).slotsOn(2)
        assertEquals(3, slots.size)
        assertEquals(2, slots.maxOf { it.laneCount })
        // 甲和丙复用同一栏
        val jia = slots.first { it.session.name == "甲" }
        val bing = slots.first { it.session.name == "丙" }
        assertEquals(jia.lane, bing.lane)
    }

    @Test
    fun `同门课同周次两条不同地点会并排`() {
        // 真实课表里有这种情况的隐患：思政第 12-14 周 location 为空、第 15 周只到第 5 节。
        // 这里直接构造两条同周次冲突，确认不会被静默丢掉。
        val a = CourseSession("思想政治理论课Ⅰ", "邱仁富", "", 1, 5, 6, listOf(11))
        val b = CourseSession("思想政治理论课Ⅰ", "邱仁富", "良西302教", 1, 5, 6, listOf(11))
        val slots = WeekGrid.build(listOf(a, b), 11).slotsOn(1)
        assertEquals(2, slots.size)
        assertTrue(slots.all { it.laneCount == 2 })
    }

    // ---------- 几何换算 ----------

    @Test
    fun `第一节的顶部偏移是0`() {
        assertEquals(0f, WeekGeometry.topOf(1, 46f, 4f), 0.001f)
    }

    @Test
    fun `每节之间要留gap`() {
        assertEquals(50f, WeekGeometry.topOf(2, 46f, 4f), 0.001f)   // 一节 46 + gap 4
        assertEquals(100f, WeekGeometry.topOf(3, 46f, 4f), 0.001f)
        assertEquals(550f, WeekGeometry.topOf(12, 46f, 4f), 0.001f) // 11 * 50
    }

    @Test
    fun `单节课的高度就是一个行高`() {
        assertEquals(46f, WeekGeometry.heightOf(1, 1, 46f, 4f), 0.001f)
    }

    @Test
    fun `三节课的高度是三行加两个gap`() {
        // 这行断言就是「不能按两节一组分组」的数学表达
        assertEquals(146f, WeekGeometry.heightOf(1, 3, 46f, 4f), 0.001f)  // 3*46 + 2*4
    }

    @Test
    fun `四节课的高度是四行加三个gap`() {
        assertEquals(196f, WeekGeometry.heightOf(1, 4, 46f, 4f), 0.001f)  // 4*46 + 3*4
    }

    @Test
    fun `块的底部正好压在第end节那一行的底部`() {
        // 这是排版正确性的核心断言：跨节块的底边必须与末节格子的底边重合，
        // 否则会出现"块比格子高一点/矮一点"的错位。
        val row = 46f
        val gap = 4f
        for (start in 1..10) {
            for (end in start..12) {
                val top = WeekGeometry.topOf(start, row, gap)
                val h = WeekGeometry.heightOf(start, end, row, gap)
                val rowBottom = WeekGeometry.topOf(end, row, gap) + row
                assertEquals("第 $start-$end 节的底边", rowBottom, top + h, 0.001f)
            }
        }
    }

    @Test
    fun `整张表的高度`() {
        assertEquals(12 * 46f + 11 * 4f, WeekGeometry.totalHeight(12, 46f, 4f), 0.001f)
        assertEquals(0f, WeekGeometry.totalHeight(0, 46f, 4f), 0.001f)
    }

    @Test
    fun `非法节次不会算出负高度`() {
        // end < start 的脏数据（解析异常时可能出现）不能变成负高度，否则 Compose 会崩
        assertTrue(WeekGeometry.heightOf(5, 3, 46f, 4f) > 0f)
    }

    // ---------- 不崩 ----------

    @Test
    fun `空课表能建出空网格而不崩`() {
        val g = WeekGrid.build(emptyList(), 5)
        assertTrue(g.isEmpty)
        assertEquals(listOf(1, 2, 3, 4, 5), g.days)   // 没周末课 → 5 列
        assertEquals(12, g.periods)
    }

    @Test
    fun `脏数据的星期几会被丢掉`() {
        val bad = listOf(
            CourseSession("星期0", "", "", 0, 1, 2, listOf(11)),
            CourseSession("星期8", "", "", 8, 1, 2, listOf(11)),
            CourseSession("正常", "", "", 3, 1, 2, listOf(11)),
        )
        val g = WeekGrid.build(bad, 11)
        assertEquals(listOf("正常"), g.slots.map { it.session.name })
    }
}

/**
 * 周次压缩显示。看起来是「小工具函数」，但它错了用户就不知道自己哪几周要上课 ——
 * 而课表里恰好全是 [4..15] 这种长串和 [12,13,14] 这种断档。
 */
class CompressWeeksTest {

    @Test
    fun `连续周次压成区间`() {
        assertEquals("4–8", compressWeeks(listOf(4, 5, 6, 7, 8)))
    }

    @Test
    fun `断档用逗号分开`() {
        assertEquals("4–8, 10–11", compressWeeks(listOf(4, 5, 6, 7, 8, 10, 11)))
    }

    @Test
    fun `单条周次不带波浪线`() {
        assertEquals("11", compressWeeks(listOf(11)))
    }

    @Test
    fun `真实课表的4到15周`() {
        assertEquals("4–15", compressWeeks((4..15).toList()))
    }

    @Test
    fun `乱序和重复都能规整`() {
        assertEquals("4, 6, 8", compressWeeks(listOf(8, 4, 6, 4, 6)))
    }

    @Test
    fun `空周次显示破折号`() {
        assertEquals("—", compressWeeks(emptyList()))
    }

    @Test
    fun `0和负数被丢掉`() {
        assertEquals("1–2", compressWeeks(listOf(0, -1, 1, 2)))
    }

    @Test
    fun `两段各只有一条也要正确`() {
        assertEquals("6, 9", compressWeeks(listOf(9, 6)))
    }

    @Test
    fun `三段连续`() {
        assertEquals("1–2, 5–6, 9–10", compressWeeks(listOf(1, 2, 5, 6, 9, 10)))
    }
}

/**
 * 周次锚点校验。
 *
 * 这一项的价值全在「拦住静默错误」：锚点填成周二不会报任何异常，
 * 只会让整学期每门课都错一天 —— 用户过两周才发现，那时根本无法判断是哪天的错。
 */
class FirstWeekAnchorTest {

    @Test
    fun `周一是合法的锚点`() {
        assertNull(Timetable.validateFirstWeekMonday("2026-09-07"))   // 2026-09-07 确实是周一
    }

    @Test
    fun `周二会被拒绝并说明是周二`() {
        val err = Timetable.validateFirstWeekMonday("2026-09-08")
        assertNotNull(err)
        assertTrue("错误信息要说清是周二：$err", err!!.contains("周二"))
    }

    @Test
    fun `周日也会被拒绝`() {
        val err = Timetable.validateFirstWeekMonday("2026-09-13")
        assertNotNull(err)
        assertTrue(err!!.contains("周日"))
    }

    @Test
    fun `空值给格式提示`() {
        assertNotNull(Timetable.validateFirstWeekMonday(""))
        assertNotNull(Timetable.validateFirstWeekMonday(null))
        assertNotNull(Timetable.validateFirstWeekMonday("   "))
    }

    @Test
    fun `乱七八糟的字符串给格式提示而不是崩溃`() {
        val err = Timetable.validateFirstWeekMonday("2026/09/07")
        assertNotNull(err)
        assertTrue(err!!.contains("格式"))
    }

    @Test
    fun `前后空格能容忍`() {
        assertNull(Timetable.validateFirstWeekMonday("  2026-09-07  "))
    }

    @Test
    fun `真实校验规则与weekOf自洽`() {
        // 校验通过的锚点，用它算出来的第 1 周周一必须等于锚点本身
        val raw = "2026-09-07"
        assertNull(Timetable.validateFirstWeekMonday(raw))
        val d = Timetable.parseFirstWeekMonday(raw)!!
        assertEquals(1, Timetable.weekOf(d, d))
    }
}

/**
 * 字段清洗。
 *
 * 真机上「教室 null」赫然显示在课表上，根因是 org.json 的 optString 对 JSON null
 * 返回字面量 "null"。这不是脏数据防御 —— 教务返回的 jx0601mc 对思政第 12–15 周
 * **就是 null**（正常数据），所以必须清洗。
 */
class CleanFieldTextTest {

    @Test
    fun `字符串null归一成空串`() {
        assertEquals("", cleanFieldText("null"))
        assertEquals("", cleanFieldText("NULL"))
        assertEquals("", cleanFieldText(" null "))
    }

    @Test
    fun `undefined也归一`() {
        assertEquals("", cleanFieldText("undefined"))
    }

    @Test
    fun `正常值原样trim`() {
        assertEquals("良东408教", cleanFieldText(" 良东408教 "))
    }

    @Test
    fun `空与null指针都安全`() {
        assertEquals("", cleanFieldText(""))
        assertEquals("", cleanFieldText("   "))
        assertEquals("", cleanFieldText(null))
    }

    @Test
    fun `别把含null的正常词误杀`() {
        // "null" 必须是整串才清，不能 contains
        assertEquals("nullroom", cleanFieldText("nullroom"))
        assertEquals("nullless 教", cleanFieldText("nullless 教"))
    }
}
