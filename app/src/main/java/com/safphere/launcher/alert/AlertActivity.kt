package com.safphere.launcher.alert

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.safphere.launcher.reboot.RebootManager
import com.safphere.launcher.tts.Speaker

/**
 * 通用全屏警报页：展示任意 [Alert]（大图标 + 大标题 + 大正文 + 可选动作按钮 + 我知道了）。
 * 动作为 ACTION_REBOOT 时，主按钮触发一键重启确认流程。
 */
class AlertActivity : AppCompatActivity() {

    private var alert: Alert? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alert = Alert.fromJson(intent.getStringExtra(Alert.K_ALERT) ?: "")

        val a = alert
        if (a == null) {
            finish()
            return
        }

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(32), dp(40), dp(32), dp(32))
        }

        root.addView(TextView(this).apply {
            text = a.iconEmoji
            textSize = 88f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = a.title
            textSize = 34f
            setTextColor(Color.parseColor("#C62828"))
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, 0)
        })

        root.addView(TextView(this).apply {
            text = a.message
            textSize = 25f
            setTextColor(Color.parseColor("#212121"))
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
            setLineSpacing(0f, 1.4f)
        })

        root.addView(TextView(this).apply {
            text = a.source.takeIf { it != "local" }?.let { "来源：$it" } ?: ""
            textSize = 14f
            setTextColor(Color.parseColor("#9E9E9E"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })

        val weightView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        root.addView(weightView)

        // 主动作按钮：REBOOT=一键重启；SNOOZE=带"稍后再提醒"副按钮；其他类型=「我知道了」
        if (a.action == Alert.ACTION_REBOOT) {
            root.addView(button(this, a.actionLabel, Color.parseColor("#E53935"), dp(96)) {
                Speaker.speak("要重启手机吗")
                showBigConfirm(
                    title = "要重启手机吗？",
                    message = "重启大约需要 1 分钟\n手机会自动重新开机",
                    okText = "立即重启"
                ) { doReboot() }
            })
            root.addView(button(this, "我知道了", Color.parseColor("#F5F5F5"), dp(72), textColor = Color.parseColor("#212121")) {
                finish()
            })
        } else if (a.action == Alert.ACTION_SNOOZE) {
            root.addView(button(this, a.actionLabel, Color.parseColor("#1E88E5"), dp(88)) {
                // 健康提醒：点「知道了」即记录今日已确认（供远程状态查询 LLZT 汇报）
                if (a.actionData.isNotBlank()) {
                    com.safphere.launcher.reminder.ReminderEngine.acknowledge(a.actionData)
                }
                finish()
            })
            root.addView(button(this, "10分钟后再提醒", Color.parseColor("#F5F5F5"), dp(72), textColor = Color.parseColor("#212121")) {
                if (a.actionData.isNotBlank()) {
                    com.safphere.launcher.reminder.ReminderEngine.snooze(applicationContext, a.actionData)
                }
                finish()
            })
        } else {
            root.addView(button(this, a.actionLabel, Color.parseColor("#1E88E5"), dp(88)) {
                finish()
            })
        }

        setContentView(root)

        // 语音播报（可由来源方关闭）
        a.speakText?.takeIf { it.isNotBlank() }?.let { Speaker.speak(it) }
    }

    private fun button(
        activity: AlertActivity,
        text: String,
        bgColor: Int,
        heightPx: Int,
        textColor: Int = Color.WHITE,
        onClick: () -> Unit
    ): View = Button(activity).apply {
        this.text = text
        this.textSize = 26f
        setTextColor(textColor)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        // 大圆角实色背景（bgColor 此前未生效，按钮呈默认灰色，白字对比度不足）
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = activity.dp(20).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, heightPx
        ).apply { topMargin = heightPx / 3 }
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showBigConfirm(title: String, message: String, okText: String, onOk: () -> Unit) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val msg = TextView(this).apply {
            text = "$title\n\n$message"
            textSize = 25f
            setPadding(dp(30), dp(40), dp(30), dp(20))
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(msg)
            .setPositiveButton(okText) { _, _ -> onOk() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doReboot() {
        when (RebootManager.tryReboot(this)) {
            RebootManager.Method.DEVICE_OWNER -> finish()
            else -> Speaker.speak("请长按手机右侧的电源键，然后点重启")
        }
    }
}
