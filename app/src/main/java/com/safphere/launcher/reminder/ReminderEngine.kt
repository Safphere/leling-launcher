package com.safphere.launcher.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.safphere.launcher.alert.Alert
import com.safphere.launcher.alert.AlertManager
import com.safphere.launcher.data.Prefs
import com.safphere.launcher.weather.WeatherFetcher
import java.util.Calendar
import java.util.Locale

/**
 * 提醒引擎：定时闹钟调度 + 天气条件评估，统一通过 AlertManager 展示
 * （前台全屏警报页、后台/锁屏全屏通知，均带语音播报）。
 *
 * 可靠性设计：
 * - 每条定时规则只排"下一次"，触发后自动排下一次（改动/开关立即重排）；
 * - 开机、每日自检、App启动 三处兜底重排（闹钟可能被系统省电策略清除）；
 * - SCHEDULE_EXACT_ALARM 不可用时自动降级为非精确闹钟（分钟级误差，吃药场景可接受）；
 * - 天气类提醒每条每天最多触发一次（Prefs 记录触发日期）。
 */
object ReminderEngine {

    const val ACTION_FIRE = "com.safphere.launcher.action.REMINDER_FIRE"
    const val ACTION_SNOOZE_FIRE = "com.safphere.launcher.action.REMINDER_SNOOZE"

    private const val TAG = "ReminderEngine"
    private const val SNOOZE_MIN = 10

    // ---------- 排期 ----------

