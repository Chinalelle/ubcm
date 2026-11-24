package com.kang.ubcm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 首页聚合状态。四张卡各有独立错误位，互不牵连：
 * 余额挂了不该让「下一节课」也不显示。
 */
data class HomeState(
    val now: LocalDateTime,
    /** 第几周；开学前为 0 或负，学期结束后会超出课表最大值 */
    val week: Int? = null,
    val next: NextCourse? = null,
    val today: List<NextCourse> = emptyList(),
    /** 一卡通 */
    val balanceYuan: String? = null,
    val todaySpendYuan: String? = null,
    val todaySpendCount: Int = 0,
    val cardError: String? = null,
    /** 待办 */
    val todoUnread: Int? = null,
    val todoTotal: Int? = null,
    val todoLatest: String? = null,
    val todoError: String? = null,
    /** 课表缓存时间（0 = 从没拉过） */
    val timetableFetchedAt: Long = 0L,
    val timetableIsStale: Boolean = false,
    val refreshing: Boolean = false,
    /** 门户 JWT 有效（未过期）。JWT 只有 4 小时，过期是常态，不是异常。 */
    val hasJwt: Boolean = false,
    /** 手里有 JWT 但已过期 —— 需要用户点一下续登 */
    val jwtExpired: Boolean = false,
    /** CAS 长效票据（一卡通/教务靠它） */
    val hasCastgc: Boolean = false,
    /** 整学期排课（周视图用）。空表示还没有课表。 */
    val sessions: List<CourseSession> = emptyList(),
    /** 小节作息；没有时用兜底表 */
    val slots: List<PeriodSlot> = Timetable.FALLBACK_SLOTS,
    /** 本学期首周周一（周次锚点） */
    val firstWeekMonday: LocalDate = LocalDate.parse(Timetable.DEFAULT_FIRST_WEEK_MONDAY),
    /**
     * 用户在周视图里手动翻到的周次。
     * null = **跟随今天**（每次重算都回到当前周，这是默认且最符合直觉的行为）；
     * 非 null = 用户已经翻走了，不要再自动把他拽回来。
     */
    val pinnedWeek: Int? = null,
) {
    val weekText: String?
        get() = week?.let { if (it >= 1) "第 $it 周" else "未开学" }

    /** 今天属于第几周（没有课表时按第 1 周兜底） */
    val currentWeek: Int get() = week ?: 1

    /** 周视图正在显示第几周 */
    val shownWeek: Int get() = pinnedWeek ?: currentWeek

    /** 是否正处在「本周」 */
    val onCurrentWeek: Boolean get() = shownWeek == currentWeek

    /** 距下一节课的分钟数（负数=已开始） */
    fun minutesToNext(): Long? = next?.let { it.time.minutesUntilStart(now) }

    fun minutesToEnd(): Long? = next?.let { it.time.minutesUntilEnd(now) }
}

