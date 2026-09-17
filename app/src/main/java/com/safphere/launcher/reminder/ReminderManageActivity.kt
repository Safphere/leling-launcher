package com.safphere.launcher.reminder

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 健康提醒管理（子女模式）：全部提醒列表，每条带独立开关；
 * 点击进入编辑，长按删除；底部「新建提醒」+ 常用模板。
 */
class ReminderManageActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("健康提醒")
        setContentView(buildUi())
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "← 返回"
            textSize = 18f
            setTextColor(0xFF1565C0.toInt())
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
        })
        root.addView(TextView(this).apply {
            text = "到点全屏+语音提醒吃药、量血压；也可设成看天气提醒" +
                "（高温/降温/雨雪时关怀，每天最多提醒一次）。"
            textSize = 14f
            setTextColor(0xFF757575.toInt())
            setPadding(0, dp(6), 0, dp(6))
        })
        emptyHint = TextView(this).apply {
            text = "还没有提醒，点下面「新建提醒」，先从常用模板挑一个吧。"
            textSize = 16f
            setTextColor(0xFF9E9E9E.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, 0)
        }
        root.addView(emptyHint)
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ReminderManageActivity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(recycler)
        root.addView(TextView(this).apply {
            text = "＋ 新建提醒"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1E88E5.toInt())
                cornerRadius = dp(18).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply {
                topMargin = dp(10)
            }
            setOnClickListener {
                startActivity(Intent(this@ReminderManageActivity, ReminderEditActivity::class.java))
            }
        })
        return root
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val rules = com.safphere.launcher.data.Prefs.reminderRules()
        emptyHint.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        recycler.adapter = object : RecyclerView.Adapter<VH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(rowView())
            override fun getItemCount() = rules.size
            override fun onBindViewHolder(h: VH, pos: Int) = h.bind(rules[pos])
        }
    }

    /** 程序化行视图：图标 | 名称+摘要 | 独立开关 */
    private fun rowView(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            background = androidx.core.content.ContextCompat.getDrawable(
                this@ReminderManageActivity,
                com.safphere.launcher.R.drawable.bg_card)
        }
        val icon = TextView(this).apply { id = android.R.id.icon; textSize = 30f }
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        val name = TextView(this).apply { id = android.R.id.text1; textSize = 19f; typeface = Typeface.DEFAULT_BOLD }
        val sub = TextView(this).apply {
            id = android.R.id.text2; textSize = 14f
            setTextColor(0xFF757575.toInt())
            setPadding(0, dp(2), 0, 0)
        }
        mid.addView(name); mid.addView(sub)
        val sw = SwitchCompat(this).apply {
            id = android.R.id.toggle
            minWidth = dp(64)
        }
        row.addView(icon); row.addView(mid); row.addView(sw)
        return row
    }

    private inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(rule: ReminderRule) {
            itemView.findViewById<TextView>(android.R.id.icon).text = rule.icon
            itemView.findViewById<TextView>(android.R.id.text1).text = rule.label
            itemView.findViewById<TextView>(android.R.id.text2).text =
                ReminderEngine.summary(rule) + if (rule.message.isNotBlank()) " · ${rule.message}" else ""
            val sw = itemView.findViewById<SwitchCompat>(android.R.id.toggle)
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = rule.enabled
            sw.setOnCheckedChangeListener { _, checked ->
                val list = com.safphere.launcher.data.Prefs.reminderRules()
                list.firstOrNull { it.id == rule.id }?.let { r ->
                    r.enabled = checked
                    com.safphere.launcher.data.Prefs.saveReminderRules(list)
                    if (checked) ReminderEngine.scheduleNext(this@ReminderManageActivity, r)
                    else ReminderEngine.cancel(this@ReminderManageActivity, r.id)
                }
            }
            itemView.setOnClickListener {
                startActivity(Intent(this@ReminderManageActivity, ReminderEditActivity::class.java)
                    .putExtra("rule_id", rule.id))
            }
            itemView.setOnLongClickListener {
                AlertDialog.Builder(this@ReminderManageActivity)
                    .setTitle("删除「${rule.label}」？")
                    .setPositiveButton("删除") { _, _ ->
                        val list = com.safphere.launcher.data.Prefs.reminderRules()
                            .filter { it.id != rule.id }
                        com.safphere.launcher.data.Prefs.saveReminderRules(list)
                        ReminderEngine.cancel(this@ReminderManageActivity, rule.id)
                        render()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }
    }
}
