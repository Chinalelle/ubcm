package com.kang.ubcm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 课表本地缓存。
 *
 * 「本地优先」的含义：联网时从教务拉一次，落盘；之后每次打开首页**先读盘**（毫秒级、离线可用），
 * 再后台刷新。这样早八出门没网也能看到「下一节课是什么、在哪」。
 */
object TimetableStore {

    private const val FILE = "timetable.json"

    data class Cache(
        val sessions: List<CourseSession>,
        val slots: List<PeriodSlot>,
        val fetchedAt: Long,
        val semesterHint: String = "",
    ) {
        val isEmpty: Boolean get() = sessions.isEmpty()
        /** 缓存超过这个时间就当"陈旧"，但仍会展示，只是提示用户刷新 */
        fun ageMs(now: Long = System.currentTimeMillis()) = now - fetchedAt
    }

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    fun load(ctx: Context): Cache? = try {
        val f = file(ctx)
        if (!f.exists()) null
        else decode(f.readText())
    } catch (e: Exception) {
        null
    }

    fun save(ctx: Context, cache: Cache) {
        try {
            file(ctx).writeText(encode(cache))
        } catch (e: Exception) {
            // 落盘失败不影响本次展示，下次联网再存
        }
    }

    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (e: Exception) { }
    }

    /**
     * 从教务 `loadSkzccx` 的原始响应建缓存。
     *
     * 两种字段来源：
     *  - `lstKb`         → 排课（kcmc 课名 / kcsj 时间编码 / kkzcmx 周次 / jx0601mc 教室）
     *  - `lstJx0415zb`   → 大节起止（kssj/jssj + jc1/jc2），用来推小节时间
     */
    fun fromJwResponse(raw: String, fetchedAt: Long = System.currentTimeMillis()): Cache? {
        return try {
            val sessions = JwApi.parseCoursesJson(raw).mapNotNull { row ->
                if (row.dayOfWeek !in 1..7 || row.periods.isEmpty() || row.weeks.isEmpty()) null
                else CourseSession(
                    name = row.name.ifBlank { "（未命名课程）" },
                    teacher = row.teacher,
                    location = row.location,
                    dayOfWeek = row.dayOfWeek,
                    startPeriod = row.periods.first(),
                    endPeriod = row.periods.last(),
                    weeks = row.weeks.distinct().sorted(),
                    className = row.nature,
                )
            }
            if (sessions.isEmpty()) null
            else Cache(
                sessions = sessions,
                slots = Timetable.slotsFromPeriods(JwApi.parsePeriods(raw))
                    .ifEmpty { Timetable.FALLBACK_SLOTS },
                fetchedAt = fetchedAt,
            )
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 序列化 ----------

    private fun encode(c: Cache): String {
        val o = JSONObject()
        o.put("fetchedAt", c.fetchedAt)
        if (c.semesterHint.isNotBlank()) o.put("semesterHint", c.semesterHint)
        o.put("slots", JSONArray().apply {
            c.slots.forEach { s ->
                put(JSONObject().put("p", s.period).put("s", s.startMin).put("e", s.endMin))
            }
        })
        o.put("sessions", JSONArray().apply {
            c.sessions.forEach { s ->
                put(
                    JSONObject()
                        .put("n", s.name)
                        .put("t", s.teacher)
                        .put("l", s.location)
                        .put("d", s.dayOfWeek)
                        .put("a", s.startPeriod)
                        .put("b", s.endPeriod)
                        .put("w", JSONArray(s.weeks))
                        .put("c", s.className)
                )
            }
        })
        return o.toString()
    }

    private fun decode(text: String): Cache? = try {
        val o = JSONObject(text)
        val slotsArr = o.optJSONArray("slots") ?: JSONArray()
        val slots = (0 until slotsArr.length()).mapNotNull { i ->
            val s = slotsArr.optJSONObject(i) ?: return@mapNotNull null
            PeriodSlot(s.optInt("p"), s.optInt("s"), s.optInt("e"))
        }
        val sessArr = o.optJSONArray("sessions") ?: JSONArray()
        val sessions = (0 until sessArr.length()).mapNotNull { i ->
            val s = sessArr.optJSONObject(i) ?: return@mapNotNull null
            val w = s.optJSONArray("w") ?: JSONArray()
            CourseSession(
                // cleanFieldText：老缓存里可能已经存了字符串 "null"（历史上 optString 的坑），
                // 这里再清一遍，用户不用重新拉课表就不会看到「教室 null」。
                name = cleanFieldText(s.optString("n")),
                teacher = cleanFieldText(s.optString("t")),
                location = cleanFieldText(s.optString("l")),
                dayOfWeek = s.optInt("d"),
                startPeriod = s.optInt("a"),
                endPeriod = s.optInt("b"),
                weeks = (0 until w.length()).mapNotNull { w.optInt(it).takeIf { v -> v > 0 } },
                className = cleanFieldText(s.optString("c")),
            )
        }
        if (sessions.isEmpty()) null
        else Cache(
            sessions = sessions,
            slots = slots.ifEmpty { Timetable.FALLBACK_SLOTS },
            fetchedAt = o.optLong("fetchedAt", 0L),
            semesterHint = o.optString("semesterHint"),
        )
    } catch (e: Exception) {
        null
    }
}