    /** 排某条规则的下一次定时触发（仅 KIND_TIME） */
    fun scheduleNext(context: Context, rule: ReminderRule) {
        if (rule.kind != ReminderRule.KIND_TIME) return
        val trigger = nextTriggerMillis(rule) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pi(context, rule.id, snooze = false)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            Log.w(TAG, "schedule inexact id=${rule.id} at=$trigger")
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            Log.w(TAG, "schedule exact id=${rule.id} at=$trigger")
        }
    }

    /** 重排全部启用的定时规则（开机/每日自检/App启动调用） */
    fun scheduleAll(context: Context) {
        if (!Prefs.reminderEnabled) return
        Prefs.reminderRules().filter { it.enabled }.forEach { scheduleNext(context, it) }
    }

    fun cancel(context: Context, ruleId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(pi(context, ruleId, snooze = false)) }
        runCatching { am.cancel(pi(context, ruleId, snooze = true)) }
    }

    /** 关闭总开关：撤销全部闹钟 */
    fun cancelAll(context: Context) {
        Prefs.reminderRules().forEach { cancel(context, it.id) }
    }

    /** 下一次触发时刻：今天起 7 天内第一个"时间已过+星期匹配"之外的时刻 */
    private fun nextTriggerMillis(rule: ReminderRule): Long? {
        val now = Calendar.getInstance()
        return (0..7L).firstNotNullOfOrNull { offset ->
            val c = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset.toInt())
                set(Calendar.HOUR_OF_DAY, rule.hour)
                set(Calendar.MINUTE, rule.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val dayOk = rule.days.isEmpty() || rule.days.contains(c.get(Calendar.DAY_OF_WEEK))
            if (dayOk && c.after(now)) c.timeInMillis else null
        }
    }

    // ---------- 触发 ----------

    fun onFire(context: Context, ruleId: String, isSnooze: Boolean) {
        if (!Prefs.reminderEnabled) {
            Log.w(TAG, "fire skipped (master off) id=$ruleId")
            return
        }
        val rule = Prefs.reminderRules().firstOrNull { it.id == ruleId } ?: run {
            Log.w(TAG, "fire skipped (rule gone) id=$ruleId")
            return
        }
        if (!rule.enabled) {
            Log.w(TAG, "fire skipped (rule off) id=$ruleId")
            return
        }
        // 勿扰时段：定时提醒推迟到时段结束（一次性闹钟），不吵醒老人；非 snooze 需保留日常排期
        if (inQuietWindow() && !isSnooze) {
            val minutes = minutesToQuietEnd()
            Log.w(TAG, "quiet window: defer id=$ruleId for $minutes min")
            if (minutes > 0) snooze(context, ruleId, minutes)
            scheduleNext(context, rule)   // 日常下一次照常排（勿扰内的这次视为已推迟）
            return
        }
        AlertManager.show(context, toAlert(rule))
        Log.w(TAG, "fired id=$ruleId snooze=$isSnooze label=${rule.label}")
        if (!isSnooze) scheduleNext(context, rule)
    }

    /** 子女端状态报告用：确认记录（点「知道了」时调用） */
    fun acknowledge(ruleId: String) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
            .format(java.util.Date())
        Prefs.setReminderAcked(ruleId, today)
        Log.w(TAG, "acked id=$ruleId on $today")
    }

    /** 当前是否处于勿扰时段（未启用/起止相同=不启用） */
    fun inQuietWindow(now: Calendar = Calendar.getInstance()): Boolean {
        if (!Prefs.quietEnabled) return false
        val start = Prefs.quietStartMin
        val end = Prefs.quietEndMin
        if (start == end) return false
        val cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (start < end) cur in start until end
        else cur >= start || cur < end   // 跨零点（22:00~07:00）
    }

    /** 距勿扰结束还有多少分钟（跨零点安全） */
    private fun minutesToQuietEnd(now: Calendar = Calendar.getInstance()): Int {
        val end = Prefs.quietEndMin
        val cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return ((end - cur + 1440) % 1440).coerceAtLeast(1)
    }

    /** "稍后再提醒"：N 分钟后补一次（一次性，不重排日常计划） */
    fun snooze(context: Context, ruleId: String, minutes: Int = SNOOZE_MIN) {
        val rule = Prefs.reminderRules().firstOrNull { it.id == ruleId } ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + minutes * 60_000L
        val pi = pi(context, rule.id, snooze = true)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        Log.w(TAG, "snoozed id=$ruleId until=$at")
    }

    private fun toAlert(rule: ReminderRule) = Alert(
        type = "HEALTH",
        title = rule.label,
        message = rule.message.ifBlank { defaultTip(rule) },
        iconEmoji = rule.icon,
        speakText = "${timeText(rule)}到了：${rule.label}。" +
            (rule.message.ifBlank { defaultTip(rule) }),
        action = Alert.ACTION_SNOOZE,
        actionLabel = "知道了",
        actionData = rule.id,
        level = Alert.LEVEL_HIGH,
        source = "local"    // 不显示"来源"行（老人无需关心）
    )

    private fun timeText(rule: ReminderRule): String =
        if (rule.kind == ReminderRule.KIND_WEATHER) "天气提醒"
        else String.format(Locale.CHINA, "%02d:%02d", rule.hour, rule.minute)

    private fun defaultTip(rule: ReminderRule): String = when {
        rule.kind == ReminderRule.KIND_WEATHER -> when (rule.weatherKind) {
            ReminderRule.W_HOT -> "天气炎热，多喝水、避免中午外出"
            ReminderRule.W_COLD -> "天气寒冷，添衣保暖，外出防滑"
            else -> "外面下雨下雪，出门带伞，地滑慢走"
        }
        else -> "记得按时完成哦"
    }

    // ---------- 天气触发 ----------

    /** 天气刷新成功后评估（高温/低温/雨雪），每条每天最多一次；勿扰时段不弹（次日仍命中会再弹） */
    fun evaluateWeather(context: Context) {
        if (!Prefs.reminderEnabled) return
        if (inQuietWindow()) return   // 夜间不做天气关怀打扰
        val w = WeatherFetcher.cached(context) ?: return
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(java.util.Date())
        var changed = false
        for (rule in Prefs.reminderRules()) {
            if (!rule.enabled || rule.kind != ReminderRule.KIND_WEATHER) continue
            val hit = when (rule.weatherKind) {
                ReminderRule.W_HOT -> w.temp >= rule.threshold
                ReminderRule.W_COLD -> w.temp <= rule.threshold
                else -> w.desc.contains("雨") || w.desc.contains("雪")
            }
            if (!hit) continue
            if (Prefs.reminderFiredAt(rule.id) == today) continue
            Prefs.setReminderFiredAt(rule.id, today)
            changed = true
            AlertManager.show(context, toAlert(rule))
            Log.w(TAG, "weather fired id=${rule.id} temp=${w.temp} desc=${w.desc}")
        }
        if (changed) Prefs.pruneReminderFired(today)
    }

    // ---------- 文案 ----------

    /** 管理列表/设置页的一行摘要 */
    fun summary(rule: ReminderRule): String = when {
        rule.kind == ReminderRule.KIND_WEATHER -> when (rule.weatherKind) {
            ReminderRule.W_HOT -> "高温（≥${rule.threshold}°C）时提醒"
            ReminderRule.W_COLD -> "低温（≤${rule.threshold}°C）时提醒"
            else -> "下雨/下雪时提醒"
        }
        rule.days.isEmpty() -> String.format(Locale.CHINA, "每天 %02d:%02d", rule.hour, rule.minute)
        else -> {
            val names = mapOf(
                Calendar.SUNDAY to "日", Calendar.MONDAY to "一", Calendar.TUESDAY to "二",
                Calendar.WEDNESDAY to "三", Calendar.THURSDAY to "四",
                Calendar.FRIDAY to "五", Calendar.SATURDAY to "六"
            )
            "每周 " + rule.days.sorted().joinToString("、") { "周${names[it] ?: "?"}" } +
                String.format(Locale.CHINA, " %02d:%02d", rule.hour, rule.minute)
        }
    }

    // ---------- 内部 ----------

    private fun pi(context: Context, ruleId: String, snooze: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context, ruleId.hashCode(),
            Intent(context, ReminderReceiver::class.java)
                .setAction(if (snooze) ACTION_SNOOZE_FIRE else ACTION_FIRE)
                .putExtra("rule_id", ruleId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
