package com.safphere.launcher.data

import org.json.JSONObject

/** 桌面联系人：头像可能是 ""（姓名首字兜底）、"preset:👴"（预设）或文件绝对路径 */
data class Contact(
    val id: Long,
    var name: String,
    var phone: String,
    var shortNum: String = "",
    var avatar: String = "",
    var emergency: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("phone", phone)
        put("short", shortNum)
        put("avatar", avatar)
        put("emergency", emergency)
    }

    /** 桌面卡片展示的号码：优先短号（亲情号更好记） */
    val displayNumber: String get() = shortNum.ifBlank { phone }

    companion object {
        fun fromJson(o: JSONObject): Contact = Contact(
            id = o.optLong("id"),
            name = o.optString("name"),
            phone = o.optString("phone"),
            shortNum = o.optString("short"),
            avatar = o.optString("avatar"),
            emergency = o.optBoolean("emergency", false)
        )
    }
}
