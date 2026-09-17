package com.safphere.launcher.sim

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.safphere.launcher.flow.FlowMonitor

/** 开机：恢复每日检测排期；延迟2分钟做一次开机检查（等电话模块就绪）；月结日重开流量 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        SimMonitor.scheduleNext(context)
        FlowMonitor.reopenIfBillingDay(context)
        // 开机后提醒闹钟需重排（闹钟随关机丢失）
        com.safphere.launcher.reminder.ReminderEngine.scheduleAll(context)

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 1002,
            Intent(context, DailyCheckReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 120_000L, pi)
    }
}
