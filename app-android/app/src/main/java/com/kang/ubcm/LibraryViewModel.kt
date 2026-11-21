package com.kang.ubcm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 图书馆座位状态。
 *
 * 数据全部来自 `/ic-web`（只读）：
 *  - `seatMenu`            楼层 + 区域的总数/空位数
 *  - `seatDevice/resvStatus` 全馆可用/已用
 *  - `reserve/punishInfo`  违约记录
 *
 * 预约（写操作）**不在 App 内做**，交给学校页面 —— 见 docs/05 的合规边界。
 */
data class LibraryState(
    val floors: List<Floor> = emptyList(),
    /** 全馆 */
    val available: Int? = null,
    val used: Int? = null,
    /** 违规记录条数（0 表示干净） */
    val punishCount: Int = 0,
    val punishDetail: List<String> = emptyList(),
    /** 我的预约（含历史），倒序 */
    val reservations: List<SeatApi.Resv> = emptyList(),
    /** 当前/待生效的预约，排在最前面展示 */
    val upcoming: List<SeatApi.Resv> = emptyList(),
    /** 「找空桌」结果（按区域拉座位图 + 桌面聚类） */
    val desks: List<DeskFinder.Desk> = emptyList(),
    val desksLoading: Boolean = false,
    val desksDate: String = "",
    val desksTotalSeats: Int = 0,
    val desksFreeSeats: Int = 0,
    val desksError: String? = null,
    val desksFetchedAt: Long = 0L,
    /** 用户选择的使用时段（当天分钟数），默认 08:30–22:00 */
    val slotStart: Int = 8 * 60 + 30,
    val slotEnd: Int = 22 * 60,
    /** 预约流程状态 */
    val reserving: Boolean = false,
    val reserveMessage: String? = null,
    val reserveOk: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val needLogin: Boolean = false,
    val fetchedAt: Long = 0L,
) {
    val totalSeats: Int get() = floors.sumOf { it.total }
    val freeSeats: Int get() = floors.sumOf { it.remain }
    val hasData: Boolean get() = floors.isNotEmpty()

    /** 全馆占用率 0..1 */
    val occupiedRatio: Float
        get() {
            val a = available ?: return 0f
            val u = used ?: return 0f
            val t = a + u
            return if (t <= 0) 0f else u.toFloat() / t
        }

    data class Floor(
        val id: Int,
        val name: String,
        val total: Int,
        val remain: Int,
        val areas: List<Area>,
    ) {
        val occupied: Int get() = (total - remain).coerceAtLeast(0)
        val freeRatio: Float get() = if (total <= 0) 0f else remain.toFloat() / total
        /** 中文里"图书馆二层" → "二层" */
        val shortName: String get() = name.removePrefix("图书馆")
    }

    data class Area(
        val id: Int,
        val name: String,
        val total: Int,
        val remain: Int,
    ) {
        val freeRatio: Float get() = if (total <= 0) 0f else remain.toFloat() / total
        val full: Boolean get() = remain <= 0
    }
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(LibraryState())
    val state = _state.asStateFlow()

    fun refresh() {
        val st = _state.value
        if (st.loading) return
        val ctx = getApplication<Application>()
        val cookie = Session.loadSeatCookie(ctx)
        if (cookie.isNullOrBlank()) {
            _state.value = st.copy(needLogin = true, loading = false)
            return
        }
        _state.value = st.copy(loading = true, error = null, needLogin = false)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                loadAll(cookie)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }
    }

    /**
     * 三个接口串行拉。任何一路失败都不清空已有数据 —— 早八在图书馆门口
     * 网络抽风时，宁可显示上一次的空位数，也不要整个页面变空。
     */
    private fun loadAll(cookie: String) {
        var error: String? = null
        var needLogin = false

        // 1) 楼层/区域
        val menuResp = SeatApi.get("seatMenu", cookie)
        val menu = SeatApi.parseSeatMenu(menuResp.resp.body)
        if (menu == null) {
            // 302 = 权限不足（cookie 失效）
            val code = SeatApi.code(menuResp.resp.body)
            if (code == 302) needLogin = true else error = "楼层数据解析失败"
        }

        // 2) 全馆
        val stResp = SeatApi.get("seatDevice/resvStatus", cookie)
        val pair = SeatApi.parseStatus(stResp.resp.body)

        // 3) 违规
        val punishResp = SeatApi.get("reserve/punishInfo", cookie)
        val punish = SeatApi.parsePunish(punishResp.resp.body)

        // 4) 我的预约（含历史；失败不影响其它卡）
        val resvResp = try { SeatApi.reservations(cookie) } catch (e: Exception) { null }
        val resvAll = resvResp?.let { SeatApi.parseReservations(it.body) }.orEmpty()
        val now = java.time.LocalDateTime.now()

        val floors = menu?.areas.orEmpty().map { a ->
            LibraryState.Floor(
                id = a.id,
                name = a.name,
                total = a.total,
                remain = a.remain,
                areas = a.children.map {
                    LibraryState.Area(it.id, it.name, it.total, it.remain)
                },
            )
        }

        val hasAny = floors.isNotEmpty() || pair != null || resvAll.isNotEmpty()
        _state.value = _state.value.copy(
            floors = if (floors.isNotEmpty()) floors else _state.value.floors,
            available = pair?.first ?: _state.value.available,
            used = pair?.second ?: _state.value.used,
            punishCount = punish.size,
            punishDetail = punish,
            reservations = if (resvAll.isNotEmpty()) resvAll else _state.value.reservations,
            upcoming = resvAll.filter { it.isActive(now) }.sortedBy { it.beginAt },
            loading = false,
            error = if (hasAny) null else (error ?: "接口无数据"),
            needLogin = needLogin,
            fetchedAt = if (hasAny) System.currentTimeMillis() else _state.value.fetchedAt,
        )
    }

    /**
     * 找空桌：按区域拉座位图（`/ic-web/reserve`），再用 [DeskFinder] 聚类。
     *
     * 日期默认取**明天** —— 学校规则 `earliestResvTime=1440`（最早提前 24 小时），
     * 当日约不了，明天才是真正可约的第一天。
     */
    fun loadDesks(roomIds: List<Int>, minDeskSize: Int = 4) {
        if (_state.value.desksLoading || roomIds.isEmpty()) return
        val slot = DeskFinder.TimeSlot(_state.value.slotStart, _state.value.slotEnd)
        val ctx = getApplication<Application>()
        val cookie = Session.loadSeatCookie(ctx)
        if (cookie.isNullOrBlank()) {
            _state.value = _state.value.copy(needLogin = true)
            return
        }
        val date = java.time.LocalDate.now().plusDays(1)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
        _state.value = _state.value.copy(desksLoading = true, desksError = null, desksDate = date)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val all = ArrayList<SeatApi.Seat>()
                var failed = 0
                // 服务端限流：连续打会返回 {"code":1,"message":"请求频繁,请稍后重试"}。
                // 实测间隔 <300ms 必被拒，所以每次之间等 350ms，失败再等 1.2s 重试一次。
                for ((idx, rid) in roomIds.withIndex()) {
                    if (idx > 0) kotlinx.coroutines.delay(350)
                    var body = SeatApi.seatMap(cookie, date, rid).body
                    var list = SeatApi.parseSeats(body)
                    if (list.isEmpty()) {
                        kotlinx.coroutines.delay(1200)
                        body = SeatApi.seatMap(cookie, date, rid).body
                        list = SeatApi.parseSeats(body)
                    }
                    if (list.isEmpty()) failed++ else all.addAll(list)
                }
                lastSeats = all
                val desks = DeskFinder.findFreeDesks(all, minDeskSize, slot)
                _state.value = _state.value.copy(
                    desks = desks,
                    desksLoading = false,
                    desksTotalSeats = all.size,
                    desksFreeSeats = all.count { it.freeDuring(slot.startMin, slot.endMin) },
                    desksError = when {
                        all.isEmpty() -> "没有拿到座位数据（可能还没到可预约日期）"
                        failed > 0 -> "$failed 个区域拉取失败"
                        else -> null
                    },
                    desksFetchedAt = System.currentTimeMillis(),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    desksLoading = false,
                    desksError = "${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }
    }

    /** 改时段：立即按新时段重算（数据已缓存，不重新请求） */
    fun setSlot(startMin: Int, endMin: Int) {
        if (startMin >= endMin) return
        val raw = lastSeats
        _state.value = _state.value.copy(slotStart = startMin, slotEnd = endMin)
        if (raw.isNotEmpty()) recomputeDesks(raw)
    }

    private var lastSeats: List<SeatApi.Seat> = emptyList()

    private fun recomputeDesks(all: List<SeatApi.Seat>) {
        val slot = DeskFinder.TimeSlot(_state.value.slotStart, _state.value.slotEnd)
        val desks = DeskFinder.findFreeDesks(all, 4, slot)
        _state.value = _state.value.copy(
            desks = desks,
            desksTotalSeats = all.size,
            desksFreeSeats = all.count { it.freeDuring(slot.startMin, slot.endMin) },
        )
    }

    /**
     * 预约一个座位 —— 严格三段式（见 docs/11 的写操作规范）：
     *
     * ```
     * prepare  重新拉该区域座位，确认这个座这个时段**现在仍然空着**，并拿到 accNo
     * commit   发一次请求（非幂等，绝不重试）
     * readback 再拉一次，确认这个座这个时段已经变成自己的预约
     * ```
     *
     * readback 不确定时返回「结果未知」，**绝不把未知升级成成功**。
     */
    fun reserveSeat(seat: SeatApi.Seat, slot: DeskFinder.TimeSlot) {
        val st = _state.value
        if (st.reserving) return
        val ctx = getApplication<Application>()
        val cookie = Session.loadSeatCookie(ctx)
        if (cookie.isNullOrBlank()) {
            _state.value = st.copy(needLogin = true)
            return
        }
        val date = java.time.LocalDate.now().plusDays(1)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
        val beginIso = "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)} " +
            "%02d:%02d:00".format(slot.startMin / 60, slot.startMin % 60)
        val endIso = "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)} " +
            "%02d:%02d:00".format(slot.endMin / 60, slot.endMin % 60)

        _state.value = st.copy(reserving = true, reserveMessage = "复查中…", reserveOk = false)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // ---------- prepare：复查这个座还在不在 ----------
                kotlinx.coroutines.delay(400)
                val fresh = SeatApi.parseSeats(SeatApi.seatMap(cookie, date, seat.roomId).body)
                val cur = fresh.firstOrNull { it.devId == seat.devId }
                if (cur == null) {
                    _state.value = _state.value.copy(
                        reserving = false, reserveOk = false,
                        reserveMessage = "复查失败：该座位已从座位表消失",
                    )
                    return@launch
                }
                if (!cur.freeDuring(slot.startMin, slot.endMin)) {
                    _state.value = _state.value.copy(
                        reserving = false, reserveOk = false,
                        reserveMessage = "${seat.name} 在这个时段刚被人约走了",
                    )
                    recomputeDesks(fresh)
                    return@launch
                }
                val accNo = SeatApi.accountAccNo(cookie)
                if (accNo == null) {
                    _state.value = _state.value.copy(
                        reserving = false, reserveOk = false,
                        reserveMessage = "拿不到账号号（accNo），无法提交",
                    )
                    return@launch
                }

                // ---------- commit：只发一次 ----------
                _state.value = _state.value.copy(reserveMessage = "提交中…")
                kotlinx.coroutines.delay(500)
                val resp = SeatApi.reserve(cookie, accNo, seat.devId, beginIso, endIso)
                val code = SeatApi.code(resp.body)
                if (code != 0) {
                    val msg = try {
                        org.json.JSONObject(resp.body).optString("message")
                    } catch (e: Exception) { "code=$code" }
                    _state.value = _state.value.copy(
                        reserving = false, reserveOk = false,
                        reserveMessage = "预约失败：$msg",
                    )
                    return@launch
                }

                // ---------- readback：只读核对 ----------
                kotlinx.coroutines.delay(800)
                val after = SeatApi.parseSeats(SeatApi.seatMap(cookie, date, seat.roomId).body)
                val mine = after.firstOrNull { it.devId == seat.devId }
                val ok = mine != null && !mine.freeDuring(slot.startMin, slot.endMin)
                val refreshed = if (mine != null) {
                    // 顺手刷新本地座位表
                    lastSeats = lastSeats.map { if (it.devId == seat.devId) mine else it }
                    lastSeats
                } else lastSeats
                recomputeDesks(refreshed)
                _state.value = _state.value.copy(
                    reserving = false,
                    reserveOk = ok,
                    reserveMessage = if (ok) {
                        "已约上 ${seat.name}（${slot.label()}），记得提前到场签到"
                    } else {
                        "结果未知：提交返回成功，但回读没看到这笔预约，请到学校页面确认"
                    },
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    reserving = false, reserveOk = false,
                    reserveMessage = "预约异常：${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }
    }

    fun clearReserveMessage() {
        _state.value = _state.value.copy(reserveMessage = null, reserveOk = false)
    }

    fun setCookie(cookie: String) {
        Session.saveSeatCookie(getApplication(), cookie.trim())
        _state.value = _state.value.copy(needLogin = false)
        refresh()
    }
}
