package com.safphere.launcher.flow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.safphere.launcher.BuildConfig

/**
 * 调试钩子（仅 debug 构建生效），用于在模拟器上验证流量链路：
 *   adb shell am broadcast -a com.safphere.launcher.DEBUG_INJECT_SMS --es from 10086 --es text "您剩余流量86.5MB"
 *   adb shell am broadcast -a com.safphere.launcher.DEBUG_BILLING_CHECK   （强制触发月结恢复检查）
 */
class FlowDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        when (intent.action) {
            ACTION_INJECT_SMS -> FlowMonitor.onIncomingSms(
                context,
                intent.getStringExtra("from") ?: "10086",
                intent.getStringExtra("text") ?: return
            )
            ACTION_BILLING_CHECK -> FlowMonitor.reopenIfBillingDay(context, force = true)
            ACTION_GOTO_PAGE -> context.sendBroadcast(
                Intent(ACTION_INTERNAL_GOTO_PAGE).setPackage(context.packageName)
                    .putExtra("page", intent.getIntExtra("page", 0))
            )
            ACTION_IDLE -> context.sendBroadcast(
                Intent(ACTION_INTERNAL_IDLE).setPackage(context.packageName)
            )
        }
    }

    companion object {
        const val ACTION_INJECT_SMS = "com.safphere.launcher.DEBUG_INJECT_SMS"
        const val ACTION_BILLING_CHECK = "com.safphere.launcher.DEBUG_BILLING_CHECK"
        const val ACTION_GOTO_PAGE = "com.safphere.launcher.DEBUG_GOTO_PAGE"
        const val ACTION_IDLE = "com.safphere.launcher.DEBUG_IDLE"
        /** App内部转发（HomeActivity动态接收） */
        const val ACTION_INTERNAL_GOTO_PAGE = "com.safphere.launcher.INTERNAL_GOTO_PAGE"
        const val ACTION_INTERNAL_IDLE = "com.safphere.launcher.INTERNAL_IDLE"
    }
}
