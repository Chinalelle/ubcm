package com.kang.ubcm

/**
 * 身份信息的**本地整理**（纯逻辑，可 JVM 单测）。
 *
 * 数据来源固定：姓名/学号来自 JWT，班级从课表 `className` 反推；
 * 学院/专业目前**没有可信来源**（门户没有对应接口、JWT 也没有），按设计显示 `—` 而不是编造。
 */
object Profile {

    /** 缺失字段统一显示这个，保证行不塌。 */
    const val EMPTY = "—"

    /**
     * 课表里的班级名 → 人看的班级名。
     *
     * 实测形如 `岐黄班202601`（前 4 位入学年 + 后 2 位班号），转成 `2026 级 1 班`。
     * 解析不出来就原样返回；空值返回 null。
     */
    fun classLabel(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || t == "null") return null
        val m = Regex("""^(.*?)(\d{4})(\d{2})$""").find(t) ?: return t
        val prefix = m.groupValues[1]
        val year = m.groupValues[2]
        val no = m.groupValues[3].toIntOrNull() ?: return t
        val base = "$year 级 $no 班"
        return if (prefix.isBlank()) base else "$base（$prefix）"
    }

    /** 任一字段为空就补 `—`。 */
    fun orEmpty(raw: String?): String {
        val t = raw?.trim().orEmpty()
        return if (t.isEmpty() || t == "null") EMPTY else t
    }

    /** 凭证状态的人话（`5/6 有效`）。 */
    fun credentialText(ok: Int, total: Int): String =
        if (total <= 0) EMPTY else "$ok/$total 有效"
}
