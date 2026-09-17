package com.safphere.launcher.sim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.safphere.launcher.flow.FlowMonitor

/** 每日定时检查（应用内闹钟触发）：SIM卡检测 + 流量查询 + 月结恢复 + 提醒自愈 */
class DailyCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SimMonitor.checkNow(context)
        SimMonitor.scheduleNext(context)
        FlowMonitor.reopenIfBillingDay(context)
        FlowMonitor.queryNow(context)
        // 提醒兜底：闹钟可能被省电策略清掉；顺便刷新天气评估天气类提醒
        val engine = com.safphere.launcher.reminder.ReminderEngine
        engine.scheduleAll(context)
        com.safphere.launcher.weather.WeatherFetcher.refreshAsync(context) {
            if (it != null) engine.evaluateWeather(context)
        }
    }
}
