package com.kang.ubcm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 首页小组件布局的持久化（docs/21 §4.7）。
 *
 * 只做「读盘 / 写盘 / 恢复默认」，布局合法性由 [WidgetLayout] 的纯函数保证：
 * 盘上的数据一旦被手改坏（重叠、越界），[load] 直接回退到默认布局，不让首页崩。
 */
object HomeWidgetStore {
    private const val PREF = "ubcm_home"
    private const val KEY = "home_widgets_v1"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<HomeWidget> {
        val raw = prefs(ctx).getString(KEY, null) ?: return WidgetLayout.defaultLayout()
        return parse(raw) ?: WidgetLayout.defaultLayout()
    }

    fun save(ctx: Context, list: List<HomeWidget>) {
        prefs(ctx).edit().putString(KEY, toJson(list)).apply()
    }

    /** 设置页「恢复默认小组件」用。 */
    fun reset(ctx: Context): List<HomeWidget> {
        val d = WidgetLayout.defaultLayout()
        save(ctx, d)
        return d
    }

    fun toJson(list: List<HomeWidget>): String {
        val arr = JSONArray()
        list.forEach { w ->
            arr.put(
                JSONObject()
                    .put("id", w.id)
                    .put("type", w.type.name)
                    .put("col", w.col)
                    .put("row", w.row)
            )
        }
        return arr.toString()
    }

    fun parse(raw: String?): List<HomeWidget>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<HomeWidget>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: return null
                val type = WidgetType.ofOrDefault(o.optString("type")) ?: return null
                out += HomeWidget(
                    id = o.optString("id").ifBlank { "w_${type.name.lowercase()}_$i" },
                    type = type,
                    col = o.optInt("col", -1),
                    row = o.optInt("row", -1),
                )
            }
            if (WidgetLayout.isValid(out)) out else null
        } catch (e: Exception) {
            null
        }
    }
}
