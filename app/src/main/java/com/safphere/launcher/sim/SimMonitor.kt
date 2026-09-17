package com.safphere.launcher.sim

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.safphere.launcher.R
import com.safphere.launcher.data.Prefs
import com.safphere.launcher.home.HomeActivity
import java.util.Calendar

/**
 * SIM卡检测：
 * - 每日定时（AlarmManager 不精确窗口，避免申请 SCHEDULE_EXACT_ALARM）
 * - SIM_STATE_CHANGED 实时广播（见 SafphereApp）
 * - 桌面每次亮屏 onResume 兜底检查（1小时节流）
 * - 无卡（ABSENT）→ 全屏警报 + 高优先级通知（后台时）
 */
object SimMonitor {

    private const val TAG = "SimMonitor"
    private const val CHANNEL_ID = "sim_alert"
    private const val ALERT_COOLDOWN_MS = 2 * 60 * 60 * 1000L   // 同一无卡状态2小时内只提醒一次
    private const val RESUME_THROTTLE_MS = 60 * 60 * 1000L      // onResume检查1小时节流

    /** null=无法判断（权限/无电话功能）， true=无卡 */
    fun isSimAbsent(context: Context): Boolean? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        return runCatching {
            var anyAbsent = false
            var slots = 1
            if (Build.VERSION.SDK_INT >= 30) {
                slots = maxOf(1, tm.activeModemCount)
            }
            for (slot in 0 until minOf(slots, 2)) {
                val state = if (slot == 0) tm.simState else tm.getSimState(slot)
                when (state) {
                    TelephonyManager.SIM_STATE_ABSENT -> anyAbsent = true
                    TelephonyManager.SIM_STATE_READY,
                    TelephonyManager.SIM_STATE_PIN_REQUIRED,
                    TelephonyManager.SIM_STATE_PUK_REQUIRED,
                    TelephonyManager.SIM_STATE_NETWORK_LOCKED,
                    TelephonyManager.SIM_STATE_CARD_IO_ERROR,
                    TelephonyManager.SIM_STATE_CARD_RESTRICTED -> return false
                    else -> Unit
                }
            }
            anyAbsent
        }.onFailure {
            // API 31+ getSimState 需要 READ_PHONE_STATE；未授权时返回 null 不误报
            Log.w(TAG, "read sim state failed: ${it.message}")
        }.getOrNull()
    }

    /** 检查并在无卡时警报；force=true 跳过冷却（如桌面警报条点击） */
    fun checkNow(context: Context, force: Boolean = false): Boolean {
        if (!Prefs.simCheckEnabled && !force) return false
        val absent = isSimAbsent(context)
        if (absent != true) return false
        if (!force && System.currentTimeMillis() - Prefs.lastSimAlertAt < ALERT_COOLDOWN_MS) return false
        showAlert(context)
        return true
    }

    fun showAlert(context: Context) {
        Prefs.lastSimAlertAt = System.currentTimeMillis()
        // 走通用警报框架：全屏警报（桌面前台）或全屏通知（后台），含一键重启动作
        com.safphere.launcher.alert.AlertManager.show(
            context,
            com.safphere.launcher.alert.Alert(
                type = "SIM",
                title = "未检测到电话卡！",
                message = "电话卡可能松动了\n请尝试重启手机\n\n如果重启后仍然没有信号\n请联系家人或到营业厅检查",
                iconEmoji = "⚠️",
                speakText = "未检测到电话卡，电话卡可能松动了，可以尝试重启手机",
                action = com.safphere.launcher.alert.Alert.ACTION_REBOOT,
                actionLabel = "立即重启",
                level = com.safphere.launcher.alert.Alert.LEVEL_HIGH,
                source = "local"
            )
        )
    }

    /** SIM广播回调（hidden extra，stateExtra 为 "ABSENT"/"READY"/... 字符串） */
    fun onSimStateChanged(context: Context, stateExtra: String?) {
        if (stateExtra.equals("ABSENT", ignoreCase = true)) {
            checkNow(context)
        }
    }

    /** 桌面onResume兜底检查（节流） */
    fun checkOnResume(context: Context) {
        val now = System.currentTimeMillis()
        if (now - Prefs.lastResumeCheckAt < RESUME_THROTTLE_MS) return
        Prefs.lastResumeCheckAt = now
        checkNow(context)
    }

    /** 排下一天的定时检查 */
    fun scheduleNext(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = dailyPendingIntent(context, create = true)

        if (!Prefs.simCheckEnabled) {
            am.cancel(pi)
            return
        }

        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, Prefs.simHour)
            set(Calendar.MINUTE, Prefs.simMinute)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        // 30分钟窗口的不精确闹钟：省电且无需特殊权限，对“每天检查”完全够用
        am.setWindow(AlarmManager.RTC_WAKEUP, next.timeInMillis, 30 * 60 * 1000L, pi)
        if (com.safphere.launcher.BuildConfig.DEBUG) Log.i(TAG, "next sim check scheduled")
    }

    private fun dailyPendingIntent(context: Context, create: Boolean): PendingIntent {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val intent = Intent(context, DailyCheckReceiver::class.java)
        return if (create) {
            PendingIntent.getBroadcast(context, 1001, intent, flags)
        } else {
            PendingIntent.getBroadcast(context, 1001, intent, flags)
        }
    }

}
