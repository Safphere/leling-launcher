package com.safphere.launcher.flow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat

/** 发送查询短信60秒后的兜底：后台线程读收件箱该号码最新短信并解析（goAsync 防主线程IO） */
class FlowInboxCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        Thread {
            try {
                doCheck(context)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun doCheck(context: Context) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val qn = com.safphere.launcher.data.Prefs.flowQueryNumber
        if (qn.isBlank()) return
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.ADDRESS} LIKE ?",
                arrayOf("%$qn%"),
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                if (c.moveToFirst()) {
                    val date = c.getLong(2)
                    // 只认10分钟内的短信（避免拿旧短信误判）
                    if (System.currentTimeMillis() - date < 10 * 60 * 1000L) {
                        FlowMonitor.onIncomingSms(context, c.getString(0), c.getString(1))
                    }
                }
            }
        }.onFailure { Log.w("FlowInbox", "read inbox failed: ${it.message}") }
    }
}
