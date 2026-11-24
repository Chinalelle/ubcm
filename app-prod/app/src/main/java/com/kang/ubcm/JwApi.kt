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
                // ⚠️ 必须过 cleanFieldText：optString 对 JSON null 返回字符串 "null"，
                // 不清洗会在课表上显示「教室 null」（真机踩到过）。
                name = cleanFieldText(o.optString("kcmc")),
                location = cleanFieldText(o.optString("jx0601mc")),
                credit = "",
                attribute = cleanFieldText(o.optString("jzwmc")),
                nature = cleanFieldText(o.optString("ktmc")),
                teacher = cleanFieldText(o.optString("jg0101mc")),
                timeRaw = cleanFieldText(o.optString("kkzc")),
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

    // ---------- 空教室（只读查询，非实时） ----------

    /**
     * 空教室查询页。**参数未实测**（docs/27 §5 明说），先按强智常见形态探测；
     * 拿不到有效表格时 UI 显示"暂无数据"，不崩。
     *
     * 教务只提供"查空闲教室"，没有预约接口 —— 这是只读页，红线里也不该有写操作。
     */
    fun emptyRoomPage(
        jsessionCookie: String,
        week: String? = null,
        day: String? = null,
        period: String? = null,
    ): Net.Resp {
        // 实测（P8，真实响应）这是一个**两层 iframe 套娃**：
        //   ① llsykb_frm.jsp                      → iframe: /Logon.do?method=toFinGlKbCx&token=...
        //   ② Logon.do?...                        → iframe: /tkglAction.do?method=llsykbFind&xnxq01id=2026-2027-1...
        //   ③ tkglAction.do?method=llsykbFind     → 真正的教室表格
        // 直接拿 ① 去解析永远拿不到数据（这就是"空教室一直空白"的根因）。
        var resp = Net.get("${Net.JW_BASE}/jsxsd/view/kbxx/kbcx/llsykb_frm.jsp", cookie = jsessionCookie)
        repeat(3) {
            if (resp.body.contains("<table", ignoreCase = true)) return resp
            val src = iframeSrc(resp.body) ?: return resp
            val next = when {
                src.startsWith("http") -> src
                src.startsWith("//") -> "https:$src"
                src.startsWith("/") -> Net.JW_BASE + src
                else -> Net.JW_BASE + "/" + src
            }
            resp = Net.get(next.replace("&amp;", "&"), cookie = jsessionCookie)
        }
        // 日期/节次参数服务端不认（实测传了也不生效），这里只把本次响应当数据页返回
        return resp
    }

    private fun iframeSrc(html: String): String? =
        Regex("""<iframe[^>]*\ssrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.replace("&amp;", "&")?.takeIf { it.isNotBlank() }

    data class EmptyRoom(val building: String, val room: String, val free: Boolean, val note: String = "")

    /** 教室名：`良东101` / `西院302` / `教101` 之类（汉字前缀 + 3~4 位数字）。 */
    private val ROOM_RE = Regex("""^[\u4e00-\u9fa5A-Za-z]{0,6}\d{3,4}$""")

    /**
     * 解析空教室表格（宽松模式，HTML 结构未实测）。
     *
     * 兼容两种常见排布：
     *  A. 一行一个教室：第一格是教室名，行内出现"空闲/可借/无课"→空闲；否则有内容→占用
     *  B. 表头式：第一行是节次，行列交叉处空 = 空闲
     * 解析不出来就返回空列表（页面显示"暂无数据"），绝不抛异常。
     */
    fun parseEmptyRooms(html: String): List<EmptyRoom> {
        val out = LinkedHashMap<String, EmptyRoom>()
        for (table in tables(html)) {
            for (row in table) {
                if (row.size < 2) continue
                val first = row.first()
                if (!ROOM_RE.matches(first)) continue
                val building = first.replace(Regex("""\d+$"""), "")
                val rest = row.drop(1).joinToString(" ")
                val free = rest.contains("空闲") || rest.contains("可借") || rest.contains("无课") ||
                    rest.isBlank()
                out[first] = EmptyRoom(
                    building = building,
                    room = first,
                    free = free,
                    note = if (free) "" else rest.take(24),
                )
            }
        }
        return out.values.sortedBy { it.room }
    }

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
            // 强智登录页的三个稳定标志（P8 用真实响应确认过）
            html.contains("loginForm") ||
            html.contains("LoginToXk") ||
            html.contains("name=\"userAccount\"") ||
            // 强智的"半登录"提示页：HTTP 200 + 一张表格里只有这句话（P8 实测）
            html.contains("用户没有登录") ||
            html.contains("没有登录，请重新登录") ||
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
