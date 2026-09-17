package com.safphere.launcher.reminder

import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.safphere.launcher.data.Prefs
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * 提醒编辑（子女模式）：大按钮操作，±5分钟调时间，星期点选；
 * 天气类支持高温/低温/雨雪三种条件与阈值；新建时可从常用模板一键填充。
 */
class ReminderEditActivity : AppCompatActivity() {

    private var rule = ReminderRule(id = UUID.randomUUID().toString())

    private lateinit var etName: EditText
    private lateinit var etMessage: EditText
    private lateinit var rgKind: RadioGroup
    private lateinit var timeBox: LinearLayout
    private lateinit var weatherBox: LinearLayout
    private lateinit var tvTime: TextView
    private lateinit var tvThreshold: TextView
    private lateinit var tvCond: TextView
    private lateinit var boxIcons: LinearLayout
    private lateinit var presetBox: LinearLayout
    private val dayButtons = mutableMapOf<Int, TextView>()
    private lateinit var chipEveryday: TextView

    private val icons = listOf("💊", "🩺", "💧", "🚶", "🛌", "🍚", "📞", "⏰", "🥵", "🧣", "☔")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("rule_id")?.let { rid ->
            Prefs.reminderRules().firstOrNull { it.id == rid }?.let { rule = it }
        }
        setContentView(buildUi())
        fill()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(0xFF616161.toInt())
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun chipBg(selected: Boolean) = GradientDrawable().apply {
        setColor(if (selected) 0xFF1E88E5.toInt() else 0xFFF1F3F5.toInt())
        cornerRadius = dp(22).toFloat()
    }

    private fun paintChip(c: TextView, selected: Boolean) {
        c.background = chipBg(selected)
        c.setTextColor(if (selected) Color.WHITE else 0xFF424242.toInt())
    }

