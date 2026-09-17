package com.safphere.launcher.guard

import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.safphere.launcher.R
import com.safphere.launcher.data.Prefs

/**
 * 防沉迷规则管理（子女模式）：应用列表，点击进入规则编辑（每天/每次/时段/开关）。
 */
class GuardRuleListActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var apps: List<ResolveInfo>
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("防沉迷 · 应用限制")
        setContentView(buildUi())
        loadApps()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        root.addView(TextView(this).apply {
            text = "← 返回"
            textSize = 18f
            setTextColor(0xFF1565C0.toInt())
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44))
        })
        root.addView(TextView(this).apply {
            text = "点应用设置限制：每天最多用多久、每次最多用多久、几点以后不能用。留空/0 表示不限制。"
            textSize = 14f
            setTextColor(0xFF757575.toInt())
            setPadding(0, dp(6), 0, dp(6))
        })
        // 快速搜索：边输边筛
        val search = EditText(this).apply {
            hint = "🔍 搜索应用名"
            textSize = 16f
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = androidx.core.content.ContextCompat.getDrawable(
                this@GuardRuleListActivity, R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        root.addView(search)
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString()?.trim() ?: ""
                render()
            }
        })
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@GuardRuleListActivity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(recycler)
        return root
    }

    private fun loadApps() {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(main, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        render()
    }

    private fun render() {
        val pm = packageManager
        val shown = apps.filter {
            query.isBlank() || it.loadLabel(pm).toString().contains(query, true) ||
                it.activityInfo.packageName.contains(query, true)
        }
        recycler.adapter = object : RecyclerView.Adapter<VH>() {
            override fun onCreateViewHolder(parent: ViewGroup, VHType: Int) = VH(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_guard_app, parent, false)
            )

            override fun getItemCount() = shown.size

            override fun onBindViewHolder(h: VH, pos: Int) {
                val ri = shown[pos]
                val pkg = ri.activityInfo.packageName
                h.itemView.findViewById<TextView>(R.id.guardAppName).text = ri.loadLabel(pm)
                val rule = Prefs.guardRule(pkg)
                val sub = h.itemView.findViewById<TextView>(R.id.guardAppSub)
                sub.text = if (rule == null || !rule.enabled) "未限制"
                           else AppGuard.ruleSummary(rule)
                h.itemView.findViewById<View>(R.id.guardRow).setOnClickListener {
                    editRule(pkg, ri.loadLabel(pm).toString())
                }
            }
        }
    }

    private fun editRule(pkg: String, label: String) {
        val existing = Prefs.guardRule(pkg)
        val pad = dp(18)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 14f; setTextColor(0xFF616161.toInt())
            setPadding(0, dp(10), 0, dp(4))
        }
        val etDaily = EditText(this).apply {
            hint = "0 = 不限"; inputType = InputType.TYPE_CLASS_NUMBER
            setText((existing?.dailyLimitMin ?: 0).toString()); textSize = 16f
        }
        val etSession = EditText(this).apply {
            hint = "0 = 不限"; inputType = InputType.TYPE_CLASS_NUMBER
            setText((existing?.sessionLimitMin ?: 0).toString()); textSize = 16f
        }
        val tvCurfew = TextView(this).apply {
            textSize = 16f; setPadding(0, dp(8), 0, dp(8))
            text = curfewText(existing)
            setOnClickListener {
                pickTime("禁用开始时间", existing?.curfewStartMin ?: 1320) { st ->
                    pickTime("禁用结束时间", existing?.curfewEndMin ?: 360) { en ->
                        tempStart = st; tempEnd = en
                        text = curfewText(null, st, en)
                    }
                }
            }
        }
        form.addView(label("每天最多使用（分钟）")); form.addView(etDaily)
        form.addView(label("每次最多使用（分钟）")); form.addView(etSession)
        form.addView(label("禁用时段（点我设置，如 22:00 ~ 06:00）")); form.addView(tvCurfew)

        tempStart = existing?.curfewStartMin
        tempEnd = existing?.curfewEndMin

        AlertDialog.Builder(this)
            .setTitle(label)
            .setView(form)
            .setPositiveButton("保存") { _, _ ->
                val daily = etDaily.text.toString().toIntOrNull() ?: 0
                val session = etSession.text.toString().toIntOrNull() ?: 0
                if (daily == 0 && session == 0 && tempStart == null) {
                    Prefs.removeGuardRule(pkg)
                    Toast.makeText(this, "已取消限制", Toast.LENGTH_SHORT).show()
                } else {
                    Prefs.saveGuardRule(GuardRule(
                        pkg = pkg, label = label, enabled = true,
                        dailyLimitMin = daily, sessionLimitMin = session,
                        curfewStartMin = tempStart, curfewEndMin = tempEnd))
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                }
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private var tempStart: Int? = null
    private var tempEnd: Int? = null

    private fun curfewText(r: GuardRule?, st: Int? = null, en: Int? = null): String {
        val s = st ?: r?.curfewStartMin
        val e = en ?: r?.curfewEndMin
        return if (s != null && e != null)
            String.format(java.util.Locale.CHINA, "%02d:%02d ~ %02d:%02d 禁用",
                s / 60 % 24, s % 60, e / 60 % 24, e % 60)
        else "未设置（点我选择时间）"
    }

    private fun pickTime(title: String, init: Int, onPick: (Int) -> Unit) {
        TimePickerDialog(this, { _, h, m -> onPick(h * 60 + m) }, init / 60 % 24, init % 60, true)
            .apply { setTitle(title) }
            .show()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v)
}
