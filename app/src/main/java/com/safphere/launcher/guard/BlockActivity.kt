package com.safphere.launcher.guard

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 防沉迷阻断页（全屏大字）：说明原因，点"知道了"回到桌面。
 */
class BlockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }
        val type = intent.getStringExtra(EXTRA_TYPE) ?: GuardBlockType.SESSION.name
        val label = intent.getStringExtra(EXTRA_LABEL) ?: ""
        val rule = AppGuard.ruleOf(pkg)
        val block = GuardBlock(GuardBlockType.valueOf(type), pkg, label)
        if (rule == null) { finish(); return }

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(32), dp(60), dp(32), dp(40))
        }

        root.addView(TextView(this).apply {
            text = when (block.type) {
                GuardBlockType.CURFEW -> "🌙"
                GuardBlockType.DAILY -> "🏁"
                GuardBlockType.SESSION -> "⏰"
            }
            textSize = 84f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = block.title()
            textSize = 34f
            setTextColor(Color.parseColor("#C62828"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })

        root.addView(TextView(this).apply {
            text = block.message(rule)
            textSize = 25f
            setTextColor(Color.parseColor("#212121"))
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.4f)
            setPadding(0, dp(20), 0, 0)
        })

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })

        root.addView(TextView(this).apply {
            text = "知道了，回到桌面"
            textSize = 26f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = com.safphere.launcher.util.AvatarUtils.circleBg(-0x1, true).apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#1E88E5"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(92))
            setOnClickListener {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                finish()
            }
        })

        setContentView(root)
        com.safphere.launcher.tts.Speaker.speak(block.title())
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_LABEL = "label"

        fun start(context: Context, block: GuardBlock, rule: GuardRule?) {
            rule ?: return
            context.startActivity(
                Intent(context, BlockActivity::class.java)
                    .putExtra(EXTRA_PKG, block.pkg)
                    .putExtra(EXTRA_TYPE, block.type.name)
                    .putExtra(EXTRA_LABEL, block.label)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            )
        }
    }
}