    private fun chip(text: String, selected: Boolean, onClick: (TextView) -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), 0, dp(16), 0)
            paintChip(this, selected)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(8) }
            setOnClickListener { onClick(this) }
        }

    private fun bigButton(text: String, color: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor(color)); cornerRadius = dp(16).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun newRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun buildUi(): View {
        val scroll = android.widget.ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "← 返回"
            textSize = 18f
            setTextColor(0xFF1565C0.toInt())
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
        })

        // ---- 常用模板（仅新建时显示） ----
        presetBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(presetBox)
        presetBox.addView(label("常用模板（点一个快速填好）"))
        val presetRow1 = newRow(); presetBox.addView(presetRow1)
        addTimePreset(presetRow1, "💊 早降压药", "吃降压药", 8, 0, "饭后吃，别忘啦")
        addTimePreset(presetRow1, "🩺 量血压", "量血压", 21, 0, "安静坐5分钟再量")
        addTimePreset(presetRow1, "🛌 该休息", "该休息了", 21, 30, "")
        val presetRow2 = newRow(); presetBox.addView(presetRow2)
        addWeatherPreset(presetRow2, "🥵 高温关怀", "天气炎热注意", ReminderRule.W_HOT, 33,
            "天热血压易波动，记得量血压、多喝水")
        addWeatherPreset(presetRow2, "☔ 雨雪提醒", "雨雪天当心", ReminderRule.W_RAIN, 0,
            "出门带伞，地滑慢走")

        // ---- 图标 ----
        root.addView(label("选个图标"))
        boxIcons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(boxIcons)

        // ---- 名称 ----
        root.addView(label("提醒名称"))
        etName = EditText(this).apply {
            hint = "例如：吃血压药"
            textSize = 18f
        }
        root.addView(etName)

        // ---- 类型 ----
        root.addView(label("提醒方式"))
        rgKind = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        rgKind.addView(RadioButton(this).apply {
            text = "⏰ 每天定时"
            textSize = 17f
            id = View.generateViewId()
            layoutParams = RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(24) }
        })
        rgKind.addView(RadioButton(this).apply {
            text = "🌤️ 看天气提醒"
            textSize = 17f
            id = View.generateViewId()
        })
        root.addView(rgKind)
        rgKind.setOnCheckedChangeListener { _, _ -> syncKindBoxes() }

        // ---- 定时区 ----
        timeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(timeBox)
        timeBox.addView(label("提醒时间（点时间可精确设置）"))
        tvTime = TextView(this).apply {
            textSize = 40f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF1565C0.toInt())
            setOnClickListener { pickTimeDialog() }
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFFF5F9FF.toInt()); cornerRadius = dp(12).toFloat()
            }
        }
        timeBox.addView(tvTime, ViewGroup.LayoutParams.MATCH_PARENT, dp(72))
        val shiftRow = newRow(); timeBox.addView(shiftRow)
        shiftRow.addView(bigButton("− 5分钟", "#F1F3F5") { shiftTime(-5) }.also {
            it.setTextColor(0xFF424242.toInt())
        })
        shiftRow.addView(bigButton("＋ 5分钟", "#F1F3F5") { shiftTime(5) }.also {
            it.setTextColor(0xFF424242.toInt())
        })
        timeBox.addView(label("重复（不选=每天）"))
        val dayRow1 = newRow(); timeBox.addView(dayRow1)
        addDayChip(dayRow1, Calendar.SUNDAY, "日"); addDayChip(dayRow1, Calendar.MONDAY, "一")
        addDayChip(dayRow1, Calendar.TUESDAY, "二"); addDayChip(dayRow1, Calendar.WEDNESDAY, "三")
        val dayRow2 = newRow(); timeBox.addView(dayRow2)
        addDayChip(dayRow2, Calendar.THURSDAY, "四"); addDayChip(dayRow2, Calendar.FRIDAY, "五")
        addDayChip(dayRow2, Calendar.SATURDAY, "六")
        chipEveryday = chip("每天", rule.days.isEmpty()) {
            rule.days = emptySet(); refreshDays()
        }
        dayRow2.addView(chipEveryday)

        // ---- 天气区 ----
        weatherBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(weatherBox)
        weatherBox.addView(label("什么天气时提醒"))
        tvCond = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFFF1F3F5.toInt()); cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { pickWeatherKind() }
        }
        weatherBox.addView(tvCond, ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        weatherBox.addView(label("温度阈值（仅高温/低温需要）"))
        tvThreshold = TextView(this).apply {
            textSize = 30f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        val thRow = newRow(); weatherBox.addView(thRow)
        thRow.addView(bigButton("− 1°C", "#F1F3F5") { shiftThreshold(-1) }.also {
            it.setTextColor(0xFF424242.toInt())
        })
        thRow.addView(tvThreshold, LinearLayout.LayoutParams(0, dp(60), 1f))
        thRow.addView(bigButton("＋ 1°C", "#F1F3F5") { shiftThreshold(1) }.also {
            it.setTextColor(0xFF424242.toInt())
        })

        // ---- 附加语 ----
        root.addView(label("想多说一句（可不填）"))
        etMessage = EditText(this).apply {
            hint = "例如：天热血压易波动，记得量血压"
            textSize = 16f
        }
        root.addView(etMessage)

        // ---- 保存/删除 ----
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(20)) })
        val btnRow = newRow(); root.addView(btnRow)
        if (ruleExists()) btnRow.addView(bigButton("删除", "#E53935") { delete() })
        btnRow.addView(bigButton("保存", "#1E88E5") { save() })
        return scroll
    }

    private fun addDayChip(row: LinearLayout, day: Int, name: String) {
        val c = chip(name, rule.days.contains(day)) {
            val d = rule.days.toMutableSet()
            if (d.contains(day)) d.remove(day) else d.add(day)
            rule.days = d
            refreshDays()
        }
        dayButtons[day] = c
        row.addView(c)
    }

    private fun addTimePreset(
        row: LinearLayout, text: String, name: String, hour: Int, minute: Int, msg: String
    ) {
        row.addView(chip(text, false) {
            rule = rule.copy(
                icon = "💊", label = name, kind = ReminderRule.KIND_TIME,
                hour = hour, minute = minute, message = msg)
            fill()
            Toast.makeText(this, "已填好，可再调整后保存", Toast.LENGTH_SHORT).show()
        })
    }

    private fun addWeatherPreset(
        row: LinearLayout, text: String, name: String, wk: Int, threshold: Int, msg: String
    ) {
        row.addView(chip(text, false) {
            rule = rule.copy(
                icon = if (wk == ReminderRule.W_HOT) "🥵" else "☔",
                label = name, kind = ReminderRule.KIND_WEATHER,
                weatherKind = wk, threshold = threshold, message = msg)
            fill()
            Toast.makeText(this, "已填好，可再调整后保存", Toast.LENGTH_SHORT).show()
        })
    }

    private fun ruleExists() = Prefs.reminderRules().any { it.id == rule.id }

    private fun fill() {
        etName.setText(rule.label)
        etMessage.setText(rule.message)
        rgKind.check(
            if (rule.kind == ReminderRule.KIND_TIME)
                rgKind.getChildAt(0).id else rgKind.getChildAt(1).id
        )
        refreshTime(); refreshDays(); refreshWeather(); refreshIcons(); syncKindBoxes()
    }

    private fun refreshIcons() {
        boxIcons.removeAllViews()
        for (ic in icons) {
            boxIcons.addView(chip(ic, ic == rule.icon) { c ->
                rule.icon = ic
                for (i in 0 until boxIcons.childCount) {
                    paintChip(boxIcons.getChildAt(i) as TextView, false)
                }
                paintChip(c, true)
            })
        }
    }

    private fun refreshTime() {
        tvTime.text = String.format(Locale.CHINA, "%02d:%02d", rule.hour, rule.minute)
    }

    private fun refreshDays() {
        for ((day, btn) in dayButtons) paintChip(btn, rule.days.contains(day))
        if (::chipEveryday.isInitialized) paintChip(chipEveryday, rule.days.isEmpty())
    }

    private fun refreshWeather() {
        tvCond.text = when (rule.weatherKind) {
            ReminderRule.W_HOT -> "高温时提醒（点我更换）"
            ReminderRule.W_COLD -> "低温时提醒（点我更换）"
            else -> "下雨/下雪时提醒（点我更换）"
        }
        tvThreshold.text = "${rule.threshold}°C"
        tvThreshold.visibility =
            if (rule.weatherKind == ReminderRule.W_RAIN) View.INVISIBLE else View.VISIBLE
    }

    private fun syncKindBoxes() {
        val isTime =
            rgKind.indexOfChild(rgKind.findViewById(rgKind.checkedRadioButtonId)) == 0
        rule.kind = if (isTime) ReminderRule.KIND_TIME else ReminderRule.KIND_WEATHER
        timeBox.visibility = if (isTime) View.VISIBLE else View.GONE
        weatherBox.visibility = if (isTime) View.GONE else View.VISIBLE
        presetBox.visibility = if (ruleExists()) View.GONE else View.VISIBLE
    }

    private fun shiftTime(deltaMin: Int) {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, rule.hour)
            set(Calendar.MINUTE, rule.minute)
            add(Calendar.MINUTE, deltaMin)
        }
        rule.hour = c.get(Calendar.HOUR_OF_DAY)
        rule.minute = c.get(Calendar.MINUTE)
        refreshTime()
    }

    private fun shiftThreshold(delta: Int) {
        rule.threshold = (rule.threshold + delta).coerceIn(-10, 42)
        refreshWeather()
    }

    private fun pickTimeDialog() {
        TimePickerDialog(this, { _, h, m ->
            rule.hour = h; rule.minute = m; refreshTime()
        }, rule.hour, rule.minute, true).show()
    }

    private fun pickWeatherKind() {
        val names = arrayOf(
            "高温时提醒（如：天热多喝水）",
            "低温时提醒（如：降温加衣服）",
            "下雨/下雪时提醒（带伞防滑）")
        AlertDialog.Builder(this)
            .setTitle("什么天气时提醒")
            .setSingleChoiceItems(names, rule.weatherKind) { d, which ->
                rule.weatherKind = which
                refreshWeather()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun save() {
        val name = etName.text.toString().trim()
        if (name.isBlank()) {
            Toast.makeText(this, "先写个名称，例如：吃血压药", Toast.LENGTH_SHORT).show()
            return
        }
        rule.label = name
        rule.message = etMessage.text.toString().trim()
        val list = Prefs.reminderRules()
        list.removeAll { it.id == rule.id }
        list.add(rule)
        Prefs.saveReminderRules(list)
        if (rule.enabled && Prefs.reminderEnabled) {
            ReminderEngine.scheduleNext(this, rule)
        } else {
            ReminderEngine.cancel(this, rule.id)
        }
        Toast.makeText(this, "已保存：${ReminderEngine.summary(rule)}", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun delete() {
        AlertDialog.Builder(this)
            .setTitle("删除「${rule.label}」？")
            .setPositiveButton("删除") { _, _ ->
                Prefs.saveReminderRules(Prefs.reminderRules().filter { it.id != rule.id })
                ReminderEngine.cancel(this, rule.id)
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
