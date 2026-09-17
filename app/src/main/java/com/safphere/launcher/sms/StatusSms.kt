package com.safphere.launcher.sms

import android.content.Context
import android.os.BatteryManager
import android.telephony.SmsManager
import com.safphere.launcher.data.Prefs
import com.safphere.launcher.flow.FlowMonitor
import com.safphere.launcher.reminder.ReminderEngine
import com.safphere.launcher.sim.SimMonitor

/**
 * 远程状态查询（零服务器）：子女向父母手机发送短信 "LLZT"，
 * 手机自动回一条状态短信：电量 / 电话卡 / 流量 / 今日提醒确认情况。
 *
 * 安全设计：
 * - 只回复「通讯录里的联系人」（号码后 8 位匹配），陌生人探询一律静默忽略；
 * - 总开关（设置页"远程状态查询"）关闭时完全禁用；
 * - 仅精确匹配 "LLZT"（忽略大小写/首尾空白），避免普通聊天误触发；
 * - 响应节流：同一号码 60 秒内只回一次（防轰炸）。
 */
object StatusSms {

    private const val TAG = "StatusSms"

    private const val CMD = "LLZT"
    private var lastReplyAt = 0L
    private var lastReplyTo = ""

    /** 返回 true 表示该短信是 LLZT 查询（已处理，调用方不再走流量解析） */
    fun maybeRespond(context: Context, sender: String, body: String): Boolean {
        if (!body.trim().equals(CMD, ignoreCase = true)) return false
        val now = System.currentTimeMillis()
        if (now - lastReplyAt < 60_000 && sender == lastReplyTo) {
            android.util.Log.w(TAG, "LLZT throttled from $sender")
            return true
        }
        lastReplyAt = now
        lastReplyTo = sender

        if (!Prefs.statusSmsEnabled) {
            android.util.Log.w(TAG, "LLZT ignored: master switch off")
            return true
        }
        if (!isTrustedContact(context, sender)) {
            android.util.Log.w(TAG, "LLZT ignored: sender $sender not in contacts")
            return true   // 陌生人：静默忽略
        }

        val text = buildStatus(context)
        val result = runCatching {
            SmsManager.getDefault().sendTextMessage(sender, null, text, null, null)
        }
        android.util.Log.w(TAG, "LLZT reply to $sender ok=${result.isSuccess} err=${result.exceptionOrNull()?.message}")
        return true
    }

    /** 信任判定：号码（仅数字、后 8 位）命中通讯录任一联系人即视为家人/子女 */
    internal fun isTrustedContact(context: Context, sender: String): Boolean {
        val s = sender.filter { it.isDigit() }.takeLast(8)
        if (s.isEmpty()) return false
        return Prefs.contacts().any {
            it.phone.filter { c -> c.isDigit() }.takeLast(8) == s
        }
    }

    internal fun buildStatus(context: Context): String {
        val sb = StringBuilder("【乐龄桌面】状态报告\n")
        // 电量
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 1..100 } ?: -1
        val charging = bm?.isCharging == true
        sb.append(if (pct > 0) "电量 $pct%${if (charging) "（充电中）" else ""}\n" else "电量 未知\n")
        // 电话卡
        sb.append("电话卡 " + when (SimMonitor.isSimAbsent(context)) {
            true -> "⚠️ 未检测到，请检查"
            false -> "正常"
            null -> "未知"
        } + "\n")
        // 流量
        sb.append("流量 " + when {
            !Prefs.flowEnabled -> "未开启管理"
            Prefs.flowDataOffByUs -> "已不足，本月暂停移动数据（月结自动恢复）"
            Prefs.flowRemainingMb >= 0 -> "剩余 ${FlowMonitor.fmtMb(Prefs.flowRemainingMb)}"
            else -> "未查询"
        } + "\n")
        // 今日提醒
        sb.append(todayReminderLine())
        return sb.toString()
    }

    /** 今日提醒确认情况：启用的定时提醒 N 项，其中已点「知道了」M 项 */
    internal fun todayReminderLine(): String {
        val rules = Prefs.reminderRules().filter {
            it.enabled && it.kind == com.safphere.launcher.reminder.ReminderRule.KIND_TIME
        }
        if (rules.isEmpty()) return "今日无定时提醒"
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
            .format(java.util.Date())
        val acked = rules.count { Prefs.reminderAckedAt(it.id) == today }
        return if (acked == rules.size) "今日 ${rules.size} 项提醒全部确认 ✓"
        else "今日提醒已确认 $acked/${rules.size} 项"
    }
}
