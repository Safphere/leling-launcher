package com.safphere.launcher.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.safphere.launcher.R
import com.safphere.launcher.home.HomeActivity

/**
 * 警报路由中心：任何来源（本机功能、外部App推送、后续新增的灾害接口）
 * 都通过 show() 展示警报。
 *
 * 展示策略：
 * - 桌面在前台 → 直接弹全屏警报页（大字+语音+可选动作）
 * - 桌面不在前台 → 高优先级全屏通知（锁屏/后台也能弹出）
 */
object AlertManager {

    private const val TAG = "AlertManager"
    private const val CHANNEL_ID = "general_alerts"

    fun show(context: Context, alert: Alert) {
        if (HomeActivity.isResumed) {
            startAlertActivity(context, alert)
        } else {
            postFullScreenNotification(context, alert)
        }
    }

    fun startAlertActivity(context: Context, alert: Alert) {
        val intent = Intent(context, AlertActivity::class.java)
            .putExtra(Alert.K_ALERT, alert.toJson().toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "show alert activity failed: ${it.message}") }
    }

    private fun postFullScreenNotification(context: Context, alert: Alert) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "重要提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "地震、洪水、电话卡异常等重要提醒"
                    enableVibration(true)
                }
            )
        }
        val contentIntent = PendingIntent.getActivity(
            context, 4001,
            Intent(context, AlertActivity::class.java)
                .putExtra(Alert.K_ALERT, alert.toJson().toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sim)
            .setContentTitle("${alert.iconEmoji} ${alert.title}")
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setFullScreenIntent(contentIntent, true)
            .setContentIntent(contentIntent)
            .build()
        runCatching { nm.notify(alert.type.hashCode(), n) }
            .onFailure { Log.w(TAG, "notify failed: ${it.message}") }
    }
}
