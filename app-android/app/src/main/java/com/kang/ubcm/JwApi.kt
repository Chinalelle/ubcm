package com.kang.ubcm

import org.json.JSONArray
import org.json.JSONObject

/**
 * 教务系统（强智 QZ）—— 返回 HTML，不是 JSON。
 * 登录态是 JSESSIONID（在 jw.bucm.edu.cn 域），与门户的 JWT 是两套。
 */
object JwApi {

    data class CourseRow(
        val name: String,
        val location: String,
        val credit: String,
        val attribute: String,
        val nature: String,
        val teacher: String,
        val timeRaw: String,
        val weeks: List<Int>,
        val dayOfWeek: Int,
        val periods: List<Int>,
    )

    /** 课表页（HTML 是空壳，数据由 JS 拉 JSON 填充） */
    fun courseTableHtml(jsessionCookie: String): Net.Resp =
        Net.get("${Net.JW_BASE}/jsxsd/xskb/bzyXskbList", cookie = jsessionCookie)

    /**
     * ★ 课表真身：POST /jsxsd/xskb/loadSkzccx 返回 JSON（不是 HTML）。
     * 之前按 HTML 表格解析拿不到数据，就是因为数据是前端渲染的。
     * zc 为空 = 全部周次。
     */
    fun courseTableJson(jsessionCookie: String, week: String = ""): Net.Resp =
        Net.postForm("${Net.JW_BASE}/jsxsd/xskb/loadSkzccx", "zc=$week", jsessionCookie)

    /** 节次时间表（第 1-6 大节的起止时间） */
    data class Period(val index: Int, val start: String, val end: String, val from: Int, val to: Int)

