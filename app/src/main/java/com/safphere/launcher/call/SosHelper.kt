package com.safphere.launcher.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.safphere.launcher.data.Contact
import com.safphere.launcher.data.Prefs
import com.safphere.launcher.tts.Speaker

/**
 * 紧急求助（SOS）：
 * 触发后自动 ① 给第一位紧急联系人打电话；② 群发带定位的求助短信给所有紧急联系人。
 * 定位用 getLastKnownLocation（秒出，不等待），拿不到则短信省略定位段。
 */
object SosHelper {

    private const val TAG = "SosHelper"

    fun emergencyContacts(context: Context): List<Contact> =
        Prefs.contacts().filter { it.emergency && it.phone.isNotBlank() }

    fun hasEmergencyContact(context: Context): Boolean =
        emergencyContacts(context).isNotEmpty()

    /** 触发求助：先短信（带定位），再呼出第一位紧急联系人 */
    fun trigger(context: Context, vibrate: Boolean = true) {
        val list = emergencyContacts(context)
        if (list.isEmpty()) return

        if (vibrate) vibrate(context)

        // 1) 群发求助短信（定位可选）；缺短信权限时跳过，优先保证呼出
        val smsAllowed = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val location = if (smsAllowed) lastKnownLocation(context) else null
        val sms = buildMessage(context, location)
        var sent = 0
        for (c in list) {
            val ok = runCatching {
                @Suppress("DEPRECATION")
                SmsManager.getDefault().sendTextMessage(c.phone, null, sms, null, null)
                true
            }.getOrDefault(false)
            if (ok) sent++
        }
        Log.i(TAG, "sos sms sent to $sent/${list.size}, location=${location != null}")

        // 2) 呼叫第一位紧急联系人（真实呼出，无权限时落拨号盘）
        val primary = list.first()
        Speaker.speak(
            if (smsAllowed) "正在呼叫紧急联系人${primary.name}，求助短信已发送"
            else "正在呼叫紧急联系人${primary.name}"
        )
        val uri = Uri.parse("tel:${primary.phone}")
        val action = if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) Intent.ACTION_CALL else Intent.ACTION_DIAL
        runCatching { context.startActivity(Intent(action, uri)) }
    }

    fun buildMessage(context: Context, location: Location?): String {
        val sb = StringBuilder("【紧急求助】机主现在遇到紧急情况，请求帮助！")
        location?.let {
            sb.append("\n当前位置：https://uri.amap.com/marker?position=")
                .append(String.format(java.util.Locale.CHINA, "%.6f", it.longitude))
                .append(",")
                .append(String.format(java.util.Locale.CHINA, "%.6f", it.latitude))
        }
        return sb.toString()
    }

    /** 最近一次已知位置：先网络定位后GPS；无权限或无结果返回 null */
    fun lastKnownLocation(context: Context): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return runCatching {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        }.getOrNull()
    }

    fun vibrate(context: Context) {
        runCatching {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(400)
            }
        }
    }
}
