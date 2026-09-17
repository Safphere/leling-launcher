package com.safphere.launcher.guard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 防沉迷看门狗：每分钟检查当前前台应用，超限即管控（DO 隐藏 + 阻断页）；
 * 同时自动恢复条件已满足的被隐藏应用（时段结束/规则关闭）。
 */
class GuardWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
                Thread {
            runCatching { GuardEnforcer.enforceForeground(appContext) }
                .onFailure { android.util.Log.w("GuardWatchdog", "enforce failed: ${it.message}") }
        }.start()
        scheduleNext(appContext)
    }

    companion object {
        private const val INTERVAL_MS = 60_000L   // 一分钟

        fun scheduleNext(context: Context) {
            if (!com.safphere.launcher.data.Prefs.guardEnabled) return
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 1201,
                Intent(context, GuardWatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + INTERVAL_MS, pi)
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(PendingIntent.getBroadcast(
                context, 1201,
                Intent(context, GuardWatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
        }
    }
}

/** 开机/解锁后恢复看门狗并立刻检查一次 */
class GuardBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != android.content.Intent.ACTION_USER_PRESENT) return
        if (!com.safphere.launcher.data.Prefs.guardEnabled) return
        Thread { runCatching { GuardEnforcer.enforceForeground(context.applicationContext) } }.start()
        GuardWatchdogReceiver.scheduleNext(context.applicationContext)
    }
}
