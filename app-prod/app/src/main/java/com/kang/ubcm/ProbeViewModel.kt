package com.kang.ubcm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class Status { IDLE, RUNNING, OK, EMPTY, FAIL, NOAUTH }

data class Probe(
    val id: String,
    val group: String,
    val title: String,
    val hint: String,
    val status: Status = Status.IDLE,
    val summary: String = "",
    val fields: List<Pair<String, String>> = emptyList(),
    val raw: String? = null,
    val ms: Long = 0,
    val route: String? = null,
)

data class UiState(
    val token: String? = null,
    val identity: Identity? = null,
    val running: Boolean = false,
    val probes: List<Probe> = emptyList(),
    val jwCookie: String? = null,
    val castgc: String? = null,
    /** 一卡通 SSO 后的会话，供多个探针复用，避免重复换票 */
    val ecardSession: String? = null,
    val ecardOpenid: String? = null,
    val seatCookie: String? = null,
)

class ProbeViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        val t = Session.loadToken(app)
        _state.value = UiState(
            token = t,
            identity = Session.parse(t),
            jwCookie = Session.loadJwSession(app),
            castgc = Session.loadCastgc(app),
            seatCookie = Session.loadSeatCookie(app),
            probes = seedProbes(),
        )
    }

    fun setToken(raw: String) {
        val t = raw.trim()
        Session.saveToken(getApplication(), t)
        _state.value = _state.value.copy(token = t, identity = Session.parse(t))
    }

    fun setJwCookie(raw: String) {
        val c = raw.trim()
        Session.saveJwSession(getApplication(), c)
        _state.value = _state.value.copy(jwCookie = c)
    }

    fun setSeatCookie(raw: String) {
        val c = raw.trim()
        Session.saveSeatCookie(getApplication(), c)
        _state.value = _state.value.copy(seatCookie = c)
    }

    fun setCastgc(raw: String) {
        val c = raw.trim()
        Session.saveCastgc(getApplication(), c)
        _state.value = _state.value.copy(castgc = c)
    }

    fun clear() {
        Session.clear(getApplication())
        _state.value = UiState(probes = seedProbes().map { it.copy(status = Status.IDLE) })
    }

    fun update(id: String, block: (Probe) -> Probe) {
        _state.value = _state.value.copy(
            probes = _state.value.probes.map { if (it.id == id) block(it) else it }
        )
    }

    fun runAll() {
        val st = _state.value
        if (st.running) return
        val token = st.token
        val jw = st.jwCookie
        val castgc = st.castgc
        _state.value = st.copy(running = true, probes = st.probes.map { it.copy(status = Status.IDLE, summary = "", fields = emptyList(), raw = null) })
        viewModelScope.launch(Dispatchers.IO) {
            runIdentity()
            if (token == null) {
                listOf("portal.apps", "portal.msg", "portal.campus", "portal.weather", "portal.svc", "portal.ask", "jw.state", "jw.kb", "jw.score", "jw.exam")
                    .forEach { id -> update(id) { it.copy(status = Status.NOAUTH, summary = "未提供 JWT") } }
            } else {
                runPortal(token)
                runJw(jw)
            }
            runCard(castgc)
            runSeat(castgc)
            _state.value = _state.value.copy(running = false)
        }
    }

    private fun runIdentity() {
        val id = _state.value.identity
        update("identity.jwt") {
            if (id == null) it.copy(status = Status.NOAUTH, summary = "未登录 / JWT 无法解析")
            else {
                val left = id.remainingMs()
                val ok = left > 0
                it.copy(
                    status = if (ok) Status.OK else Status.FAIL,
                    summary = if (ok) "有效，剩余 ${fmtDuration(left)}" else "已过期 ${fmtDuration(-left)}",
                    fields = listOf(
                        "姓名" to id.name,
                        "学号" to id.studentNo,
                        "手机" to maskMobile(id.mobile),
                        "院系ID" to id.unitUid,
                        "卡类型" to id.cardType,
                        "卡号" to id.cardId,
                        "到期" to fmtTime(id.expAt),
                    ),
                )
            }
        }
    }

    private fun runPortal(token: String) {
        run("portal.apps") { p ->
            val r = PortalApi.apps(token)
            when {
                r.noAuth -> p.copy(status = Status.NOAUTH, summary = "JWT 被拒：${r.msg}")
                !r.ok -> p.copy(status = Status.FAIL, summary = "code=${r.code} ${r.msg}", raw = r.raw.take(400))
                else -> {
                    val data = r.data as? JSONObject
                    val list = data?.optJSONArray("APP_LIST") ?: JSONArray()
                    val mobile = (0 until list.length()).count {
                        list.optJSONObject(it)?.optString("IS_MOBILE_SHOW")?.contains("1") == true
                    }
                    val cats = data?.optJSONObject("SORT_APP_LIST")?.let { o ->
                        o.keys().asSequence().joinToString(" / ") { k -> "$k(${o.optJSONArray(k)?.length() ?: 0})" }
                    } ?: ""
                    p.copy(
                        status = if (list.length() > 0) Status.OK else Status.EMPTY,
                        summary = "共 ${list.length()} 个应用，其中 ${mobile} 个支持移动端",
                        fields = buildList {
                            add("应用总数" to "${list.length()}")
                            add("支持移动端" to "$mobile")
                            add("返回形态" to r.shape)
                            if (cats.isNotBlank()) add("分类分布" to cats)
                        },
                        raw = r.raw.take(300), ms = 0,
                    )
                }
            }
        }

        run("portal.msg") { p ->
            // ⚠️ 这里曾用 message_remind 的 total 当「待办数」，实测 118353 条 ——
            // 那是全校消息量级，不是个人待办。真正的未读数走 getCardCenterUnReadInfo（data.CNT）。
            val unread = PortalApi.unreadCount(token, "5")
            val unreadCnt = (unread.data as? JSONObject)?.optInt("CNT", -1)?.takeIf { it >= 0 }
            val svc = PortalApi.todoServiceNum(token)
            val allNum = (svc.data as? JSONObject)?.optInt("ALL_NUM", -1)?.takeIf { it >= 0 }
            val listResp = PortalApi.todoList(token, tabType = "1", page = 1, size = 3)
            val listData = listResp.data as? JSONObject
            val first = listData?.optJSONArray("list")?.optJSONObject(0)
            val listTotal = listData?.optInt("total", -1)?.takeIf { it >= 0 }

            p.copy(
                status = when {
                    unread.noAuth -> Status.NOAUTH
                    unreadCnt != null -> if (unreadCnt > 0) Status.OK else Status.EMPTY
                    else -> Status.FAIL
                },
                summary = when {
                    unreadCnt != null -> "未读待办 $unreadCnt 条" + if (allNum != null) "（待办总数 $allNum）" else ""
                    else -> "未读接口无数据：code=${unread.code} ${unread.msg}"
                },
                fields = buildList {
                    add("未读数来源" to "getCardCenterUnReadInfo(tab=5)")
                    add("未读 CNT" to (unreadCnt?.toString() ?: "-"))
                    add("待办总数" to (allNum?.toString() ?: "-"))
                    add("待受理列表 total" to (listTotal?.toString() ?: "-"))
                    first?.let {
                        add("首条待办" to it.optString("MSG_TITLE"))
                        add("首条时间" to it.optString("CREATE_TIME"))
                        add("已读标记" to it.optString("IS_READ"))
                    }
                },
                raw = unread.raw.take(200),
            )
        }

        run("portal.campus") { p ->
            val r = PortalApi.campusInfo(token)
            val d = r.data as? JSONObject
            val total = d?.optInt("total", 0) ?: 0
            val list = d?.optJSONArray("list") ?: JSONArray()
            val first = list.optJSONObject(0)
            p.copy(
                status = if (r.ok) Status.OK else Status.FAIL,
                summary = if (r.ok) "校园信息 $total 条" else "code=${r.code} ${r.msg}",
                fields = buildList {
                    add("总数" to "$total")
                    first?.let {
                        add("来源单位" to it.optString("BELONG_UNIT_NAME"))
                        add("标题" to it.optString("PIM_TITLE").ifBlank { it.optString("TITLE") })
                        add("时间" to it.optString("TIME"))
                    }
                },
                raw = r.raw.take(300),
            )
        }

        run("portal.weather") { p ->
            val r = PortalApi.weather(token)
            val d = r.data as? JSONObject
            val cd = d?.optJSONObject("CURRENT_DATE")
            p.copy(
                status = if (r.ok) Status.OK else Status.FAIL,
                summary = cd?.optString("WEEK_DAY").orEmpty(),
                fields = listOf(
                    "日期" to cd?.optString("WEEK_DAY").orEmpty(),
                    "农历" to cd?.optString("LUNAR_CALENDAR").orEmpty(),
                    "天气" to if (d?.isNull("WEATHER") != false) "（服务未返回）" else d.optString("WEATHER"),
                ),
                raw = r.raw.take(200),
            )
        }

        run("portal.svc") { p ->
            val r = PortalApi.services(token)
            val d = r.data as? JSONObject
            val total = d?.let { o -> o.keys().asSequence().sumOf { o.optJSONArray(it)?.length() ?: 0 } } ?: 0
            val cats = d?.let { o -> o.keys().asSequence().sorted().joinToString(" ") { "$it:${o.optJSONArray(it)?.length() ?: 0}" } } ?: ""
            p.copy(
                status = if (total > 0) Status.OK else Status.FAIL,
                summary = "服务大厅 $total 项（8 类）",
                fields = listOf("分类计数" to cats, "返回形态" to r.shape),
                raw = r.raw.take(200),
            )
        }

        run("portal.ask") { p ->
            val r = PortalApi.askList(token)
            val d = r.data as? JSONObject
            val total = d?.optInt("total", 0) ?: 0
            val first = d?.optJSONArray("list")?.optJSONObject(0)
            p.copy(
                status = if (r.ok) Status.OK else Status.FAIL,
                summary = "校务问答 $total 条",
                fields = listOf(
                    "首条标题" to (first?.optString("ASK_TITLE") ?: ""),
                    "提问人" to (first?.optString("USER_NAME") ?: ""),
                ),
                raw = r.raw.take(200),
            )
        }
    }

    private fun runJw(cookie: String?) {
        if (cookie.isNullOrBlank()) {
            listOf("jw.state", "jw.kb", "jw.score", "jw.exam").forEach {
                update(it) { p -> p.copy(status = Status.NOAUTH, summary = "未提供 JSESSIONID（教务是另一套会话）") }
            }
            return
        }
        run("jw.state") { p ->
            val r = JwApi.home(cookie)
            val logged = r.isHttpOk() && !JwApi.isLoginPage(r.body)
            p.copy(
                status = if (logged) Status.OK else Status.FAIL,
                summary = if (logged) "教务会话有效" else "被踢回登录页（HTTP ${r.http}）",
                fields = listOf("HTTP" to "${r.http}", "字节" to "${r.body.length}"),
                raw = r.body.take(200),
            )
        }
        run("jw.kb") { p ->
            val r = JwApi.courseTableJson(cookie)
            if (JwApi.isLoginPage(r.body)) {
                return@run p.copy(status = Status.FAIL, summary = "未登录")
            }
            val courses = JwApi.parseCoursesJson(r.body)
            val periods = JwApi.parsePeriods(r.body)
            val byName = courses.groupBy { it.name }
            val todayCourses = courses.filter { it.weeks.contains(12) }.size
            p.copy(
                status = if (courses.isNotEmpty()) Status.OK else Status.EMPTY,
                summary = if (courses.isEmpty())
                    "接口通了但本月无排课（共 ${r.body.length} 字节）"
                else "解析出 ${byName.size} 门课 / ${courses.size} 条排课，第 12 周有 $todayCourses 节次",
                fields = byName.entries.take(10).map { (n, rows) ->
                    val c = rows.first()
                    n to "${c.teacher} · ${c.location} · 周${c.dayOfWeek} 第${c.periods.joinToString(",")}节 · 共${rows.sumOf { it.weeks.size }}周次"
                },
                raw = buildString {
                    appendLine("节次表：")
                    periods.forEach { appendLine("  ${it.index}. ${it.start}-${it.end} (${it.from}-${it.to})") }
                    appendLine("字段：kcmc/jg0101mc/jx0601mc/kkzcmx/kcsj/ktmc")
                },
            )
        }
        run("jw.score") { p ->
            val r = JwApi.scoreList(cookie)
            when {
                JwApi.isLoginPage(r.body) -> p.copy(status = Status.FAIL, summary = "未登录")
                else -> {
                    val scores = JwApi.parseScores(r.body)
                    if (scores.isEmpty()) {
                        p.copy(
                            status = Status.EMPTY,
                            summary = "查询成功，但当前没有成绩记录（新生第一学期期末未开考时正常）",
                            fields = listOf(
                                "查询方式" to "POST /kscj/cjcx_list",
                                "返回" to "${r.body.length} 字节 / ${r.body.length}",
                            ),
                            raw = "表头：序号|开课学期|课程名称|最终成绩|原始成绩|总学时|学分|绩点|课程属性|考试性质\n结果：未查询到数据",
                        )
                    } else {
                        val credits = scores.sumOf { it.getOrNull(6)?.toDoubleOrNull() ?: 0.0 }
                        p.copy(
                            status = Status.OK,
                            summary = "共 ${scores.size} 门课程，合计 ${"%.1f".format(credits)} 学分",
                            fields = scores.take(10).map { row ->
                                row.getOrElse(2) { "?" } to "${row.getOrElse(3) { "-" }} 分 · ${row.getOrElse(6) { "-" }} 学分 · 绩点 ${row.getOrElse(7) { "-" }}"
                            },
                            raw = scores.take(5).joinToString("\n") { it.take(8).joinToString(" | ") },
                        )
                    }
                }
            }
        }
        run("jw.exam") { p ->
            val r = JwApi.examPage(cookie)
            p.copy(
                status = if (JwApi.isLoginPage(r.body)) Status.FAIL else Status.OK,
                summary = "考试页可访问（${r.body.length} 字节）",
                raw = r.body.take(150),
            )
        }
    }

    private fun runCard(castgc: String?) {
        // 一卡通：CAS(CASTGC) → ykt SSO → servicehall 会话
        var session: String? = _state.value.ecardSession
        var openid: String? = _state.value.ecardOpenid

        if ((session == null || openid == null) && !castgc.isNullOrBlank()) {
            val lr = EcardApi.login(castgc)
            session = lr.session
            openid = lr.openid
            _state.value = _state.value.copy(ecardSession = session, ecardOpenid = openid)
            update("card.sso") { p ->
                p.copy(
                    status = if (lr.ok) Status.OK else Status.FAIL,
                    summary = if (lr.ok) "SSO 成功，拿到 servicehall 会话" else "SSO 失败（CASTGC 可能过期）",
                    fields = listOf(
                        "会话" to (lr.session?.let { it.take(8) + "…" } ?: "无"),
                        "openid" to (lr.openid?.take(12) ?: "未找到"),
                    ),
                    raw = lr.log,
                )
            }
        } else if (castgc.isNullOrBlank()) {
            listOf("card.sso", "card.balance", "card.bill").forEach {
                update(it) { p -> p.copy(status = Status.NOAUTH, summary = "未提供 CASTGC（一卡通靠 CAS 单点）") }
            }
            return
        }

        val sid = session
        val oid = openid
        val idserial = _state.value.identity?.studentNo.orEmpty()
        if (sid == null || oid == null || idserial.isBlank()) {
            listOf("card.balance", "card.bill").forEach {
                update(it) { p -> p.copy(status = Status.FAIL, summary = "缺少会话 / openid / 学号") }
            }
            return
        }

        // 一次请求拿余额 + 流水（余额在每条流水的 balance 字段里）
        val r = EcardApi.trades(sid, oid, idserial, startOfHalfYear(), today())
        val plain = EcardApi.unwrap(r.body)          // 解密：key 在密文前 16 位
        val page = EcardApi.parseTrades(plain)

        update("card.balance") { p ->
            val latestBalance = page?.trades?.firstOrNull()?.balanceYuan
            when {
                page == null -> p.copy(
                    status = Status.EMPTY,
                    summary = "解密后仍无法解析（原始 ${r.body.length}B，明文 ${plain.length}B）",
                    fields = listOf(
                        "原始" to r.body.take(50),
                        "解密" to plain.take(80),
                        "密钥来源" to "密文前 16 位",
                    ),
                    raw = plain.take(300),
                )
                latestBalance == null -> p.copy(status = Status.EMPTY, summary = "无流水，无法推算余额")
                else -> p.copy(
                    status = Status.OK,
                    summary = "卡余额 ￥$latestBalance",
                    fields = listOf(
                        "余额" to "￥$latestBalance",
                        "来源" to "最新流水的 balance 字段",
                        "学号" to idserial,
                        "接口" to "thirdQuerSelfTrade",
                    ),
                    raw = "total=${page.total}, pages=${page.pages}",
                )
            }
        }

        update("card.bill") { p ->
            if (page == null) {
                p.copy(status = Status.FAIL, summary = "解析失败", raw = r.body.take(300))
            } else if (page.trades.isEmpty()) {
                p.copy(status = Status.EMPTY, summary = "近半年无交易")
            } else {
                val spend = page.trades.filter { it.amountYuan.startsWith("-") }
                p.copy(
                    status = Status.OK,
                    summary = "共 ${page.total} 条 / ${page.pages} 页，本页 ${page.trades.size} 条",
                    fields = spend.take(8).map { t ->
                        t.merchant.take(14) to "${t.amountYuan} 元 · ${fmtTs(t.timeMs)}"
                    },
                    raw = page.trades.take(5).joinToString("\n") {
                        "${it.merchant} | ${it.amountYuan} | 余${it.balanceYuan} | ${fmtTs(it.timeMs)}"
                    },
                )
            }
        }
    }

    private fun runSeat(castgc: String?) {
        if (castgc.isNullOrBlank()) {
            listOf("seat.login", "seat.menu", "seat.status", "seat.punish").forEach {
                update(it) { p -> p.copy(status = Status.NOAUTH, summary = "未提供 CASTGC") }
            }
            return
        }
        var cookie = _state.value.seatCookie
        if (cookie == null) {
            val lr = SeatApi.login(castgc)
            cookie = lr.cookie
            _state.value = _state.value.copy(seatCookie = cookie)
            update("seat.login") { p ->
                p.copy(
                    status = if (lr.ok) Status.OK else Status.FAIL,
                    summary = if (lr.ok) "CAS 单点成功，拿到 ic-cookie" else "登录失败",
                    fields = listOf("ic-cookie" to (cookie?.take(10) ?: "无")),
                    raw = lr.log,
                    route = "direct",
                )
            }
        } else {
            update("seat.login") { p -> p.copy(status = Status.OK, summary = "已有 ic-cookie，跳过登录") }
        }
        val ck = cookie ?: return

        run("seat.menu") { p ->
            val r = SeatApi.get("seatMenu", ck)
            val menu = SeatApi.parseSeatMenu(r.resp.body)
            val totalRemain = menu?.areas?.sumOf { it.remain } ?: 0
            val totalSeat = menu?.areas?.sumOf { it.total } ?: 0
            p.copy(
                status = if (menu != null && menu.areas.isNotEmpty()) Status.OK else Status.FAIL,
                route = r.route.name,
                summary = if (menu != null)
                    "${menu.areas.size} 个楼层，空位 $totalRemain / 总 $totalSeat"
                else "解析失败: ${r.resp.body.take(60)}",
                fields = menu?.areas?.map { a ->
                    a.name to "${a.remain}/${a.total}（${a.children.joinToString("、") { it.name }}）"
                } ?: emptyList(),
                raw = r.resp.body.take(300),
            )
        }

        run("seat.status") { p ->
            val r = SeatApi.get("seatDevice/resvStatus", ck)
            val st = SeatApi.parseStatus(r.resp.body)
            p.copy(
                status = if (st != null) Status.OK else Status.FAIL,
                route = r.route.name,
                summary = st?.let { "全馆可用 ${it.first} 座，已用 ${it.second} 座" } ?: "解析失败",
                raw = r.resp.body.take(200),
            )
        }

        run("seat.punish") { p ->
            val r = SeatApi.get("reserve/punishInfo", ck)
            val ok = SeatApi.code(r.resp.body) == 0
            val body = r.resp.body
            p.copy(
                status = if (ok) Status.OK else Status.FAIL,
                route = r.route.name,
                summary = if (ok) (if (body.contains("[]")) "无违规记录" else "有违规记录") else "接口返回异常",
                raw = body.take(300),
            )
        }
    }

    private fun startOfHalfYear(): String {
        val c = java.util.Calendar.getInstance()
        val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
        c.add(java.util.Calendar.MONTH, -6)
        return d.format(c.time)
    }

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date())

    private fun fmtTs(ms: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))

    private fun run(id: String, block: (Probe) -> Probe) {
        val cur = _state.value.probes.firstOrNull { it.id == id } ?: return
        update(id) { it.copy(status = Status.RUNNING, summary = "…") }
        val t0 = System.currentTimeMillis()
        val res = try {
            block(cur.copy(status = Status.RUNNING))
        } catch (e: Exception) {
            cur.copy(status = Status.FAIL, summary = "${e.javaClass.simpleName}: ${e.message}")
        }
        update(id) { res.copy(ms = System.currentTimeMillis() - t0) }
    }

    companion object {
        fun seedProbes() = listOf(
            Probe("identity.jwt", "身份", "JWT 解包", "从 accessToken 读姓名/学号/院系/卡号"),
            Probe("portal.apps", "门户", "应用清单", "163 个应用 + 移动端标记"),
            Probe("portal.msg", "门户", "未读待办", "getCardCenterUnReadInfo → data.CNT"),
            Probe("portal.campus", "门户", "校园信息", "校内通知流"),
            Probe("portal.weather", "门户", "天气 / 农历", "首页顶部卡片数据"),
            Probe("portal.svc", "门户", "服务大厅", "979 项办事指南"),
            Probe("portal.ask", "门户", "校务问答", "你问我答"),
            Probe("jw.state", "教务", "会话检测", "JSESSIONID 是否有效"),
            Probe("jw.kb", "教务", "课表", "HTML 表格 → 结构化课程"),
            Probe("jw.score", "教务", "成绩", "成绩查询页可访问性"),
            Probe("jw.exam", "教务", "考试", "考试安排页可访问性"),
            Probe("seat.login", "图书馆座位", "CAS 单点", "CASTGC → ic-cookie"),
            Probe("seat.menu", "图书馆座位", "楼层空位", "各层剩余座位数"),
            Probe("seat.status", "图书馆座位", "全馆状态", "可用/已用座位"),
            Probe("seat.punish", "图书馆座位", "违规记录", "我的违约情况"),
            Probe("card.sso", "一卡通", "CAS 单点", "CASTGC → servicehall 会话"),
            Probe("card.balance", "一卡通", "卡余额", "从最新流水推算"),
            Probe("card.bill", "一卡通", "消费流水", "近半年交易记录"),
        )

        fun fmtTime(ms: Long): String {
            val f = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            return f.format(java.util.Date(ms))
        }

        fun fmtDuration(ms: Long): String {
            val s = ms / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            return if (h > 0) "${h}h${m}m" else "${m}m"
        }

        fun maskMobile(m: String): String =
            if (m.length == 11) m.substring(0, 3) + "****" + m.substring(7) else m
    }
}
