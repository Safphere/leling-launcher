package com.safphere.launcher.flow

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.safphere.launcher.R
import com.safphere.launcher.admin.ElderDeviceAdminReceiver
import com.safphere.launcher.data.Prefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 流量智能管理：
 * 每天发短信查运营商剩余流量 → 低于阈值自动关移动数据（打电话/WiFi不受影响）
 * → 月结日自动重开并重新查询。
 */
object FlowMonitor {

    private const val TAG = "FlowMonitor"
    private const val QUERY_THROTTLE_MS = 20 * 60 * 1000L   // 查询节流20分钟
    private const val CHANNEL_ID = "flow_alert"

    /** 三家运营商默认查询配置 */
    val CARRIERS = listOf(
        Triple("中国移动", "10086", "CXLL"),
        Triple("中国联通", "10010", "CXLL"),
        Triple("中国电信", "10001", "108")
    )

    // ---------------- 短信查询 ----------------

    /** 发送流量查询短信（每日定时与手动点击都会走到这里，内部节流） */
    fun queryNow(context: Context): Boolean {
        if (!Prefs.flowEnabled) return false
        val now = System.currentTimeMillis()
        if (now - Prefs.flowLastQueryAt < QUERY_THROTTLE_MS) return false
        val number = Prefs.flowQueryNumber
        val text = Prefs.flowQueryText
        if (number.isBlank() || text.isBlank()) return false
        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "send sms skipped: SEND_SMS permission missing")
            return false
        }
        Prefs.flowLastQueryAt = now

        val ok = runCatching {
            @Suppress("DEPRECATION")
            SmsManager.getDefault().sendTextMessage(number, null, text, null, null)
            true
        }.onFailure { Log.w(TAG, "send sms failed: ${it.message}") }.getOrDefault(false)

        if (ok) {
            // 60秒后兜底读收件箱（RECEIVE_SMS广播可能被系统策略拦）
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 1101,
                Intent(context, FlowInboxCheckReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60_000L, pi)
            if (com.safphere.launcher.BuildConfig.DEBUG) Log.i(TAG, "flow query sms sent")
        }
        return ok
    }

    /** 收到疑似运营商短信（实时广播或收件箱兜底） */
    fun onIncomingSms(context: Context, from: String?, text: String?) {
        if (!Prefs.flowEnabled || text.isNullOrBlank()) return
        val qn = Prefs.flowQueryNumber
        if (qn.isNotBlank() && from != null && !from.contains(qn)) return

        val mb = parseFlow(text)
        if (mb == null) {
            if (com.safphere.launcher.BuildConfig.DEBUG) Log.i(TAG, "cannot parse flow sms (${text.length} chars)")
            return
        }
        Prefs.flowRemainingMb = mb
        Prefs.flowLastQueryOkAt = System.currentTimeMillis()
        if (com.safphere.launcher.BuildConfig.DEBUG) Log.i(TAG, "remaining flow = $mb MB")

        if (mb < Prefs.flowThresholdMb) {
            val ok = setDataEnabled(context, false)
            Prefs.flowDataOffByUs = true
            notifyFlowState(
                context,
                title = "剩余流量不足，已关闭移动数据",
                text = "本月剩余 ${fmtMb(mb)}，已低于 ${Prefs.flowThresholdMb}MB。\n" +
                    "${Prefs.flowBillingDay}号月结后自动恢复。\n打电话和WiFi上网不受影响。" +
                    if (ok) "" else "\n\n（自动关闭失败，请子女在系统设置中手动关闭移动数据）"
            )
        } else if (Prefs.flowDataOffByUs) {
            // 流量充足（如已月结/加了加油包）：自动恢复数据
            val ok = setDataEnabled(context, true)
            if (ok) {
                Prefs.flowDataOffByUs = false
                notifyFlowState(
                    context,
                    title = "流量充足，移动数据已恢复",
                    text = "当前剩余 ${fmtMb(mb)}，移动数据已自动打开。"
                )
            }
        }
    }

    /** 从运营商短信中解析剩余流量（MB）。关键词最近的数字+单位优先。 */
    fun parseFlow(text: String): Float? {
        val token = Regex("(\\d+(?:\\.\\d+)?)\\s*(GB|G|MB|M|兆|KB|K)", RegexOption.IGNORE_CASE)
        val tokens = token.findAll(text).map {
            Triple(it.range.first, it.groupValues[1].toFloat(), it.groupValues[2].uppercase(Locale.ROOT))
        }.toList()
        if (tokens.isEmpty()) return null

        fun toMb(v: Float, u: String) = when (u) {
            "GB", "G" -> v * 1024f
            "KB", "K" -> v / 1024f
            else -> v   // MB / M / 兆
        }

        for (kw in listOf("剩余流量", "流量剩余", "剩余", "还剩", "可用", "余")) {
            val idx = text.indexOf(kw)
            if (idx >= 0) {
                tokens.filter { it.first >= idx }.minByOrNull { it.first }?.let {
                    return toMb(it.second, it.third)
                }
            }
        }
        val t = tokens.first()
        return toMb(t.second, t.third)
    }

    // ---------------- 月结自动恢复 ----------------

    /** 每日检查/开机/亮屏时调用：今天是月结日且数据被我们关了 → 重开并重新查询 */
    fun reopenIfBillingDay(context: Context, force: Boolean = false) {
        if (!Prefs.flowEnabled || !Prefs.flowDataOffByUs) return
        val cal = Calendar.getInstance()
        val ym = SimpleDateFormat("yyyy-MM", Locale.CHINA).format(cal.time)
        if (!force) {
            if (cal.get(Calendar.DAY_OF_MONTH) != Prefs.flowBillingDay) return
            if (Prefs.flowLastResetYm == ym) return
        }
        Prefs.flowLastResetYm = ym
        val ok = setDataEnabled(context, true)
        if (ok) {
            Prefs.flowDataOffByUs = false
            notifyFlowState(
                context,
                title = "月结已到，移动数据已恢复",
                text = "本月流量已重置，移动数据自动打开。正在重新查询剩余流量。"
            )
            queryNow(context)
        }
    }

    // ---------------- 移动数据开关（三级降级） ----------------

    fun setDataEnabled(context: Context, on: Boolean): Boolean {
        // 1) Device Owner 静默开关（推荐，配机时 adb 设置）。
        //    setMobileDataEnabled 在新SDK已移除存根（API33弃用），设备上仍存在 → 反射调用。
        runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                val method = DevicePolicyManager::class.java.getMethod(
                    "setMobileDataEnabled",
                    android.content.ComponentName::class.java,
                    Boolean::class.javaPrimitiveType
                )
                method.invoke(dpm, ElderDeviceAdminReceiver.component(context), on)
                return true
            }
        }.onFailure { Log.w(TAG, "dpm.setMobileDataEnabled failed", it) }

        // 2) WRITE_SECURE_SETTINGS（子女 adb pm grant 授予）写 Settings.Global
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                Settings.Global.putInt(context.contentResolver, "mobile_data", if (on) 1 else 0)
                return true
            }.onFailure { Log.w(TAG, "settings.global write failed", it) }
        }
        return false
    }

    // ---------------- 展示与通知 ----------------

    fun fmtMb(mb: Float): String =
        if (mb >= 1024f) String.format(Locale.CHINA, "%.1fGB", mb / 1024f)
        else String.format(Locale.CHINA, "%.0fMB", mb)

    private fun notifyFlowState(context: Context, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "流量管理提醒", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sim)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(3101, n) }
    }
}
