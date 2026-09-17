package com.safphere.launcher.settings

import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.safphere.launcher.R
import com.safphere.launcher.contacts.ContactEditActivity
import com.safphere.launcher.data.Prefs
import com.safphere.launcher.databinding.ActivitySettingsBinding
import com.safphere.launcher.reboot.RebootManager
import com.safphere.launcher.setup.SetupWizardActivity
import com.safphere.launcher.sim.SimMonitor
import com.safphere.launcher.util.AvatarUtils
import kotlin.concurrent.thread

/**
 * 子女模式：PIN解锁后进行全部配置。
 * 老人日常使用（桌面）完全接触不到这里的配置项。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var pinInput = StringBuilder()
    private var confirmNewPin: String? = null   // 修改PIN时的第一次输入
    private var unlockFailCount = 0             // 解锁门连续错误计数
    private var unlockLockedUntil = 0L          // 锁定截止时间戳

    private val langOptions = listOf("普通话" to "zh-CN", "粤语" to "zh-HK", "英语" to "en-US")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        buildPinPad()
        binding.btnBack.setOnClickListener { finish() }
        bindRows()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_WALLPAPER && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri == null) {
                toast("没有选择图片")
                return
            }
            toast("正在导入壁纸…")
            thread {
                val ok = com.safphere.launcher.home.Wallpaper.setFromUri(this, uri)
                runOnUiThread {
                    toast(if (ok) "壁纸已设置，回到桌面即可看到" else "这张图片读取失败，换一张试试")
                }
            }
        }
    }

    private fun refreshAll() {
        if (binding.settingsContent.visibility == View.VISIBLE) {
            renderStatusRows()
            renderContactRows()
        }
    }

    // ================= PIN 解锁 =================

    private fun buildPinPad() {
        val pad = binding.pinPad
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
        for (k in keys) {
            val btn = TextView(this).apply {
                text = k
                textSize = 28f
                setTextColor(Color.parseColor("#212121"))
                gravity = Gravity.CENTER
                background = AvatarUtils.circleBg(Color.parseColor("#F1F3F5"))
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = dp(72)
                    height = dp(64)
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                    setGravity(Gravity.CENTER)
                }
                setOnClickListener { onKey(k) }
            }
            pad.addView(btn)
        }
        binding.pinHint.text = "默认密码 1234（进入后请修改）"
    }

    private fun onKey(k: String) {
        when {
            k == "⌫" -> if (pinInput.isNotEmpty()) pinInput.deleteCharAt(pinInput.length - 1)
            k == "✓" -> submitPin()
            pinInput.length < 4 -> pinInput.append(k)
            else -> return
        }
        renderDots()
    }

    private fun renderDots() {
        val n = pinInput.length
        binding.pinDots.text = buildString {
            repeat(n) { append("● ") }
            repeat(4 - n) { append("○ ") }
        }.trim()
    }

    private fun submitPin() {
        val entered = pinInput.toString()
        if (confirmNewPin != null) {
            // 修改PIN第二步：确认新密码
            if (entered == confirmNewPin) {
                Prefs.pin = entered
                confirmNewPin = null
                pinInput.clear()
                renderDots()
                toast("PIN 已修改")
                unlock()
            } else {
                confirmNewPin = null
                pinInput.clear()
                renderDots()
                toast("两次输入不一致，请重新操作")
            }
            return
        }
        // 解锁门防爆破：连续错 5 次，锁定 60 秒
        if (unlockLockedUntil > System.currentTimeMillis()) {
            val left = (unlockLockedUntil - System.currentTimeMillis()) / 1000 + 1
            pinInput.clear()
            renderDots()
            toast("错误次数过多，请 ${left} 秒后再试")
            return
        }
        if (entered == Prefs.pin) {
            unlockFailCount = 0
            pinInput.clear()
            renderDots()
            unlock()
        } else {
            pinInput.clear()
            renderDots()
            unlockFailCount++
            if (unlockFailCount >= 5) {
                unlockLockedUntil = System.currentTimeMillis() + 60_000
                unlockFailCount = 0
                toast("错误次数过多，已锁定 60 秒")
            } else {
                toast("密码不对（剩 ${5 - unlockFailCount} 次机会）")
            }
        }
    }

    private fun unlock() {
        binding.pinGate.visibility = View.GONE
        binding.settingsContent.visibility = View.VISIBLE
        renderStatusRows()
        renderContactRows()
    }

    // ================= 配置行 =================

    private fun bindRows() {
        binding.rowAddContact.setOnClickListener {
            startActivity(Intent(this, ContactEditActivity::class.java))
        }
        binding.rowApps.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        binding.swShowAllApps.isChecked = Prefs.showAllApps
        binding.swShowAllApps.setOnCheckedChangeListener { _, checked ->
            Prefs.showAllApps = checked
        }
        binding.rowEmergency.setOnClickListener { pickEmergencyContacts() }
        binding.rowPermCheck.setOnClickListener {
            startActivity(android.content.Intent(this, com.safphere.launcher.perm.PermissionStatusActivity::class.java))
        }
        binding.rowContactSize.setOnClickListener { pickColumns("联系人大小", 1, 3, Prefs.contactColumns) { Prefs.contactColumns = it; renderStatusRows() } }
        binding.rowAppSize.setOnClickListener { pickColumns("应用图标大小", 2, 4, Prefs.appColumns) { Prefs.appColumns = it; renderStatusRows() } }
        binding.rowDock.setOnClickListener { pickDockApps() }

        // ----- 流量智能管理 -----
        binding.swFlow.isChecked = Prefs.flowEnabled
        binding.swFlow.setOnCheckedChangeListener { _, checked ->
            Prefs.flowEnabled = checked
        }
        binding.rowFlowCarrier.setOnClickListener { pickCarrier() }
        binding.rowFlowQuery.setOnClickListener { editFlowQuery() }
        binding.rowFlowThreshold.setOnClickListener {
            editString("剩余流量阈值（MB）", Prefs.flowThresholdMb.toString()) { s ->
                s.toIntOrNull()?.takeIf { it in 1..10240 }?.let { Prefs.flowThresholdMb = it }
                renderStatusRows()
            }
        }
        binding.rowFlowBillingDay.setOnClickListener {
            editString("月结日（每月几号，1-28）", Prefs.flowBillingDay.toString()) { s ->
                s.toIntOrNull()?.takeIf { it in 1..28 }?.let { Prefs.flowBillingDay = it }
                renderStatusRows()
            }
        }
        binding.rowFlowQueryNow.setOnClickListener {
            val ok = com.safphere.launcher.flow.FlowMonitor.queryNow(this)
            toast(if (ok) "查询短信已发送，约1分钟后收到回复"
                  else "发送失败：请到「权限自检与授权」开启短信权限（或20分钟内已查过）")
            renderStatusRows()
        }
        binding.rowFlowStatus.setOnClickListener {
            com.safphere.launcher.flow.FlowMonitor.queryNow(this)
            renderStatusRows()
        }

        // ----- 健康提醒 -----
        binding.swReminder.isChecked = Prefs.reminderEnabled
        binding.swReminder.setOnCheckedChangeListener { _, checked ->
            Prefs.reminderEnabled = checked
            if (checked) {
                com.safphere.launcher.reminder.ReminderEngine.scheduleAll(this)
                toast("已开启健康提醒")
            } else {
                com.safphere.launcher.reminder.ReminderEngine.cancelAll(this)
                toast("已关闭全部提醒")
            }
            renderStatusRows()
        }
        binding.rowReminders.setOnClickListener {
            startActivity(android.content.Intent(this,
                com.safphere.launcher.reminder.ReminderManageActivity::class.java))
        }

        // ----- 勿扰时段（健康提醒夜间静默，推迟到时段结束） -----
        binding.swQuiet.isChecked = Prefs.quietEnabled
        binding.swQuiet.setOnCheckedChangeListener { _, checked ->
            Prefs.quietEnabled = checked
            toast(if (checked) "已开启夜间勿扰，提醒将推迟到早上"
                  else "已关闭勿扰，提醒全天正常")
            renderStatusRows()
        }
        binding.rowQuietStart.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                Prefs.quietStartMin = h * 60 + m
                renderStatusRows()
            }, Prefs.quietStartMin / 60 % 24, Prefs.quietStartMin % 60, true).show()
        }
        binding.rowQuietEnd.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                Prefs.quietEndMin = h * 60 + m
                renderStatusRows()
            }, Prefs.quietEndMin / 60 % 24, Prefs.quietEndMin % 60, true).show()
        }

        // 提醒准点性：精确闹钟被拒时引导到系统"闹钟和提醒"授权页
        binding.rowExactAlarm.setOnClickListener {
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val ok = runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
            if (android.os.Build.VERSION.SDK_INT >= 31 && !ok) {
                runCatching {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:$packageName")))
                    toast("请允许「闹钟和提醒」，提醒将准点触发")
                }.onFailure { toast("无法打开系统设置") }
            } else {
                toast("提醒准点性已就绪 ✓")
            }
        }

        // ----- 便捷页：家庭地址（走失求助大字卡） -----
        binding.rowHomeAddress.setOnClickListener {
            val input = EditText(this).apply {
                setText(Prefs.homeAddress)
                hint = "例如：XX省XX市XX区XX路XX号 或 小区名"
                textSize = 16f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                maxLines = 3
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            AlertDialog.Builder(this)
                .setTitle("家庭地址（老人走失时大字显示+一键导航）")
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
                    Prefs.homeAddress = input.text.toString().trim()
                    toast(if (Prefs.homeAddress.isBlank()) "已清空地址" else "已保存，便捷页可见")
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // ----- 远程状态查询（LLZT 短信） -----
        binding.swStatusSms.isChecked = Prefs.statusSmsEnabled
        binding.swStatusSms.setOnCheckedChangeListener { _, checked ->
            Prefs.statusSmsEnabled = checked
            toast(if (checked) "已开启：子女发短信 LLZT 可查询状态"
                  else "已关闭远程状态查询")
        }

        // ----- 生活页：天气城市 -----
        // ----- 悬浮球 -----
        binding.swFloatingBall.isChecked = Prefs.floatingBallEnabled
        binding.swFloatingBall.setOnCheckedChangeListener { _, checked ->
            Prefs.floatingBallEnabled = checked
            if (checked) {
                if (!com.safphere.launcher.perm.PermCatalog.accessibilityEnabled(this)) {
                    // 悬浮球依赖 TYPE_ACCESSIBILITY_OVERLAY，需先开无障碍
                    toast("悬浮球需要先开启无障碍服务")
                    runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                } else {
                    com.safphere.launcher.agent.FloatingBallService.start(this)
                    toast("悬浮球已开启")
                }
            } else {
                com.safphere.launcher.agent.FloatingBallService.stop(this)
                toast("悬浮球已关闭")
            }
            renderStatusRows()
        }

        // ----- 功能开关 -----
        binding.swSos.isChecked = Prefs.sosEnabled
        binding.swSos.setOnCheckedChangeListener { _, checked ->
            Prefs.sosEnabled = checked
            toast(if (checked) "已开启紧急求助，回桌面可见求助条" else "已关闭紧急求助")
        }
        binding.swAi.isChecked = Prefs.aiEnabled
        binding.swAi.setOnCheckedChangeListener { _, checked ->
            Prefs.aiEnabled = checked
            toast(if (checked) "已开启AI问答，生活页可见入口" else "已关闭AI问答")
        }

        // ----- 防沉迷 -----
        binding.swGuard.isChecked = Prefs.guardEnabled
        binding.swGuard.setOnCheckedChangeListener { _, checked ->
            if (com.safphere.launcher.BuildConfig.DEBUG) {
                runCatching {
                    java.io.File(filesDir, "guard_heartbeat.txt").appendText(
                        "SETTINGS listener checked=" + checked + " | ")
                }
            }
            Prefs.guardEnabled = checked
            if (checked) {
                com.safphere.launcher.guard.GuardWatchdogReceiver.scheduleNext(this)
                com.safphere.launcher.guard.GuardTicker.restart(this)
            } else {
                com.safphere.launcher.guard.GuardWatchdogReceiver.cancel(this)
                com.safphere.launcher.guard.GuardTicker.stop()
                com.safphere.launcher.guard.GuardEnforcer.unhideIfAllowed(this)
            }
            renderStatusRows()
        }
        binding.rowGuardRules.setOnClickListener {
            startActivity(android.content.Intent(this, com.safphere.launcher.guard.GuardRuleListActivity::class.java))
        }
        binding.rowGuardAccess.setOnClickListener {
            runCatching { startActivity(android.content.Intent(
                android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                .onFailure { toast("无法打开系统设置") }
        }
        binding.rowWallpaper.setOnClickListener {
            val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            runCatching { startActivityForResult(
                Intent.createChooser(pick, "选择壁纸图片"), REQ_WALLPAPER) }
                .onFailure { toast("没有可用的图片选择器") }
        }
        binding.rowWallpaperReset.setOnClickListener {
            com.safphere.launcher.home.Wallpaper.clear(this)
            toast("已恢复默认背景，回到桌面即可看到")
        }

        binding.rowWeatherCity.setOnClickListener { pickWeatherLocation() }
        binding.swSimCheck.isChecked = Prefs.simCheckEnabled
        binding.swSimCheck.setOnCheckedChangeListener { _, checked ->
            Prefs.simCheckEnabled = checked
            SimMonitor.scheduleNext(this)
        }
        binding.rowSimTime.setOnClickListener { pickTime() }

        binding.rowAiLang.setOnClickListener { pickLang() }
        binding.rowAiUrl.setOnClickListener { editString("接口地址", Prefs.aiUrl) { Prefs.aiUrl = it } }
        binding.rowAiModel.setOnClickListener { editString("模型", Prefs.aiModel) { Prefs.aiModel = it } }
        binding.rowAiKey.setOnClickListener { editString("API Key", Prefs.aiKey, masked = true) { Prefs.aiKey = it; renderStatusRows() } }
        binding.rowAiTest.setOnClickListener { testAi() }

        binding.rowDoStatus.setOnClickListener { showDoHelp() }
        binding.rowA11y.setOnClickListener { openAccessibility() }

        binding.rowSpeak.setOnClickListener {
            Prefs.speakEnabled = !Prefs.speakEnabled
            renderStatusRows()
        }
        binding.rowPin.setOnClickListener { startPinChange() }
        binding.rowWizard.setOnClickListener {
            Prefs.firstRunDone = true
            startActivity(Intent(this, SetupWizardActivity::class.java))
        }
        binding.rowExitLauncher.setOnClickListener { exitLauncher() }
        binding.rowAbout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("乐龄桌面 v1.0")
                .setMessage("给爸妈用的简洁桌面：点头像打电话、电话卡异常自动提醒一键重启、常用应用白名单、AI语音问答（支持配置方言引擎）。\n\n由子女在「子女模式」中完成全部配置。")
                .setPositiveButton("好的", null)
                .show()
        }
    }

    private fun renderStatusRows() {
        binding.rowSimTime.text = String.format("检测时间：%02d:%02d", Prefs.simHour, Prefs.simMinute)
        binding.rowAiLang.text = "识别语言：" + (langOptions.firstOrNull { it.second == Prefs.aiLang }?.first ?: Prefs.aiLang)
        binding.rowAiModel.text = "模型：${Prefs.aiModel}"
        binding.rowAiKey.text = if (Prefs.aiKey.isBlank()) "API Key（未设置）"
        else "API Key（${Prefs.aiKey.take(3)}****${Prefs.aiKey.takeLast(2)}）"
        binding.rowAiRead.text = "朗读回答：${if (Prefs.aiReadAloud) "开" else "关"}"
        binding.rowSpeak.text = "操作语音播报：${if (Prefs.speakEnabled) "开" else "关"}"
        binding.rowAiRead.setOnClickListener {
            Prefs.aiReadAloud = !Prefs.aiReadAloud
            renderStatusRows()
        }

        val isDo = RebootManager.isDeviceOwner(this)
        binding.rowDoStatus.text = if (isDo) "设备管理员：已激活（一键静默重启可用）✓"
        else "设备管理员：未激活（点我查看激活方法）"
        binding.rowDoStatus.setTextColor(if (isDo) Color.parseColor("#2E7D32") else Color.parseColor("#212121"))

        renderFlowRows()
        binding.rowGuardAccess.text = "使用情况访问：" +
            if (com.safphere.launcher.guard.AppGuard.hasUsageAccess(this)) "已授权 ✓"
            else "未授权（每日/每次限制需要，点我授权）"
        binding.rowWeatherCity.text = if (Prefs.weatherAutoLocation)
            "天气位置：自动定位" + (if (Prefs.weatherLocateName.isNotBlank())
                "（${Prefs.weatherLocateName}）" else "")
        else "天气城市：${Prefs.weatherCity}"
        val reminders = Prefs.reminderRules()
        binding.rowReminders.text = "管理提醒（定时+按天气）：共${reminders.size}项，已启用" +
            reminders.count { it.enabled } + "项"
        binding.rowQuietStart.text = "勿扰开始：" + String.format("%02d:%02d",
            Prefs.quietStartMin / 60 % 24, Prefs.quietStartMin % 60)
        binding.rowQuietEnd.text = "勿扰结束：" + String.format("%02d:%02d",
            Prefs.quietEndMin / 60 % 24, Prefs.quietEndMin % 60)
        binding.rowHomeAddress.text = if (Prefs.homeAddress.isBlank()) "家庭地址（走失求助，大字卡+导航）"
        else "家庭地址：" + Prefs.homeAddress.take(12) + if (Prefs.homeAddress.length > 12) "…" else ""
        val exactOk = runCatching {
            (getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()
        }.getOrDefault(true)
        binding.rowExactAlarm.text = if (exactOk) "提醒准点性：精确闹钟已就绪 ✓"
        else "提醒准点性：未授权（提醒可能晚几分钟，点我授权）" 
        binding.rowContactSize.text = "联系人大小：每行${Prefs.contactColumns}个" +
            if (Prefs.contactColumns == 1) "（特大）" else if (Prefs.contactColumns == 2) "（大）" else "（中）"
        binding.rowAppSize.text = "应用图标大小：每行${Prefs.appColumns}个"
        val emergencyNames = Prefs.contacts().filter { it.emergency }.map { it.name }
        binding.rowEmergency.text = "紧急联系人：" +
            if (emergencyNames.isEmpty()) "未设置"
            else "${emergencyNames.size}位（${emergencyNames.joinToString("、")}）"
        val dockNames = Prefs.dockApps().mapNotNull { pkg ->
            runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            }.getOrNull()
        }
        binding.rowDock.text = "底部快捷栏：" +
            (if (dockNames.isEmpty()) "默认（电话/短信/相机）" else dockNames.joinToString("、"))
    }

    /** 天气位置：自动定位（设备位置）或手动输入城市；切换后立即重新获取 */
    private fun pickWeatherLocation() {
        val options = arrayOf("自动定位（用设备位置获取天气）", "手动输入城市")
        val current = if (Prefs.weatherAutoLocation) 0 else 1
        AlertDialog.Builder(this)
            .setTitle("天气位置")
            .setSingleChoiceItems(options, current) { d, which ->
                if (which == 0) {
                    Prefs.weatherAutoLocation = true
                    Prefs.weatherFetchedAt = 0L   // 立即触发重新获取
                    toast("已切换为自动定位，回桌面翻到生活页即可看到")
                } else {
                    Prefs.weatherAutoLocation = false
                    d.dismiss()
                    editString("天气城市（如：北京、成都）", Prefs.weatherCity) { s ->
                        if (s.isNotBlank()) {
                            Prefs.weatherCity = s
                            Prefs.weatherLatLon = ""          // 清缓存 → 自动重新定位
                            Prefs.weatherFetchedAt = 0L       // 立即触发重新获取
                        }
                        renderStatusRows()
                    }
                    return@setSingleChoiceItems
                }
                renderStatusRows()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 底部快捷栏选择：多选应用，最多3个 */
    private fun pickDockApps() {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString() }
        val labels = apps.map { it.loadLabel(pm).toString() }
        val selected = Prefs.dockApps().toMutableList()

        val checks = BooleanArray(apps.size) { i -> apps[i].activityInfo.packageName in selected }
        var listView: android.widget.ListView? = null

        val dlg = AlertDialog.Builder(this)
            .setTitle("底部快捷栏（最多选3个）")
            .setMultiChoiceItems(labels.toTypedArray(), checks) { _, which, isChecked ->
                val pkg = apps[which].activityInfo.packageName
                if (isChecked) {
                    if (selected.size >= 3) {
                        Toast.makeText(this, "最多放3个，先取消其他的", Toast.LENGTH_SHORT).show()
                        checks[which] = false
                        listView?.setItemChecked(which, false)
                    } else selected.add(pkg)
                } else selected.remove(pkg)
            }
            .setPositiveButton("保存") { _, _ ->
                Prefs.saveDockApps(selected)
                renderStatusRows()
                Toast.makeText(this, "已保存，返回桌面即可看到", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        dlg.show()
        listView = dlg.listView
    }

    /** 紧急联系人配置：多选桌面联系人，勾选者为 SOS 呼叫/短信对象 */
    private fun pickEmergencyContacts() {
        val contacts = Prefs.contacts()
        if (contacts.isEmpty()) {
            Toast.makeText(this, "请先在桌面添加联系人", Toast.LENGTH_SHORT).show()
            return
        }
        val names = contacts.map { it.name }.toTypedArray()
        val checks = BooleanArray(contacts.size) { contacts[it].emergency }
        AlertDialog.Builder(this)
            .setTitle("紧急联系人（SOS 呼叫/短信对象）")
            .setMultiChoiceItems(names, checks) { _, which, isChecked ->
                contacts[which].emergency = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                Prefs.saveContacts(contacts)
                renderStatusRows()
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 单选列数（1-4），选完立即生效 */
    private fun pickColumns(title: String, min: Int, max: Int, current: Int, save: (Int) -> Unit) {
        val labels = (min..max).map { "每行 $it 个" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, current - min) { dialog, which ->
                save(min + which)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderFlowRows() {
        val carriers = com.safphere.launcher.flow.FlowMonitor.CARRIERS
        val c = carriers.getOrElse(Prefs.flowCarrier) { carriers[0] }
        binding.rowFlowCarrier.text = "运营商：${c.first}"
        binding.rowFlowQuery.text = "查询短信：${Prefs.flowQueryNumber} 发送 ${Prefs.flowQueryText}"
        binding.rowFlowThreshold.text = "剩余流量阈值：${Prefs.flowThresholdMb}MB"
        binding.rowFlowBillingDay.text = "月结日：每月${Prefs.flowBillingDay}号"
        binding.rowFlowStatus.text = when {
            Prefs.flowDataOffByUs -> "状态：剩余不足已关闭数据（${Prefs.flowBillingDay}号自动恢复）"
            Prefs.flowRemainingMb >= 0 -> "状态：剩余 " +
                com.safphere.launcher.flow.FlowMonitor.fmtMb(Prefs.flowRemainingMb)
            else -> "状态：未查询（点立即查询试一次）"
        }
    }

    private fun pickCarrier() {
        val carriers = com.safphere.launcher.flow.FlowMonitor.CARRIERS
        val labels = carriers.map { "${it.first}（${it.second}）" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择运营商")
            .setSingleChoiceItems(labels, Prefs.flowCarrier) { dialog, which ->
                Prefs.flowCarrier = which
                // 切换运营商时重置默认查询号码与内容
                Prefs.flowQueryNumber = carriers[which].second
                Prefs.flowQueryText = carriers[which].third
                renderFlowRows()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 分别编辑查询号码和短信内容 */
    private fun editFlowQuery() {
        val input = android.widget.EditText(this).apply {
            setText("${Prefs.flowQueryNumber}|${Prefs.flowQueryText}")
            hint = "格式：号码|短信内容，例如 10086|CXLL"
            textSize = 16f
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle("查询短信（号码|内容）")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val parts = input.text.toString().trim().split("|")
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    Prefs.flowQueryNumber = parts[0].trim()
                    Prefs.flowQueryText = parts[1].trim()
                    renderFlowRows()
                } else toast("格式不对，应为：号码|内容")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderContactRows() {
        val box = binding.contactRows
        box.removeAllViews()
        val list = Prefs.contacts()
        if (list.isEmpty()) {
            box.addView(smallText("还没有联系人，点上面「添加联系人」。"))
            return
        }
        for (c in list) {
            box.addView(smallText("${c.avatar.removePrefix("preset:").ifBlank { "👤" }} ${c.name}  ${c.phone}" +
                if (c.shortNum.isNotBlank()) " / 短号 ${c.shortNum}" else "").apply {
                setOnClickListener { startActivity(ContactEditActivity.edit(this@SettingsActivity, c.id)) }
            })
        }
    }

    private fun smallText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.parseColor("#424242"))
        setPadding(28, 26, 28, 26)
        background = androidx.core.content.ContextCompat.getDrawable(
            this@SettingsActivity, com.safphere.launcher.R.drawable.bg_card
        )
    }

    private fun pickTime() {
        TimePickerDialog(this, { _, h, m ->
            Prefs.simHour = h
            Prefs.simMinute = m
            SimMonitor.scheduleNext(this)
            renderStatusRows()
        }, Prefs.simHour, Prefs.simMinute, true).show()
    }

    private fun pickLang() {
        val labels = langOptions.map { it.first }.toTypedArray()
        val current = langOptions.indexOfFirst { it.second == Prefs.aiLang }.let { if (it < 0) 0 else it }
        AlertDialog.Builder(this)
            .setTitle("识别语言")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Prefs.aiLang = langOptions[which].second
                renderStatusRows()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editString(title: String, init: String, masked: Boolean = false, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(init)
            textSize = 16f
            if (masked) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("保存") { _, _ -> save(input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun testAi() {
        toast("正在测试…")
        if (Prefs.aiKey.isBlank()) {
            toast("请先填写 API Key")
            return
        }
        thread {
            val ok = runCatching {
                LlmTest.chat(Prefs.aiUrl, Prefs.aiKey, Prefs.aiModel)
            }
            runOnUiThread {
                ok.onSuccess { AlertDialog.Builder(this)
                    .setTitle("连接成功 ✓")
                    .setMessage("小乐回复：$it")
                    .setPositiveButton("好", null)
                    .show() }
                ok.onFailure { AlertDialog.Builder(this)
                    .setTitle("连接失败")
                    .setMessage(it.message ?: "未知错误")
                    .setPositiveButton("好", null)
                    .show() }
            }
        }
    }

    private fun showDoHelp() {
        val cmd = "adb shell dpm set-device-owner com.safphere.launcher/.admin.ElderDeviceAdminReceiver"
        val msg = "「一键静默重启」需要把乐龄桌面设为设备所有者（适合新手机首次配置）：\n\n" +
            "1. 手机恢复出厂后不登录任何账号\n" +
            "2. 电脑连接手机，执行：\n\n$cmd\n\n" +
            "3. 提示 Success 后，「一键重启」即可无感静默重启\n\n" +
            "没有电脑也可以用无障碍方式（点上一行「开启无障碍服务」）。"
        AlertDialog.Builder(this)
            .setTitle(if (RebootManager.isDeviceOwner(this)) "设备管理员已激活 ✓" else "如何激活设备管理员")
            .setMessage(msg)
            .setPositiveButton("复制命令") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("cmd", cmd))
                toast("命令已复制")
            }
            .setNeutralButton("关闭", null)
            .show()
    }

    private fun openAccessibility() {
        toast("请找到「乐龄桌面（用于一键重启）」并开启")
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { toast("无法打开系统设置") }
    }

    private fun startPinChange() {
        confirmNewPin = null
        editPinStep1()
    }

    private fun editPinStep1() {
        // 先验证旧PIN，防他人直接改密；5次错误锁定
        val old = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "输入当前 PIN 密码"
            textSize = 20f
        }
        AlertDialog.Builder(this)
            .setTitle("验证当前密码")
            .setView(old)
            .setPositiveButton("确定") { _, _ ->
                if (pinFailCount >= 5) { toast("错误次数过多，稍后再试"); return@setPositiveButton }
                if (old.text.toString().trim() != Prefs.pin) {
                    pinFailCount++
                    toast("密码错误（剩余${5 - pinFailCount}次机会）")
                    return@setPositiveButton
                }
                pinFailCount = 0
                promptNewPin()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private var pinFailCount = 0

    private fun promptNewPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "输入新的4位数字"
            textSize = 20f
        }
        AlertDialog.Builder(this)
            .setTitle("设置新 PIN")
            .setView(input)
            .setPositiveButton("下一步") { _, _ ->
                val p1 = input.text.toString().trim()
                if (p1.length != 4) { toast("必须是4位数字"); return@setPositiveButton }
                editPinStep2(p1)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editPinStep2(p1: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "再输入一次"
            textSize = 20f
        }
        AlertDialog.Builder(this)
            .setTitle("设置新 PIN（第2步）")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                if (input.text.toString().trim() == p1) {
                    Prefs.pin = p1
                    toast("PIN 已修改")
                } else toast("两次不一致，未修改")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exitLauncher() {
        runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
            .onFailure {
                // 打开系统桌面选择器
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(Intent.createChooser(home, "选择桌面").setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        toast("在系统设置中选择其他桌面")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQ_WALLPAPER = 2001
    }
}

/** 测试连接用（与主客户端同协议） */
private object LlmTest {
    fun chat(url: String, key: String, model: String): String =
        com.safphere.launcher.ai.LlmClient.chat(
            url, key, model,
            listOf(com.safphere.launcher.ai.LlmClient.Msg("user", "你好"))
        )
}
