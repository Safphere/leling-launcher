package com.safphere.launcher.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.safphere.launcher.alert.Alert
import com.safphere.launcher.alert.AlertContract
import com.safphere.launcher.alert.AlertManager

/**
 * 【对外集成接口】外部警报推送接收器。
 *
 * 其他 App（地震预警、洪水预警、社区通知等）可通过隐式/显式广播推送警报：
 *
 *   Intent(AlertContract.ACTION_PUSH)
 *       .setPackage("com.safphere.launcher")
 *       .putExtra("type", "EARTHQUAKE")        // 来源类型（自定义字符串，≤16字符）
 *       .putExtra("title", "地震预警")          // 必填，≤40字符
 *       .putExtra("message", "震中附近…")       // ≤300字符
 *       .putExtra("icon", "🌊")                 // 可选 emoji
 *       .putExtra("speak", "地震了，请到空旷处") // 可选 TTS 播报文本
 *       .putExtra("action", "NONE")            // NONE / REBOOT
 *       .putExtra("level", "HIGH")             // HIGH=全屏警报 NORMAL=通知
 *       .putExtra("source", "地震预警App")      // 显示的来源名
 *
 * 安全：接收器声明 com.safphere.launcher.permission.PUSH_ALERT（signature 级），
 * 仅同签名应用可推送；所有字段严格截断/校验，防止恶意输入。
 */
class ExternalAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlertContract.ACTION_PUSH) return

        val title = intent.getStringExtra(AlertContract.EXTRA_TITLE)?.trim().orEmpty()
        if (title.isBlank()) return   // 无标题直接忽略

        val appContext = context.applicationContext
        val alert = Alert(
            type = intent.getStringExtra(AlertContract.EXTRA_TYPE)?.take(16)?.trim()
                ?.ifBlank { "CUSTOM" } ?: "CUSTOM",
            title = title.take(40),
            message = intent.getStringExtra(AlertContract.EXTRA_MESSAGE)?.trim()?.take(300).orEmpty(),
            iconEmoji = intent.getStringExtra(AlertContract.EXTRA_ICON)?.take(8)
                ?.ifBlank { "⚠️" } ?: "⚠️",
            speakText = intent.getStringExtra(AlertContract.EXTRA_SPEAK)?.trim()?.take(120)
                ?.ifBlank { null },
            action = if (intent.getStringExtra(AlertContract.EXTRA_ACTION) == Alert.ACTION_REBOOT)
                Alert.ACTION_REBOOT else Alert.ACTION_NONE,
            actionLabel = if (intent.getStringExtra(AlertContract.EXTRA_ACTION) == Alert.ACTION_REBOOT)
                "立即重启" else "我知道了",
            level = if (intent.getStringExtra(AlertContract.EXTRA_LEVEL) == Alert.LEVEL_HIGH)
                Alert.LEVEL_HIGH else Alert.LEVEL_NORMAL,
            source = intent.getStringExtra(AlertContract.EXTRA_SOURCE)?.take(20)
                ?.ifBlank { "外部应用" } ?: "外部应用"
        )
        AlertManager.show(appContext, alert)
    }
}
