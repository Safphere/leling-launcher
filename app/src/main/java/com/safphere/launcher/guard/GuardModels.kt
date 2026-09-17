package com.safphere.launcher.guard

import org.json.JSONObject

/**
 * 单个应用的防沉迷规则。
 * dailyLimitMin/sessionLimitMin：0 = 不限。
 * curfewStartMin/EndMin：一天内分钟数（22:00=1320）；null = 无时段限制；支持跨零点（如 1320~360）。
 */
data class GuardRule(
    val pkg: String,
    val label: String = "",
    var enabled: Boolean = true,
    var dailyLimitMin: Int = 0,
    var sessionLimitMin: Int = 0,
    var curfewStartMin: Int? = null,
    var curfewEndMin: Int? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("daily", dailyLimitMin)
        put("session", sessionLimitMin)
        put("cs", curfewStartMin ?: -1)
        put("ce", curfewEndMin ?: -1)
        put("label", label)
    }

    companion object {
        fun fromJson(pkg: String, o: JSONObject): GuardRule = GuardRule(
            pkg = pkg,
            enabled = o.optBoolean("enabled", true),
            dailyLimitMin = o.optInt("daily", 0),
            sessionLimitMin = o.optInt("session", 0),
            curfewStartMin = o.optInt("cs", -1).takeIf { it >= 0 },
            curfewEndMin = o.optInt("ce", -1).takeIf { it >= 0 },
            label = o.optString("label")
        )
    }
}

/** 阻断原因 */
enum class GuardBlockType { CURFEW, DAILY, SESSION }

data class GuardBlock(
    val type: GuardBlockType,
    val pkg: String,
    val label: String
) {
    fun title(): String = when (type) {
        GuardBlockType.CURFEW -> "到了休息时间"
        GuardBlockType.DAILY -> "今天的时间用完啦"
        GuardBlockType.SESSION -> "看了太久啦，休息一下"
    }

    fun message(rule: GuardRule): String = when (type) {
        GuardBlockType.CURFEW -> "现在不是使用「${label}」的时间\n（${fmtMin(rule.curfewStartMin)} ~ ${fmtMin(rule.curfewEndMin)} 不能使用）\n\n去做点别的事吧"
        GuardBlockType.DAILY -> "「${label}」今天已用满 ${rule.dailyLimitMin} 分钟\n\n明天再来吧"
        GuardBlockType.SESSION -> "「${label}」已经连续用了 ${rule.sessionLimitMin} 分钟\n\n起来走走，喝口水"
    }

    private fun fmtMin(m: Int?): String =
        if (m == null) "?" else String.format(java.util.Locale.CHINA, "%02d:%02d", m / 60 % 24, m % 60)
}
