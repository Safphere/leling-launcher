package com.safphere.launcher.util

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.safphere.launcher.R

/** 老人用的大字确认对话框：大标题 + 大正文 + 大确认按钮 + 取消 */
fun Activity.showBigConfirm(
    title: String,
    message: String,
    okText: String = getString(R.string.confirm),
    okColor: Int = Color.parseColor("#E53935"),
    onOk: () -> Unit
): Dialog {
    val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
    val pad = dp(24)

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, dp(10))
    }

    root.addView(TextView(this).apply {
        text = title
        textSize = 30f
        setTextColor(Color.parseColor("#212121"))
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
    })

    root.addView(TextView(this).apply {
        text = message
        textSize = 24f
        setTextColor(Color.parseColor("#616161"))
        gravity = Gravity.CENTER
        setPadding(0, pad, 0, pad)
        setLineSpacing(0f, 1.3f)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    })

    val dialog = Dialog(this)

    val okBtn = TextView(this).apply {
        text = okText
        textSize = 26f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(okColor)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(88)
        ).apply { topMargin = dp(8) }
        setOnClickListener {
            dialog.dismiss()
            onOk()
        }
    }
    root.addView(okBtn)

    val cancelBtn = TextView(this).apply {
        text = "取消"
        textSize = 22f
        setTextColor(Color.parseColor("#616161"))
        gravity = Gravity.CENTER
        setPadding(0, dp(18), 0, dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setOnClickListener { dialog.dismiss() }
    }
    root.addView(cancelBtn)

    dialog.setContentView(root)
    dialog.window?.setBackgroundDrawable(
        GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(Color.WHITE)
        }
    )
    dialog.window?.setGravity(Gravity.CENTER)
    dialog.window?.setLayout(
        (resources.displayMetrics.widthPixels * 0.86).toInt(),
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
    dialog.setCancelable(true)
    dialog.setCanceledOnTouchOutside(false)
    dialog.show()
    return dialog
}

/** 大字信息提示对话框（单按钮「知道了」） */
fun Activity.showBigInfo(title: String, message: String, okColor: Int = Color.parseColor("#1E88E5")) {
    showBigConfirm(title, message, okText = "知道了", okColor = okColor) { }
}
