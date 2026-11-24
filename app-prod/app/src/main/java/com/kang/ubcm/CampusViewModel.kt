package com.kang.ubcm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 校园页数据：余额、今日消费、近 30 天流水（折线 + 消费分布都从它算）。
 *
 * 取数复用一卡通已有链路（CASTGC → servicehall 会话 → thirdQuerSelfTrade），
 * **不改网络层**。凭证缺失/过期时退回示例数据，并在 UI 上明确标注（同 P4 的做法）。
 */
class CampusViewModel(app: Application) : AndroidViewModel(app) {

    data class State(
        val balanceYuan: String? = null,
        val todaySpendYuan: String? = null,
        val spends: List<SpendCategory.Spend> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        /** true = 展示的是页内示例数据（凭证过期） */
        val isDemo: Boolean = false,
        val fetchedAt: Long = 0L,
    ) {
        val hasSpends: Boolean get() = spends.isNotEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun refresh(force: Boolean = true) {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = runCatching { load() }.getOrElse { e ->
                null to "无法取到一卡通数据：${e.javaClass.simpleName}: ${e.message}"
            }
            val (ok, err) = loaded
            if (ok != null) {
                _state.value = ok.copy(loading = false)
            } else {
                _state.value = demo().copy(
                    loading = false,
                    error = err ?: "凭证不可用",
                    isDemo = true,
                )
            }
        }
    }

    /** 真实数据：余额取最新一条流水的 balance，今日消费与近 30 天从流水中聚合。 */
    private fun load(): Pair<State?, String?> {
        val ctx = getApplication<Application>()
        val castgc = Session.loadCastgc(ctx) ?: return null to "缺 CASTGC，无法登录一卡通"
        val idserial = Session.parse(Session.loadToken(ctx))?.studentNo
            ?: return null to "缺门户学号"

        var session = Session.loadEcardSession(ctx)
        var openid = Session.loadEcardOpenid(ctx)
        if (session == null || openid == null) {
            val lr = EcardApi.login(castgc)
            if (!lr.ok || lr.session == null || lr.openid == null) {
                return null to "一卡通单点失败（CASTGC 可能过期）"
            }
            session = lr.session
            openid = lr.openid
            Session.saveEcard(ctx, session, openid)
        }

        val end = LocalDate.now()
        val start = end.minusDays(29)
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        var page = EcardApi.parseTrades(
            EcardApi.unwrap(
                EcardApi.trades(session, openid, idserial, fmt.format(start), fmt.format(end), size = 100).body
            )
        )
        if (page == null) {
            Session.clearEcard(ctx)
            val lr = EcardApi.login(castgc)
            if (lr.ok && lr.session != null && lr.openid != null) {
                Session.saveEcard(ctx, lr.session, lr.openid)
                page = EcardApi.parseTrades(
                    EcardApi.unwrap(
                        EcardApi.trades(lr.session, lr.openid, idserial, fmt.format(start), fmt.format(end), size = 100).body
                    )
                )
            }
        }
        if (page == null) return null to "流水解析失败"

        val zone = ZoneId.of("Asia/Shanghai")
        val spends = page.trades.map { t ->
            SpendCategory.Spend(
                merchant = t.merchant,
                amountYuan = SpendCategory.spendYuan(t.amountYuan),
                timeMs = t.timeMs,
            )
        }

        val today = Instant.now().atZone(zone).toLocalDate()
        val todaySpend = spends.filter {
            Instant.ofEpochMilli(it.timeMs).atZone(zone).toLocalDate() == today && it.amountYuan > 0
        }.sumOf { it.amountYuan }

        return State(
            balanceYuan = page.trades.firstOrNull()?.balanceYuan,
            todaySpendYuan = "%.2f".format(todaySpend),
            spends = spends,
            loading = false,
            isDemo = false,
            fetchedAt = System.currentTimeMillis(),
        ) to null
    }

    /**
     * 示例数据：凭证过期时让页面结构可看。
     * 金额比例对齐 docs/26 的示例（东院食堂一层 / 西院食堂一层 / 西院超市 / 开水机 / 西院小卖部 / 其他）。
     */
    private fun demo(): State {
        val now = System.currentTimeMillis()
        val day = 24 * 3600 * 1000L
        val plan = listOf(
            Triple("良乡东院食堂一层基本伙餐厅", 38.0, 0),
            Triple("良乡西院食堂一层基本伙餐厅", 26.0, 1),
            Triple("良乡西院超市", 18.0, 2),
            Triple("良乡校区开水机", 9.0, 3),
            Triple("良乡西院小卖部", 6.0, 4),
            Triple("文印中心打印", 2.0, 5),
            Triple("良乡东院食堂二层风味餐厅", 12.0, 6),
            Triple("良乡东院食堂一层基本伙餐厅", 9.5, 7),
            Triple("良乡西院超市", 7.0, 9),
            Triple("良乡校区开水机", 3.0, 12),
            Triple("良乡西院食堂一层基本伙餐厅", 15.0, 15),
            Triple("良乡东院食堂一层基本伙餐厅", 20.0, 20),
            Triple("良乡西院小卖部", 4.0, 24),
        )
        val spends = plan.map { (m, v, backDay) ->
            SpendCategory.Spend(m, v, now - backDay * day)
        }
        val today = spends.filter { it.timeMs >= now - day && it.amountYuan > 0 }.sumOf { it.amountYuan }
        return State(
            balanceYuan = "132.50",
            todaySpendYuan = "%.2f".format(today),
            spends = spends,
            isDemo = true,
            fetchedAt = System.currentTimeMillis(),
        )
    }
}
