package com.safphere.launcher.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 闹钟触发入口：定时到点 / 稍后再提醒，都路由到 ReminderEngine */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("rule_id") ?: return
        when (intent.action) {
            ReminderEngine.ACTION_FIRE -> ReminderEngine.onFire(context, id, isSnooze = false)
            ReminderEngine.ACTION_SNOOZE_FIRE -> ReminderEngine.onFire(context, id, isSnooze = true)
        }
    }
}
