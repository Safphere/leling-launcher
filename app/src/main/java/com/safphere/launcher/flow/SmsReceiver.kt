package com.safphere.launcher.flow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** 实时接收运营商回复短信并解析流量（动态注册为主，manifest注册兜底） */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull()
            ?: return
        val from = msgs.firstOrNull()?.originatingAddress
        val text = msgs.joinToString("") { it.displayMessageBody ?: "" }
        if (from != null && text.isNotBlank()) {
            // 远程状态查询（子女发 LLZT）：命中即回复状态短信并结束（不走流量解析）
            if (com.safphere.launcher.sms.StatusSms.maybeRespond(context, from, text)) return
            FlowMonitor.onIncomingSms(context, from, text)
        }
    }
}