/**
 * 首页数据聚合：课表（本地缓存优先）+ 一卡通余额/今日消费 + 未读待办。
 *
 * 「本地优先」的落地方式：
 *  1. 构造时**先读盘**，界面立刻有内容（离线、冷启动都快）
 *  2. 再后台判断：有凭证才联网刷新，没凭证不打扰用户
 *  3. 刷新失败不清空已有内容，只在对应卡片上标错误
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(HomeState(now = LocalDateTime.now()))
    val state = _state.asStateFlow()

    private var cache: TimetableStore.Cache? = null
    private var firstWeekMonday: LocalDate = LocalDate.now()
    private var tickerStarted = false
    private var cardBusy = false

    init {
        firstWeekMonday = Timetable.parseFirstWeekMonday(Session.loadFirstWeekMonday(app))
            ?: LocalDate.parse(Timetable.DEFAULT_FIRST_WEEK_MONDAY)

        val token = Session.loadToken(app)
        val castgc = Session.loadCastgc(app)
        val cached = TimetableStore.load(app)
        if (cached != null) {
            cache = cached
            _state.value = _state.value.copy(
                timetableFetchedAt = cached.fetchedAt,
                timetableIsStale = cached.ageMs() > STALE_MS,
            )
        }
        authFlags()
        recomputeTime()
        startTicker()
    }

    /**
     * 刷新登录态标记。JWT 4 小时一过期，CASTGC 是长效的，
     * 所以「门户能用」和「一卡通能用」是两件事，UI 必须分开表达。
     */
    private fun authFlags() {
        val ctx = getApplication<Application>()
        val token = Session.loadToken(ctx)
        val castgc = Session.loadCastgc(ctx)
        val id = Session.parse(token)
        val expired = token != null && (id == null || id.remainingMs() <= 0)
        _state.value = _state.value.copy(
            hasJwt = token != null && !expired,
            jwtExpired = expired,
            hasCastgc = !castgc.isNullOrBlank(),
        )
    }

    /** 每分钟（其实 20 秒够了）重算倒计时。用户停在首页看倒计时跳字。 */
    private fun startTicker() {
        if (tickerStarted) return
        tickerStarted = true
        viewModelScope.launch {
            while (true) {
                delay(20_000)
                recomputeTime()
            }
        }
    }

    private fun recomputeTime() {
        val now = LocalDateTime.now()
        val sessions = cache?.sessions.orEmpty()
        val slots = cache?.slots ?: Timetable.FALLBACK_SLOTS
        val week = if (cache == null) null else Timetable.weekOf(now.toLocalDate(), firstWeekMonday)
        _state.value = _state.value.copy(
            now = now,
            week = week,
            next = Timetable.nextCourse(sessions, slots, now, firstWeekMonday),
            today = Timetable.todayCourses(sessions, slots, now, firstWeekMonday),
            // 周视图的数据源。列表是引用，copy 不会复制内容。
            sessions = sessions,
            slots = slots,
            firstWeekMonday = firstWeekMonday,
        )
    }

    /**
     * 周视图切到某一周。传 null 表示「回到跟随今天」。
     *
     * 刻意不做边界钳制：翻到学期外看到一张空表 + 一句「这一周没有课」，
     * 比按钮突然点不动更容易理解。
     */
    fun selectWeek(week: Int?) {
        _state.value = _state.value.copy(pinnedWeek = week)
    }

    /** 上一周 / 下一周 */
    fun shiftWeek(delta: Int) {
        val next = (_state.value.shownWeek + delta).coerceAtLeast(MIN_NAV_WEEK)
        selectWeek(next)
    }

    /**
     * 改周次锚点（本学期首周的周一）。
     *
     * 返回 null = 保存成功；非 null = 校验失败的说明，调用方直接显示给用户。
     * 必须校验「是周一」：填成周二不会抛异常，只会让整学期每门课都错一天（见 Timetable.validateFirstWeekMonday）。
     */
    fun setFirstWeekMonday(raw: String): String? {
        Timetable.validateFirstWeekMonday(raw)?.let { return it }
        val d = Timetable.parseFirstWeekMonday(raw) ?: return "日期解析失败"
        Session.saveFirstWeekMonday(getApplication(), d.toString())
        firstWeekMonday = d
        // 改了锚点，之前"翻到第 N 周"的意图已经失效，回到跟随今天
        _state.value = _state.value.copy(pinnedWeek = null)
        recomputeTime()
        return null
    }

    /**
     * 刷新全部。force=false 时只补缺失的部分（缓存新鲜就不重复拉课表）。
     */
    fun refresh(force: Boolean = true) {
        val st = _state.value
        if (st.refreshing) return
        android.util.Log.i(TAG, "refresh(force=$force) cache=${cache != null}")
        authFlags()
        _state.value = _state.value.copy(refreshing = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val needTimetable = force || cache == null ||
                    cache!!.ageMs() > STALE_MS
                if (needTimetable) refreshTimetable()
                recomputeTime()
                refreshCard(force)
                refreshTodo(force)
            } finally {
                val s = _state.value
                _state.value = s.copy(
                    refreshing = false,
                    timetableIsStale = cache?.let { it.ageMs() > STALE_MS } ?: true,
                )
                authFlags()
            }
        }
    }

    // ---------- 课表 ----------

    /** 从教务拉课表并落盘。没有教务会话就静默跳过（保留缓存）。 */
    private fun refreshTimetable() {
        val ctx = getApplication<Application>()
        val jw = Session.loadJwSession(ctx)
        if (jw.isNullOrBlank()) {
            android.util.Log.i(TAG, "timetable: 跳过（无教务会话）")
            return
        }
        val resp = JwApi.courseTableJson(jw)
        val loginPage = JwApi.isLoginPage(resp.body)
        android.util.Log.i(TAG, "timetable: http=${resp.http} len=${resp.body.length} loginPage=$loginPage body=${resp.body.take(160)}")
        android.util.Log.i(TAG, "timetable: cookieLen=${jw.length} cookie=${jw.take(70)}")
        if (loginPage) return
        val fresh = TimetableStore.fromJwResponse(resp.body)
        android.util.Log.i(TAG, "timetable: parsed sessions=${fresh?.sessions?.size} slots=${fresh?.slots?.size}")
        if (fresh != null) {
            cache = fresh
            TimetableStore.save(ctx, fresh)
            // 同步到 UI：否则 footer 会一直显示「尚未拉取课表」
            _state.value = _state.value.copy(
                timetableFetchedAt = fresh.fetchedAt,
                timetableIsStale = false,
            )
            android.util.Log.i(TAG, "timetable: saved cache, fetchedAt=${fresh.fetchedAt}")
        }
    }

    // ---------- 一卡通 ----------

    /**
     * 余额与今日消费来自同一次流水查询：
     *  - 余额 = 最新一条流水的 balance 字段（平台不单给余额接口）
     *  - 今日消费 = 今天所有负数金额之和
     */
    private fun refreshCard(force: Boolean) {
        if (cardBusy) return
        val ctx = getApplication<Application>()
        val castgc = Session.loadCastgc(ctx)
        if (castgc.isNullOrBlank()) {
            _state.value = _state.value.copy(cardError = null)
            return
        }
        val idserial = Session.parse(Session.loadToken(ctx))?.studentNo
        if (idserial.isNullOrBlank()) {
            _state.value = _state.value.copy(cardError = "需要登录门户以获取学号")
            return
        }
        cardBusy = true
        try {
            // 注意：不能因为 force 就把缓存的一卡通会话清掉。
            // 缓存会话比 CASTGC 活得久（实测 CASTGC 过期后 servicehall 仍可用），
            // 清掉就等于把还能用的会话丢了。正确做法：先用缓存，失败了再重登（见下）。
            var session = Session.loadEcardSession(ctx)
            var openid = Session.loadEcardOpenid(ctx)

            if (session == null || openid == null) {
                val lr = EcardApi.login(castgc)
                if (!lr.ok || lr.session == null || lr.openid == null) {
                    _state.value = _state.value.copy(cardError = "一卡通单点失败（CASTGC 可能过期）")
                    return
                }
                session = lr.session
                openid = lr.openid
                Session.saveEcard(ctx, session, openid)
            }

            val end = LocalDate.now()
            val start = end.minusDays(LOOKBACK_DAYS)
            val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val resp = EcardApi.trades(
                session = session,
                openid = openid,
                idserial = idserial,
                start = fmt.format(start),
                end = fmt.format(end),
                size = 50,
            )
            val plain = EcardApi.unwrap(resp.body)
            var page = EcardApi.parseTrades(plain)

            // 会话可能已失效：重登一次再试
            if (page == null) {
                Session.clearEcard(ctx)
                val lr = EcardApi.login(castgc)
                if (lr.ok && lr.session != null && lr.openid != null) {
                    Session.saveEcard(ctx, lr.session, lr.openid)
                    val r2 = EcardApi.trades(lr.session, lr.openid, idserial, fmt.format(start), fmt.format(end), size = 50)
                    page = EcardApi.parseTrades(EcardApi.unwrap(r2.body))
                }
            }

            if (page == null) {
                _state.value = _state.value.copy(cardError = "流水解析失败")
                return
            }

            val today = LocalDate.now()
            val zone = ZoneId.of("Asia/Shanghai")
            val todayTrades = page.trades.filter {
                Instant.ofEpochMilli(it.timeMs).atZone(zone).toLocalDate() == today
            }
            val spendCents = todayTrades.sumOf { t ->
                val v = t.amountYuan.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                if (v.signum() < 0) v.abs() else java.math.BigDecimal.ZERO
            }
            val balance = page.trades.firstOrNull()?.balanceYuan

            _state.value = _state.value.copy(
                balanceYuan = balance,
                todaySpendYuan = spendCents.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                todaySpendCount = todayTrades.count { it.amountYuan.startsWith("-") },
                cardError = null,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(cardError = "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            cardBusy = false
        }
    }

    // ---------- 待办 ----------

    /**
     * 未读待办。走门户消息接口的「待办提醒」页签。
     * 注意：这个数字是**待办/提醒**，不是邮箱未读（邮箱另走 /bucm/oaemail/haveRedPoint）。
     */
    private fun refreshTodo(force: Boolean) {
        val ctx = getApplication<Application>()
        val token = Session.loadToken(ctx)
        if (token.isNullOrBlank()) {
            _state.value = _state.value.copy(todoError = null)
            return
        }
        try {
            val r = PortalApi.todoSummary(token)
            if (!r.ok) {
                _state.value = _state.value.copy(todoError = if (r.noAuth) "登录已过期" else r.msg)
                return
            }
            _state.value = _state.value.copy(
                todoUnread = r.unread,
                todoTotal = r.total,
                todoLatest = r.latestTitle,
                todoError = null,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(todoError = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "UBcmHome"
        /** 课表缓存超过 6 小时就当陈旧（教务一天里可能调课） */
        const val STALE_MS = 6 * 60 * 60 * 1000L
        /** 流水往回看几天：够覆盖余额 + 今天的消费 */
        const val LOOKBACK_DAYS = 30L
        /** 周视图往左最多翻到第几周（再往前没有意义，教务也不排） */
        const val MIN_NAV_WEEK = 0
    }
}
