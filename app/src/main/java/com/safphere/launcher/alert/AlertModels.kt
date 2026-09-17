package com.safphere.launcher.alert

import org.json.JSONObject

/**
 * 通用警报模型：一切需要"老人可见、可听、可操作"的提醒都抽象为 Alert。
 *
 * type   : 来源类型（SIM/FLOW/EARTHQUAKE/FLOOD/WEATHER/HEALTH/CUSTOM...，自定义字符串）
 * action : 用户点击主按钮后执行的动作
 *          ACTION_NONE   仅"我知道了"
 *          ACTION_REBOOT 弹重启确认并执行一键重启（SIM松动等场景）
 * level  : LEVEL_HIGH 弹全屏警报；LEVEL_NORMAL 走高优先级通知
 */
data class Alert(
    val type: String,
    val title: String,
    val message: String,
    val iconEmoji: String = "⚠️",
    val speakText: String? = null,
    val action: String = ACTION_NONE,
    val actionLabel: String = "我知道了",
    val actionData: String = "",
    val level: String = LEVEL_NORMAL,
    val source: String = "local"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(K_TYPE, type)
        put(K_TITLE, title)
        put(K_MESSAGE, message)
        put(K_ICON, iconEmoji)
        put(K_SPEAK, speakText ?: "")
        put(K_ACTION, action)
        put(K_ACTION_LABEL, actionLabel)
        put(K_ACTION_DATA, actionData)
        put(K_LEVEL, level)
        put(K_SOURCE, source)
    }

    companion object {
        const val ACTION_NONE = "NONE"
        const val ACTION_REBOOT = "REBOOT"
        const val ACTION_SNOOZE = "SNOOZE"   // 显示"稍后再提醒"副按钮（健康提醒用）
        const val LEVEL_HIGH = "HIGH"
        const val LEVEL_NORMAL = "NORMAL"

        const val K_ALERT = "alert_json"
        const val K_TYPE = "type"
        const val K_TITLE = "title"
        const val K_MESSAGE = "message"
        const val K_ICON = "icon"
        const val K_SPEAK = "speak"
        const val K_ACTION = "action"
        const val K_ACTION_LABEL = "action_label"
        const val K_ACTION_DATA = "action_data"
        const val K_LEVEL = "level"
        const val K_SOURCE = "source"

        fun fromJson(json: String): Alert? = runCatching {
            val o = JSONObject(json)
            Alert(
                type = o.optString(K_TYPE, "CUSTOM"),
                title = o.optString(K_TITLE),
                message = o.optString(K_MESSAGE),
                iconEmoji = o.optString(K_ICON, "⚠️"),
                speakText = o.optString(K_SPEAK).ifBlank { null },
                action = o.optString(K_ACTION, ACTION_NONE),
                actionLabel = o.optString(K_ACTION_LABEL, "我知道了"),
                actionData = o.optString(K_ACTION_DATA),
                level = o.optString(K_LEVEL, LEVEL_NORMAL),
                source = o.optString(K_SOURCE, "local")
            )
        }.getOrNull()?.takeIf { it.title.isNotBlank() }
    }
}