    fun parsePeriods(json: String): List<Period> = try {
        val arr = JSONObject(json).optJSONArray("lstJx0415zb") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Period(
                index = o.optString("xh").toIntOrNull() ?: return@mapNotNull null,
                start = o.optString("kssj"),
                end = o.optString("jssj"),
                from = o.optString("jc1").toIntOrNull() ?: 0,
                to = o.optString("jc2").toIntOrNull() ?: 0,
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * 解析 lstKb。关键字段：
     * kcmc 课名 | jg0101mc 教师 | jx0601mc 教室 | jzwmc 建筑
     * kkzc 周次("13-17") | kkzcmx 周次明细(",13,14,15,")
     * kcsj 时间编码("30102"=周三1-2节) | ktmc 班级
     */
    fun parseCoursesJson(json: String): List<CourseRow> = try {
        val arr = JSONObject(json).optJSONArray("lstKb") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val kcsj = o.optString("kcsj")
            if (kcsj.isBlank()) return@mapNotNull null
            val day = kcsj.take(1).toIntOrNull() ?: 0
            val periods = ArrayList<Int>()
            var p = 1
            while (p + 1 < kcsj.length) {
                kcsj.substring(p, p + 2).toIntOrNull()?.let { periods.add(it) }
                p += 2
            }
            val weeks = o.optString("kkzcmx").split(",").mapNotNull { it.trim().toIntOrNull() }
            CourseRow(
                name = o.optString("kcmc"),
                location = o.optString("jx0601mc"),
                credit = "",
                attribute = o.optString("jzwmc"),
                nature = o.optString("ktmc"),
                teacher = o.optString("jg0101mc"),
                timeRaw = o.optString("kkzc"),
                weeks = weeks,
                dayOfWeek = day,
                periods = periods,
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** 成绩查询壳页（GET 只有壳，数据要 POST cjcx_list） */
    fun scorePage(jsessionCookie: String): Net.Resp =
        Net.get("${Net.JW_BASE}/jsxsd/kscj/cjcx_frm", cookie = jsessionCookie)

    /** 成绩列表：表单 POST，GET 只会拿到空壳 */
    fun scoreList(jsessionCookie: String, term: String = ""): Net.Resp =
        Net.postForm(
            "${Net.JW_BASE}/jsxsd/kscj/cjcx_list",
            "kksj=$term&kcxz=&kcmc=&xsfs=all",
            jsessionCookie,
        )

    /**
     * 解析成绩表。实测表头 17 列：
     * 序号 | 开课学期 | 课程名称 | 最终成绩 | 原始成绩 | 总学时 | 学分 | 绩点 | 课程属性 | 考试性质 | 成绩标识 | 补重学期 | ...
     * 无数据时只有一行「未查询到数据」。
     */
    fun parseScores(html: String): List<List<String>> {
        val t = tables(html).firstOrNull { (it.firstOrNull()?.size ?: 0) >= 10 } ?: return emptyList()
        if (t.firstOrNull()?.firstOrNull() != "序号") return emptyList()
        return t.drop(1).filter { it.size >= 12 && it[0] != "未查询到数据" && it[0].toIntOrNull() != null }
    }

    /** 考试安排壳页 */
    fun examPage(jsessionCookie: String): Net.Resp =
        Net.get("${Net.JW_BASE}/jsxsd/xsks/xsksap_query", cookie = jsessionCookie)

    /** 首页（用来判断登录态是否有效） */
    fun home(jsessionCookie: String): Net.Resp =
        Net.get("${Net.JW_BASE}/jsxsd/framework/xsMain_bzy.html", cookie = jsessionCookie)

    fun isLoggedIn(html: String): Boolean =
        html.contains("xsMain") || html.contains("欢迎您") || html.contains("我的桌面") ||
            html.contains("个人课表信息")

    /**
     * 判定是不是"被踢回登录页"。
     * 注意：所有业务页底部都有「湖南强智科技发展有限公司」版权，不能拿它当标志，
     * 否则正常页面会被误判成登录页（首版就踩了这个坑，课表直接显示"未登录"）。
     */
    fun isLoginPage(html: String): Boolean {
        val title = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim().orEmpty()
        return title == "登录" ||
            html.contains("请先登录系统") ||
            html.contains("userLogin") ||
            (html.contains("用户登录") && !html.contains("个人课表"))
    }

    // ---------- HTML 解析 ----------

    /** 取所有 table，按行数降序 */
    fun tables(html: String): List<List<List<String>>> {
        val out = ArrayList<List<List<String>>>()
        val tableRe = Regex("""<table[^>]*>(.*?)</table>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (m in tableRe.findAll(html)) {
            val rows = ArrayList<List<String>>()
            val rowRe = Regex("""<tr[^>]*>(.*?)</tr>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            for (r in rowRe.findAll(m.groupValues[1])) {
                val cells = ArrayList<String>()
                val cellRe = Regex("""<t[dh][^>]*>(.*?)</t[dh]>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                for (c in cellRe.findAll(r.groupValues[1])) {
                    cells.add(strip(c.groupValues[1]))
                }
                if (cells.isNotEmpty()) rows.add(cells)
            }
            if (rows.isNotEmpty()) out.add(rows)
        }
        return out.sortedByDescending { it.size }
    }

    private fun strip(s: String): String =
        s.replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /**
     * 解析课表详情表。列序（实测 10 列）：
     * 课程名 | 地点 | 学分 | 属性 | 课程性质 | 教师 | 时间 | 内部ID | 课程编号 | 课程群
     */
    fun parseCourses(html: String): List<CourseRow> {
        val t = tables(html).firstOrNull { it.size >= 3 && it.firstOrNull()?.size == 10 } ?: return emptyList()
        return t.mapNotNull { cells ->
            if (cells.size < 7) return@mapNotNull null
            val timeRaw = cells[6]
            if (timeRaw.isBlank()) return@mapNotNull null
            val (weeks, day, periods) = parseTime(timeRaw)
            CourseRow(
                name = cells[0],
                location = cells[1],
                credit = cells[2],
                attribute = cells[3],
                nature = cells[4],
                teacher = cells[5],
                timeRaw = timeRaw,
                weeks = weeks,
                dayOfWeek = day,
                periods = periods,
            )
        }
    }

    /**
     * "4-10,13-14周 星期一1091011节"
     * → weeks=[4..10,13,14], day=1, periods=[9,10,11]
     */
    fun parseTime(raw: String): Triple<List<Int>, Int, List<Int>> {
        val weekPart = raw.substringBefore("周").trim()
        val weeks = ArrayList<Int>()
        weekPart.split(",", "，").forEach { seg ->
            val s = seg.trim()
            if (s.isEmpty()) return@forEach
            if (s.contains("-")) {
                val (a, b) = s.split("-").mapNotNull { it.trim().toIntOrNull() }.let {
                    if (it.size >= 2) it[0] to it[1] else return@forEach
                }
                for (w in a..b) weeks.add(w)
            } else {
                s.toIntOrNull()?.let { weeks.add(it) }
            }
        }
        val dayMap = mapOf(
            "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7, "天" to 7
        )
        var day = 0
        val dayMatch = Regex("""星期([一二三四五六日天])""").find(raw)
        dayMatch?.let { day = dayMap[it.groupValues[1]] ?: 0 }
        val periods = ArrayList<Int>()
        val periodMatch = Regex("""(\d+)节""").find(raw)
        periodMatch?.let { pm ->
            val digits = pm.groupValues[1]
            var i = 1                      // 第一位是星期几，跳过
            while (i + 1 < digits.length) {
                digits.substring(i, i + 2).toIntOrNull()?.let { periods.add(it) }
                i += 2
            }
        }
        return Triple(weeks, day, periods)
    }
}
