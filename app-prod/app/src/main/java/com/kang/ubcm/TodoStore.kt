package com.kang.ubcm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 待办的本地持久化（docs/21 §6.8）。
 *
 * P2 只做「数据结构 + 排序 + 规则 + 渲染」；完整编辑页在 P7。
 * 编码沿用仓库既有习惯：`optString` 碰到 JSON null 会返回字符串 "null"，所以一律过一层过滤。
 */
object TodoStore {
    private const val PREF = "ubcm_home"
    private const val KEY = "todos_v1"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<TodoItem> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return parse(raw)
    }

    fun save(ctx: Context, list: List<TodoItem>) {
        prefs(ctx).edit().putString(KEY, toJson(list)).apply()
    }

    fun upsert(ctx: Context, item: TodoItem): List<TodoItem> {
        val cur = load(ctx).toMutableList()
        val idx = cur.indexOfFirst { it.id == item.id }
        if (idx >= 0) cur[idx] = item else cur += item
        save(ctx, cur)
        return cur
    }

    /** 标记完成（写入完成时间）。 */
    fun complete(ctx: Context, id: String, atMs: Long = System.currentTimeMillis()): List<TodoItem> {
        val cur = load(ctx).map { if (it.id == id) it.copy(done = true, completedAt = atMs) else it }
        save(ctx, cur)
        return cur
    }

    /** 撤销完成（回待办分段）。 */
    fun restore(ctx: Context, id: String): List<TodoItem> {
        val cur = load(ctx).map { if (it.id == id) it.copy(done = false, completedAt = null) else it }
        save(ctx, cur)
        return cur
    }

    fun clearCompleted(ctx: Context): List<TodoItem> {
        val cur = load(ctx).filterNot { it.done }
        save(ctx, cur)
        return cur
    }

    fun clearAll(ctx: Context): List<TodoItem> {
        save(ctx, emptyList())
        return emptyList()
    }

    fun remove(ctx: Context, id: String): List<TodoItem> {
        val next = load(ctx).filterNot { it.id == id }
        save(ctx, next)
        return next
    }

    fun toJson(list: List<TodoItem>): String {
        val arr = JSONArray()
        list.forEach { t ->
            val subs = JSONArray()
            t.subs.forEach { s ->
                subs.put(JSONObject().put("id", s.id).put("text", s.text).put("done", s.done))
            }
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("title", t.title)
                    .put("note", t.note ?: JSONObject.NULL)
                    .put("dueAt", t.dueAt ?: JSONObject.NULL)
                    .put("done", t.done)
                    .put("createdAt", t.createdAt)
                    .put("completedAt", t.completedAt ?: JSONObject.NULL)
                    .put("subs", subs)
            )
        }
        return arr.toString()
    }

    fun parse(raw: String?): List<TodoItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<TodoItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title").ifBlank { continue }
                val subsArr = o.optJSONArray("subs") ?: JSONArray()
                val subs = mutableListOf<SubTodo>()
                for (j in 0 until subsArr.length()) {
                    val so = subsArr.optJSONObject(j) ?: continue
                    subs += SubTodo(
                        id = so.optString("id").ifBlank { "s$j" },
                        text = so.optString("text").takeIf { it.isNotBlank() && it != "null" } ?: continue,
                        done = so.optBoolean("done", false),
                    )
                }
                out += TodoItem(
                    id = o.optString("id").ifBlank { "t$i" },
                    title = title,
                    note = o.optString("note").takeIf { it.isNotBlank() && it != "null" },
                    dueAt = if (o.isNull("dueAt")) null else o.optLong("dueAt", 0L).takeIf { it > 0 },
                    done = o.optBoolean("done", false),
                    subs = subs,
                    createdAt = o.optLong("createdAt", 0L),
                    completedAt = if (o.isNull("completedAt")) null else o.optLong("completedAt", 0L).takeIf { it > 0 },
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
