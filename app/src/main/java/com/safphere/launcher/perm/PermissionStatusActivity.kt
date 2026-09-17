package com.safphere.launcher.perm

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.safphere.launcher.perm.PermCatalog

/**
 * 权限自检与授权页：
 * 列出全部所需权限的实时状态；未授权的运行时权限点一下即弹出系统授权框；
 * 被小米"拒绝后不再询问"的，引导跳应用详情页手动开启；含小米自启动指引。
 */
class PermissionStatusActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private var pendingRequest: String? = null

    private val dp = { v: Int ->
        (v * resources.displayMetrics.density).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refresh()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(Color.WHITE)
        }

        root.addView(text("← 返回", 18f, "#1565C0").apply {
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
            setOnClickListener { finish() }
        })

        root.addView(text("权限自检与授权", 28f, "#212121", bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        })

        root.addView(text(
            "小米手机提示「未知来源/风险应用」后，敏感权限可能被自动关闭。" +
                "绿色=已授权；点击红色未授权项即可弹出授权；" +
                "如果不再弹框，请点底部「应用详情页」手动开启。",
            15f, "#616161").apply {
            setLineSpacing(0f, 1.2f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        })

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        root.addView(listContainer)

        root.addView(action("应用详情页（手动授权 / 自启动 / 省电策略）") { openAppDetails() })
        root.addView(action("无障碍服务设置（一键重启增强）") {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { toast("无法打开设置") }
        })

        root.addView(text(
            "小米自启动开启路径：设置 → 应用设置 → 应用管理 → 乐龄桌面 → 自启动，" +
                "并把省电策略改为「无限制」。否则每日检测、短信接收可能被系统清理。",
            13f, "#757575").apply {
            setLineSpacing(0f, 1.2f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        })

        scroll.addView(root)
        return scroll
    }

    private fun text(
        content: String, size: Float, colorHex: String, bold: Boolean = false
    ): TextView = TextView(this).apply {
        this.text = content
        textSize = size
        setTextColor(Color.parseColor(colorHex))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun action(label: String, onClick: () -> Unit): View =
        TextView(this).apply {
            this.text = label
            textSize = 16f
            setTextColor(Color.parseColor("#1565C0"))
            gravity = Gravity.CENTER
            background = round("#E3F2FD")
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
            ).apply { topMargin = dp(10) }
            setOnClickListener { onClick() }
        }

    private fun round(colorHex: String) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = dp(12).toFloat()
    }

    private fun refresh() {
        listContainer.removeAllViews()

        for (item in PermCatalog.ITEMS) {
            val ok = when (item.kind) {
                PermCatalog.Kind.RUNTIME ->
                    item.permission?.let { PermCatalog.granted(this, it) } ?: true
                PermCatalog.Kind.ACCESSIBILITY -> PermCatalog.accessibilityEnabled(this)
                PermCatalog.Kind.AUTO_START -> true   // 无法程序化检测，仅展示指引
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(if (ok) "#F1F8E9" else "#FFEBEE")
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
                ).apply { topMargin = dp(6) }
                setPadding(dp(14), 0, dp(14), 0)
                if (item.kind == PermCatalog.Kind.RUNTIME && !ok) {
                    isClickable = true
                    isFocusable = true
                }
            }

            row.addView(TextView(this).apply {
                text = if (ok) "✓" else "✗"
                textSize = 20f
                setTextColor(if (ok) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            })

            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = dp(10) }
                addView(TextView(this@PermissionStatusActivity).apply {
                    text = item.label
                    textSize = 16f
                    setTextColor(Color.parseColor("#212121"))
                })
                addView(TextView(this@PermissionStatusActivity).apply {
                    text = if (ok) "已授权" else item.usage
                    textSize = 13f
                    setTextColor(Color.parseColor("#757575"))
                })
            })

            if (!ok) row.addView(TextView(this).apply {
                text = "点击授权 >"
                textSize = 13f
                setTextColor(Color.parseColor("#1565C0"))
            })

            row.setOnClickListener { onRequestRow(item) }
            listContainer.addView(row)
        }

        listContainer.addView(text(
            "小米自启动开启路径：设置 → 应用设置 → 应用管理 → 乐龄桌面 → 自启动，" +
                "并把省电策略改为「无限制」。否则每日检测、短信接收可能被系统清理。",
            13f, "#757575").apply {
            setLineSpacing(0f, 1.2f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        })
    }

    private fun onRequestRow(item: PermCatalog.Item) {
        val perm = item.permission ?: return
        if (PermCatalog.granted(this, perm)) {
            toast("${item.label} 已授权")
            return
        }
        // 再次弹出系统授权框；被"拒绝且不再询问"时无反应 → 引导应用详情页
        pendingRequest = perm
        requestPermissions(arrayOf(perm), 100)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                toast("未弹框？请到应用详情页手动开启")
                openAppDetails()
            }
            refresh()
        }
    }

    private fun openAppDetails() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: ActivityNotFoundException) {
            toast("无法打开应用详情")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
