package com.safphere.launcher.reminder

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 健康提醒规则（子女配置，老人只管看到和听到）。
 *
 * 两类触发：
 * - KIND_TIME    定时：每天或每周某几天，到点提醒（吃药/量血压/喝水…）
 * - KIND_WEATHER 看天气：气温过高/过低、下雨下雪时提醒（当天最多提醒一次，防打扰）
 */
data class ReminderRule(
    val id: String,
    var icon: String = "💊",
    var label: String = "",
    var enabled: Boolean = true,
    var kind: Int = KIND_TIME,
    var hour: Int = 8,
    var minute: Int = 0,
    var days: Set<Int> = emptySet(),      // Calendar.DAY_OF_WEEK；空 = 每天
    var weatherKind: Int = W_HOT,         // kind==KIND_WEATHER 时生效
    var threshold: Int = 33,              // 高温/低温阈值（°C）
    var message: String = ""              // 附加提醒语（如"天热血压易波动，记得量血压"）
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(K_ID, id)
        put(K_ICON, icon)
        put(K_LABEL, label)
        put(K_ENABLED, enabled)
        put(K_KIND, kind)
        put(K_HOUR, hour)
        put(K_MINUTE, minute)
        put(K_DAYS, JSONArray(days.toList()))
        put(K_WKIND, weatherKind)
        put(K_THRESHOLD, threshold)
        put(K_MESSAGE, message)
    }

    companion object {
        const val KIND_TIME = 0
        const val KIND_WEATHER = 1

        const val W_HOT = 0
        const val W_COLD = 1
        const val W_RAIN = 2

        private const val K_ID = "id"
        private const val K_ICON = "icon"
        private const val K_LABEL = "label"
        private const val K_ENABLED = "enabled"
        private const val K_KIND = "kind"
        private const val K_HOUR = "hour"
        private const val K_MINUTE = "minute"
        private const val K_DAYS = "days"
        private const val K_WKIND = "wkind"
        private const val K_THRESHOLD = "threshold"
        private const val K_MESSAGE = "message"

        fun fromJson(o: JSONObject): ReminderRule? = runCatching {
            val daysA = o.optJSONArray(K_DAYS) ?: JSONArray()
            ReminderRule(
                id = o.getString(K_ID),
                icon = o.optString(K_ICON, "💊"),
                label = o.optString(K_LABEL),
                enabled = o.optBoolean(K_ENABLED, true),
                kind = o.optInt(K_KIND, KIND_TIME),
                hour = o.optInt(K_HOUR, 8),
                minute = o.optInt(K_MINUTE, 0),
                days = (0 until daysA.length()).map { daysA.getInt(it) }.toSet(),
                weatherKind = o.optInt(K_WKIND, W_HOT),
                threshold = o.optInt(K_THRESHOLD, 33),
                message = o.optString(K_MESSAGE)
            )
        }.getOrNull()?.takeIf { it.label.isNotBlank() }
    }
}
