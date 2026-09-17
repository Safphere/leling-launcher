package com.safphere.launcher.ai

import android.content.Context
import android.os.BatteryManager
import com.safphere.launcher.data.Contact
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 离线本地技能：没网/没配Key也能答的高频问题，
 * 以及「打电话给某某」的联系人匹配。
 */
object LocalSkills {

    sealed class Answer {
        data class Text(val text: String) : Answer()
        data class Call(val contact: Contact, val text: String) : Answer()
    }

    fun match(context: Context, query: String, contacts: List<Contact>): Answer? {
        val q = query.trim()
        if (q.isEmpty()) return null

        // 打电话给 X / 呼叫 X / 拨 X
        if (q.contains("电话") || q.contains("拨打") || q.contains("呼叫") || q.contains("打给")) {
            contacts.firstOrNull { it.name.isNotBlank() && q.contains(it.name) }?.let { c ->
                return Answer.Call(c, "好的，正在为您呼叫${c.name}")
            }
        }

        val now = Date()
        // 时间
        if (q.contains("几点") || q.contains("时间") || q.contains("时候") || q.contains("time")) {
            val t = SimpleDateFormat("HH点mm分", Locale.CHINA).format(now)
            return Answer.Text("现在是$t。")
        }
        // 日期/星期
        if (q.contains("几号") || q.contains("日期") || q.contains("星期") || q.contains("礼拜")
            || q.contains("date") || q.contains("今天")
        ) {
            val d = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now)
            return Answer.Text("今天是$d。")
        }
        // 电量
        if (q.contains("电量") || q.contains("电池") || q.contains("battery")) {
            val p = battery(context)
            val extra = if (p <= 20) "电量不多了，记得充电。" else "电量充足，放心用。"
            return Answer.Text("手机电量百分之$p，$extra")
        }
        return null
    }

    fun battery(context: Context): Int = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 1..100 } ?: 100
    }.getOrDefault(100)
}
