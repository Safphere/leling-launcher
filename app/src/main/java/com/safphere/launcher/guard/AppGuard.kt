package com.safphere.launcher.guard

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.text.TextUtils
import com.safphere.launcher.data.Prefs
import java.util.Calendar
import java.util.Locale

/**
 * 防沉迷核心引擎：
 * - 判定（时段 / 每日 / 单次）
 * - 用量统计（UsageStats 事件流：RESUMED→PAUSED 区间累加）
 * - 当前前台应用识别
 */
object AppGuard {

    /** 一天内使用的毫秒数 */
    data class Usage(val usedMs: Long, val currentSince: Long?)

    /** 是否具备"使用情况访问"权限 */
    fun hasUsageAccess(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = ops.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), context.packageName)
        mode == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun ruleOf(pkg: String): GuardRule? = Prefs.guardRule(pkg)

    /** 今天已用量（含正在使用的部分）。无权限返回 null */
    fun usageToday(context: Context, pkg: String): Usage? {
        if (!hasUsageAccess(context)) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val events = usm.queryEvents(cal.timeInMillis, System.currentTimeMillis() + 60_000)
        var used = 0L
        var resumeAt = -1L
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.packageName != pkg) continue
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (resumeAt < 0) resumeAt = ev.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> if (resumeAt >= 0) {
                    used += (ev.timeStamp - resumeAt).coerceAtLeast(0)
                    resumeAt = -1
                }
            }
        }
        val currentSince = if (resumeAt >= 0) resumeAt else null
        return Usage(used, currentSince)
    }

    /** 当前前台应用包名（取最近一次 RESUMED 且未被 STOPPED 覆盖）。无权限返回 null */
    fun currentForeground(context: Context): String? {
        if (!hasUsageAccess(context)) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(
            System.currentTimeMillis() - 10 * 60_000, System.currentTimeMillis() + 60_000)
        var current: String? = null
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> current = ev.packageName
                UsageEvents.Event.ACTIVITY_STOPPED ->
                    if (ev.packageName == current) current = null
            }
        }
        return current
    }

    /**
     * 判定是否阻断。
     * usage：外部可传入缓存的用量（避免重复查询）；null 时若需要用量且无权限则跳过用量类判定。
     */
    fun check(context: Context, pkg: String, usage: Usage? = null): GuardBlock? {
        if (!Prefs.guardEnabled) return null
        val rule = ruleOf(pkg) ?: return null
        if (!rule.enabled) return null
        val label = rule.label.ifBlank { pkg }

        // 1) 时段限制
        val start = rule.curfewStartMin
        val end = rule.curfewEndMin
        if (start != null && end != null) {
            val now = Calendar.getInstance()
            val m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val inside = if (start <= end) m in start until end
            else m >= start || m < end   // 跨零点
            if (inside) return GuardBlock(GuardBlockType.CURFEW, pkg, label)
        }

        // 2) 每日 / 单次（需要用量数据）
        val u = usage ?: usageToday(context, pkg) ?: return null
        if (rule.dailyLimitMin > 0 && u.usedMs >= rule.dailyLimitMin * 60_000L) {
            return GuardBlock(GuardBlockType.DAILY, pkg, label)
        }
        if (rule.sessionLimitMin > 0 && u.currentSince != null &&
            System.currentTimeMillis() - u.currentSince >= rule.sessionLimitMin * 60_000L
        ) {
            return GuardBlock(GuardBlockType.SESSION, pkg, label)
        }
        return null
    }

    /** 系统关键应用不做 DO 隐藏（只做软阻断），避免破坏手机基本功能 */
    fun isCritical(context: Context, pkg: String): Boolean {
        if (pkg == context.packageName) return true
        val pm = context.packageManager
        val probes = listOf(
            android.content.Intent(android.content.Intent.ACTION_DIAL),
            android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:")),
            android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
        )
        return probes.any { intent ->
            runCatching {
                pm.resolveActivity(intent, 0)?.activityInfo?.packageName == pkg
            }.getOrDefault(false)
        }
    }

    /** 设置摘要（管理列表展示） */
    fun ruleSummary(rule: GuardRule): String {
        val parts = mutableListOf<String>()
        if (rule.dailyLimitMin > 0) parts.add("每天${rule.dailyLimitMin}分钟")
        if (rule.sessionLimitMin > 0) parts.add("每次${rule.sessionLimitMin}分钟")
        if (rule.curfewStartMin != null && rule.curfewEndMin != null) {
            parts.add(String.format(Locale.CHINA, "%02d:%02d后禁用",
                (rule.curfewStartMin ?: 0) / 60 % 24, (rule.curfewStartMin ?: 0) % 60))
        }
        return if (parts.isEmpty()) "未设置限制" else TextUtils.join(" · ", parts)
    }
}
