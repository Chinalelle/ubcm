package com.kang.ubcm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户自建日程的持久化（docs/21 §5.6）。
 *
 * 课表派生的日程**不在这里**——它每次实时算，存了反而会与最新课表打架。
 * 解析失败一律返回空列表：日程是锦上添花，不能因为它让首页起不来。
 */
object AgendaStore {
    private const val PREF = "ubcm_home"
    private const val KEY = "agenda_user_v1"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<AgendaItem> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return parse(raw)
    }

    fun save(ctx: Context, list: List<AgendaItem>) {
        prefs(ctx).edit().putString(KEY, toJson(list)).apply()
    }

    fun add(ctx: Context, item: AgendaItem): List<AgendaItem> {
        val next = (load(ctx) + item).sortedBy { it.startMillis }
        save(ctx, next)
        return next
    }

    fun remove(ctx: Context, id: String): List<AgendaItem> {
        val next = load(ctx).filterNot { it.id == id }
        save(ctx, next)
        return next
    }

    fun toJson(list: List<AgendaItem>): String {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("title", a.title)
                    .put("start", a.startMillis)
                    .put("end", a.endMillis ?: JSONObject.NULL)
                    .put("location", a.location ?: JSONObject.NULL)
                    .put("note", a.note ?: JSONObject.NULL)
                    .put("source", a.source.name)
            )
        }
        return arr.toString()
    }

    fun parse(raw: String?): List<AgendaItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<AgendaItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title").ifBlank { continue }
                out += AgendaItem(
                    id = o.optString("id").ifBlank { "user-$i" },
                    title = title,
                    startMillis = o.optLong("start", 0L),
                    endMillis = if (o.isNull("end")) null else o.optLong("end", 0L).takeIf { it > 0 },
                    location = o.optString("location").takeIf { it.isNotBlank() && it != "null" },
                    note = o.optString("note").takeIf { it.isNotBlank() && it != "null" },
                    source = AgendaSource.USER,
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
