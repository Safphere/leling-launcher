package com.safphere.launcher.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.safphere.launcher.R
import com.safphere.launcher.ai.AiChatActivity
import com.safphere.launcher.contacts.ContactEditActivity
import com.safphere.launcher.data.Contact
import com.safphere.launcher.data.Prefs
import com.safphere.launcher.databinding.ActivityHomeBinding
import com.safphere.launcher.databinding.ItemAppBinding
import com.safphere.launcher.databinding.ItemCalDayBinding
import com.safphere.launcher.databinding.PageAppsBinding
import com.safphere.launcher.databinding.PageContactsBinding
import com.safphere.launcher.databinding.PageLifeBinding
import com.safphere.launcher.databinding.PageToolsBinding
import com.safphere.launcher.reboot.RebootManager
import com.safphere.launcher.settings.SettingsActivity
import com.safphere.launcher.sim.SimMonitor
import com.safphere.launcher.setup.SetupWizardActivity
import com.safphere.launcher.tts.Speaker
import com.safphere.launcher.util.LunarCalendar
import com.safphere.launcher.util.showBigConfirm
import com.safphere.launcher.util.showBigInfo
import com.safphere.launcher.weather.WeatherFetcher
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 主桌面（系统 Launcher）：三页结构——
 * 第0页：联系人快捷呼叫（点头像【真呼出】）；
 * 第1页：生活页（日期农历+在线天气+本月日历）；
 * 第2页：常用应用（白名单/全部，点图标启动并语音播报）。
 * 顶部大时钟/状态跨页常驻；底部四按钮导航（不依赖滑动）；
 * 子女设置入口：连点底部品牌文字5次（+PIN）。
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var contactAdapter: ContactAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val dateFmt = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)

    // 生活页状态
    private var lifeBinding: PageLifeBinding? = null
    private var shownMonth: Calendar = Calendar.getInstance()
    private var lastWeather: WeatherFetcher.Weather? = null

    // 应用列表缓存：轻量Entry（枚举/标签全在后台完成后才上主线程）
    // 冷启动秒出：onCreate 先从磁盘快照水合，后台再枚举校准
    data class AppEntry(val pkg: String, val label: String)
    private var appsCache: List<AppEntry> = emptyList()
    private var appsCacheDirty = true
    private var lastAppsSig: Int = -1
    private var lastRenderedAppsSig: Int = -1
    private val appLoadExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var debugReceiver: android.content.BroadcastReceiver? = null
    private var packageMonitor: android.content.BroadcastReceiver? = null

    // 真呼出：权限补授后的待拨联系人
    private var pendingCall: Contact? = null

    // 子女设置隐藏入口：品牌文字连点5次
    private var brandTaps = 0
    private val brandTapReset = Runnable { brandTaps = 0 }

    // 快捷栏内容签名：无变化不重建（onResume每次调用buildDock的开销优化）
    private var lastDockSig: String = "\u0000"

    // 滑动中挂起的全量重绑标记（页面静止时执行，避免 fling 掉帧）
    private var pendingRebind = false

    // 壁纸文件时间戳（变化才重刷背景）
    private var wallpaperStamp = 0L

    private val tick = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 30_000L)
        }
    }

    // 仅在拨出后开启挂断监听；连续3次未检测到通话才停（容忍拨号接通间隙），避免常驻轮询耗电
    @Volatile private var watchCall = false
    private var callMiss = 0
    private val callTick = object : Runnable {
        override fun run() {
            val inCall = com.safphere.launcher.call.CallController.isInCall(this@HomeActivity)
            binding.callChip.visibility = if (inCall) View.VISIBLE else View.GONE
            if (inCall) {
                callMiss = 0
                handler.postDelayed(this, 2_000L)
            } else if (++callMiss >= 3) {
                watchCall = false   // 确认通话结束，停止轮询
            } else {
                handler.postDelayed(this, 2_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 冷启动秒出应用页：先水合上次快照（几KB，同步无感知），后台再枚举校准
        appsCache = AppsSnapshot.load(this).map { AppEntry(it.pkg, it.label) }
        if (appsCache.isNotEmpty()) appsCacheDirty = false
        lastRenderedAppsSig = appsCache.hashCode()
        if (com.safphere.launcher.BuildConfig.DEBUG) android.util.Log.w("HomePerf", "onCreate hydrate snapshot n=${appsCache.size}")

        // 边到边绘制：内容自行留出状态栏/手势栏空间，避免遮挡
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
            )
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        contactAdapter = ContactAdapter(
            onCall = { call(it) },                       // 点击照片=直接呼叫
            onSms = { openSms(it) },                     // 长按菜单→发短信
            onEdit = { startActivity(ContactEditActivity.edit(this, it.id)) },
            onAdd = { startActivity(Intent(this, ContactEditActivity::class.java)) }
        )

        binding.pager.adapter = PagesAdapter()
        binding.pager.offscreenPageLimit = 2   // 三页常驻，翻页即时
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                renderDots(position)
                if (position == 1) maybeFetchWeather()
            }

            override fun onPageScrollStateChanged(state: Int) {
                // 滑动中攒着的大活（全量重绑）等停下再做，避免插入 fling 帧导致掉帧
                if (state == ViewPager2.SCROLL_STATE_IDLE && pendingRebind) {
                    pendingRebind = false
                    runCatching { binding.pager.adapter?.notifyDataSetChanged() }
                }
            }
        })

        // 底部：应用快捷栏（子女可配） + 固定一键重启
        binding.btnReboot.setOnClickListener { confirmReboot() }
        buildDock()

        // 异常提醒条：点击按最高优先级处理（SIM > 流量 > 电量）
        binding.alertChip.setOnClickListener {
            when {
                SimMonitor.isSimAbsent(this) == true -> SimMonitor.checkNow(this, force = true)
                Prefs.flowDataOffByUs -> showBigInfo(
                    "流量已自动关闭",
                    "本月流量快用完了（剩不到${Prefs.flowThresholdMb}MB），\n" +
                        "移动数据已暂时关闭，防止多扣话费。\n\n" +
                        "${Prefs.flowBillingDay}号月结后会自动恢复。\n" +
                        "打电话和WiFi上网不受影响。",
                    okColor = android.graphics.Color.parseColor("#FB8C00")
                )
                batteryPercent() <= 20 -> Speaker.speak("手机电量不足，请尽快充电")
                else -> Unit
            }
        }
        // 隐藏入口：连点指示点行 5 次 → 子女设置
        binding.dotsRow.setOnClickListener { onDotsTap() }

        // 通话中挂断条
        binding.callChip.setOnClickListener { onEndCallTap() }

        // debug通道：调试广播翻页（自动化测试用；仅 debug 构建注册，真机可正常滑动）
        if (com.safphere.launcher.BuildConfig.DEBUG) {
            debugReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, i: android.content.Intent) {
                    if (i.action == com.safphere.launcher.flow.FlowDebugReceiver.ACTION_INTERNAL_GOTO_PAGE) {
                        binding.pager.setCurrentItem(i.getIntExtra("page", 0), false)
                    }
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                debugReceiver,
                android.content.IntentFilter(com.safphere.launcher.flow.FlowDebugReceiver.ACTION_INTERNAL_GOTO_PAGE),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        // 应用安装/更新/卸载：实时刷新应用页（新装应用无需重启桌面）
        packageMonitor = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                // 覆盖安装会连发 REMOVED+ADDED，靠 REPLACING 标记去重
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                val pkg = intent.data?.schemeSpecificPart ?: return
                com.safphere.launcher.home.IconDiskCache.invalidate(
                    applicationContext, pkg)
                appsCacheDirty = true
                loadAppsAsync { apps ->
                    if (apps.hashCode() != lastRenderedAppsSig) {
                        lastRenderedAppsSig = apps.hashCode()
                        runCatching { binding.pager.adapter?.notifyDataSetChanged() }
                    }
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            packageMonitor,
            android.content.IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        refreshStatus()
        refreshContacts()
        renderLifeHeader()

        // 子女设置返回后立即生效（页面不重绑也要更新显隐）
        sosBarRef?.visibility = if (Prefs.sosEnabled) View.VISIBLE else View.GONE
        lifeBinding?.aiEntry?.visibility =
            if (Prefs.aiEnabled) View.VISIBLE else View.GONE

        // 自定义壁纸：设置里换图/清图后回到桌面即时生效
        com.safphere.launcher.home.Wallpaper.refreshAsync(
            applicationContext, binding.root, wallpaperStamp) { wallpaperStamp = it }

        if (!Prefs.firstRunDone) {
            Prefs.firstRunDone = true
            startActivity(Intent(this, SetupWizardActivity::class.java))
        }

        com.safphere.launcher.guard.GuardWatchdogReceiver.scheduleNext(this)
        com.safphere.launcher.guard.GuardTicker.start(this)
        SimMonitor.checkOnResume(this)
        com.safphere.launcher.flow.FlowMonitor.reopenIfBillingDay(this)
        maybeFetchWeather()
        // 应用列表增量刷新：内容真变了才整体重绑（冷启动时快照==枚举结果，一次都不用重绑）；
        // 正在滑动/落位动画中只记标记，停下再重绑，fling 帧不被大活插队
        loadAppsAsync { apps ->
            if (apps.hashCode() != lastRenderedAppsSig) {
                lastRenderedAppsSig = apps.hashCode()
                if (binding.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE) {
                    runCatching { binding.pager.adapter?.notifyDataSetChanged() }
                } else {
                    pendingRebind = true
                }
            }
        }
        buildDock()

        handler.postDelayed(tick, 30_000L)
        if (watchCall) handler.post(callTick)
    }

    override fun onPause() {
        super.onPause()
        isResumed = false
        handler.removeCallbacks(tick)
        handler.removeCallbacks(callTick)
        handler.removeCallbacks(sosStep)
        binding.callChip.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(debugReceiver) }
        runCatching { unregisterReceiver(packageMonitor) }
        // 手电筒开着时退出桌面：帮忙关掉（不留在黑暗里忘记关灯）
        if (torchOn) {
            runCatching {
                val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)
                cm.cameraIdList.firstOrNull { cid ->
                    cm.getCameraCharacteristics(cid)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }?.let { cm.setTorchMode(it, false) }
                torchOn = false
            }
        }
        lifeBinding = null
    }

    // ================= 页面导航 =================

    private fun renderDots(position: Int) {
        binding.dot0.setBackgroundResource(
            if (position == 0) R.drawable.pager_dot_on else R.drawable.pager_dot_off
        )
        binding.dot1.setBackgroundResource(
            if (position == 1) R.drawable.pager_dot_on else R.drawable.pager_dot_off
        )
        binding.dot2.setBackgroundResource(
            if (position >= 2) R.drawable.pager_dot_on else R.drawable.pager_dot_off
        )
    }

    /** 底部快捷栏：构建子女配置的应用快捷方式（最多3个） */
    /** 底部快捷栏：3 个固定槽位（可从应用页拖拽图标更换）+ 固定一键重启 */
    private fun buildDock() {
        val dockCfg = Prefs.dockApps().toMutableList()
        // 内容无变化直接跳过（onResume 高频调用，避免重复inflate+查PM）
        val sig = dockCfg.joinToString(",")
        if (sig == lastDockSig) return
        lastDockSig = sig
        val bar = binding.dockShortcuts
        bar.removeAllViews()
        val dock = dockCfg
        while (dock.size < 3) dock.add("")
        val pm = packageManager
        dock.forEachIndexed { idx, pkg ->
            val slot = layoutInflater.inflate(R.layout.item_dock_app, bar, false)
            val icon = slot.findViewById<android.widget.ImageView>(R.id.dockIcon)
            val label = slot.findViewById<android.widget.TextView>(R.id.dockLabel)
            val launch = pkg.takeIf { it.isNotBlank() }?.let { pm.getLaunchIntentForPackage(it) }
            if (launch != null) {
                icon.setImageDrawable(iconOf(pkg))
                label.text = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                slot.setOnClickListener {
                    val blk = com.safphere.launcher.guard.AppGuard.check(this, pkg)
                    if (blk != null) {
                        com.safphere.launcher.guard.GuardEnforcer.softBlock(this, blk)
                        return@setOnClickListener
                    }
                    runCatching { startActivity(launch) }
                }
                // 长按快捷图标 = 从快捷栏移除
                slot.setOnLongClickListener {
                    setDockSlot(idx, "")
                    android.widget.Toast.makeText(this, "已从快捷栏移除", android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
            } else {
                icon.setImageResource(android.R.drawable.ic_input_add)
                icon.imageTintList = android.content.res.ColorStateList.valueOf(
                    getColor(R.color.text_secondary))
                label.text = "空槽位"
                label.setTextColor(getColor(R.color.text_secondary))
            }
            slot.setOnDragListener(dockDragListener(idx))
            bar.addView(slot)
        }
    }

    /** 子女设置隐藏入口：3秒内连点5次，防止老人误触；进入还需PIN */
    private fun onDotsTap() {
        brandTaps++
        handler.removeCallbacks(brandTapReset)
        handler.postDelayed(brandTapReset, 3000)
        if (brandTaps >= 5) {
            brandTaps = 0
            handler.removeCallbacks(brandTapReset)
            Speaker.speak("已打开子女设置")
            startActivity(Intent(this, SettingsActivity::class.java))
        } else if (brandTaps >= 2) {
            android.widget.Toast.makeText(
                this, "再点 ${5 - brandTaps} 次进入子女设置", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ================= SOS 紧急求助 =================

    private var sosBarRef: TextView? = null
    private var sosCountdown = 3

    /** 按住3秒触发（防误触）：倒计时期间松开=取消；无紧急联系人时给引导 */
    private fun bindSosBar(bar: TextView) {
        sosBarRef = bar
        bar.visibility = if (Prefs.sosEnabled) View.VISIBLE else View.GONE
        bar.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> startSosCountdown()
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> cancelSos()
            }
            true
        }
    }

    private fun startSosCountdown() {
        if (!com.safphere.launcher.call.SosHelper.hasEmergencyContact(this)) {
            showBigInfo(
                "还没有紧急联系人",
                "请让子女进入联系人编辑页，\n打开「设为紧急联系人」开关。\n\n设置后，按住这里3秒即可一键求助",
                okColor = android.graphics.Color.parseColor("#FB8C00")
            )
            return
        }
        sosCountdown = 3
        Speaker.speak("紧急求助")
        com.safphere.launcher.call.SosHelper.vibrate(this)
        updateSosText("🆘 紧急求助 3…")
        handler.postDelayed(sosStep, 1000)
    }

    private val sosStep = object : Runnable {
        override fun run() {
            sosCountdown--
            if (sosCountdown <= 0) {
                fireSos()
                return
            }
            com.safphere.launcher.call.SosHelper.vibrate(this@HomeActivity)
            updateSosText("🆘 紧急求助 ${sosCountdown}…")
            handler.postDelayed(this, 1000)
        }
    }

    private fun cancelSos() {
        handler.removeCallbacks(sosStep)
        updateSosText("🆘 紧急求助 · 按住3秒")
    }

    private fun fireSos() {
        updateSosText("🆘 正在呼叫紧急联系人…")
        com.safphere.launcher.call.SosHelper.trigger(this)
        handler.postDelayed({ updateSosText("🆘 紧急求助 · 按住3秒") }, 4000)
    }

    private fun updateSosText(t: String) {
        sosBarRef?.text = t
    }

    // ================= 状态刷新（异常才显示，正常保持安静） =================

    /**
     * 异常提醒条：SIM无卡 > 流量已关 > 流量偏低 > 电量不足。
     * 全部正常时整条隐藏——不给老人制造噪音。
     */
    private fun refreshStatus() {
        val alerts = mutableListOf<String>()
        if (SimMonitor.isSimAbsent(this) == true) alerts.add("⚠️ 未检测到电话卡")
        if (Prefs.flowDataOffByUs) alerts.add("⚠️ 流量不足·移动数据已关")
        else if (Prefs.flowEnabled && Prefs.flowRemainingMb in 0f..Prefs.flowThresholdMb.toFloat())
            alerts.add("⚠️ 流量剩余不足${Prefs.flowThresholdMb}MB")
        if (batteryPercent() <= 20) alerts.add("⚠️ 电量不足，请充电")

        if (alerts.isEmpty()) {
            binding.alertChip.visibility = View.GONE
        } else {
            binding.alertChip.visibility = View.VISIBLE
            binding.alertChip.text = alerts.joinToString("　")
        }
    }

    private var lastContactsSig: String = ""
    private fun refreshContacts() {
        val list = Prefs.contacts()
        val sig = list.joinToString(",") { "${it.id}:${it.name}:${it.avatar}" }
        if (sig == lastContactsSig) return   // 无变化跳过，避免无谓全量刷新
        lastContactsSig = sig
        contactAdapter.submit(list)
    }

    private fun batteryPercent(): Int = runCatching {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 1..100 } ?: 100
    }.getOrDefault(100)

    // ================= 真实拨号 / 短信 =================

    /** 发短信（大输入框+常用短语） */
    private fun openSms(contact: Contact) {
        startActivity(
            Intent(this, com.safphere.launcher.sms.SmsComposeActivity::class.java)
                .putExtra(com.safphere.launcher.sms.SmsComposeActivity.EXTRA_NAME, contact.name)
                .putExtra(com.safphere.launcher.sms.SmsComposeActivity.EXTRA_PHONE, contact.phone)
        )
    }

    /** 通话中点挂断条：有权限直接挂断；无权限当场申请；被拒则打开拨号盘（有"返回通话"入口） */
    private fun onEndCallTap() {
        if (com.safphere.launcher.call.CallController.canEndCall(this)) {
            val ended = com.safphere.launcher.call.CallController.endCall(this)
            if (ended) {
                binding.callChip.visibility = View.GONE
                android.widget.Toast.makeText(this, "已挂断 ✓", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                openInCallScreen()
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.ANSWER_PHONE_CALLS), REQ_END)
        }
    }

    /** 兜底：打开拨号盘（正在通话时拨号盘顶部有"返回通话"大条） */
    private fun openInCallScreen() {
        runCatching { startActivity(Intent(Intent.ACTION_DIAL)) }
    }

    /** 点联系人卡片：真实呼出。无权限时当场申请（申请通过立即拨出），彻底拒绝才落拨号盘 */
    private fun call(contact: Contact) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            doCall(contact)
        } else {
            pendingCall = contact
            Speaker.speak("需要电话权限")
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALL) {
            val c = pendingCall
            pendingCall = null
            if (c != null && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                doCall(c)
            } else if (c != null) {
                // 被拒绝：退到系统拨号盘（号码已填好，按一个绿色键即拨出）
                Speaker.speak("正在呼叫${c.name}")
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.phone.ifBlank { c.shortNum }}"))
                    )
                }
            }
        } else if (requestCode == REQ_END) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                val ended = com.safphere.launcher.call.CallController.endCall(this)
                if (ended) {
                    binding.callChip.visibility = View.GONE
                    android.widget.Toast.makeText(this, "已挂断 ✓", android.widget.Toast.LENGTH_SHORT).show()
                } else openInCallScreen()
            } else {
                openInCallScreen()
            }
        }
    }

    private fun doCall(contact: Contact) {
        watchCall = true
        handler.post(callTick)
        val number = contact.phone.ifBlank { contact.shortNum }
        Speaker.speak(getString(R.string.calling_fmt, contact.name))
        runCatching {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
        }.onFailure {
            // 个别ROM仍拦截时，落到拨号盘而不是失败
            runCatching {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
            }
        }
    }

    // ================= 应用启动 / 流量 / 重启 =================

    private fun launchApp(pkg: String) {
        // 防沉迷启动拦截：违规弹大字阻断页，不放行
        val block = com.safphere.launcher.guard.AppGuard.check(this, pkg)
        if (block != null) {
            com.safphere.launcher.guard.GuardEnforcer.softBlock(this, block)
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: run {
            Speaker.speak("这个应用已经不在了")
            return
        }
        runCatching { startActivity(launch) }
    }

    private fun confirmReboot() {
        Speaker.speak("要重启手机吗")
        showBigConfirm(
            title = getString(R.string.reboot_confirm_title),
            message = getString(R.string.reboot_confirm_msg),
            okText = getString(R.string.reboot_now)
        ) { doReboot() }
    }

    private fun doReboot() {
        when (RebootManager.tryReboot(this)) {
            RebootManager.Method.DEVICE_OWNER -> Unit
            RebootManager.Method.POWER_DIALOG -> showBigInfo(
                "请点【重启】",
                "手机电源菜单已打开\n请在菜单里点【重启】",
                okColor = android.graphics.Color.parseColor("#1E88E5")
            )
            RebootManager.Method.MANUAL -> showBigInfo(
                "请手动重启",
                "请长按手机右侧的\n电源键，直到出现菜单，\n然后点【重启】"
            )
        }
    }

    // ================= 分页适配器 =================

    /** 第0页联系人网格；第1页生活页；第2页起常用应用网格；最后是便捷工具页 */
    private inner class PagesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int): Int =
            if (position == 2 + totalAppPages()) TOOLS_PAGE_TYPE else position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> ContactsHolder(PageContactsBinding.inflate(inf, parent, false))
                1 -> LifeHolder(PageLifeBinding.inflate(inf, parent, false))
                TOOLS_PAGE_TYPE -> ToolsHolder(PageToolsBinding.inflate(inf, parent, false))
                else -> AppsHolder(PageAppsBinding.inflate(inf, parent, false))
            }
        }

        override fun getItemCount(): Int = 2 + totalAppPages() + 1
        private fun totalAppPages(): Int {
            val cols = Prefs.appColumns.coerceIn(2, 4)
            val capacity = 4 * cols
            return kotlin.math.max(
                1, kotlin.math.ceil(appsCache.size.toDouble() / capacity).toInt())
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is ContactsHolder -> {
                    // 列数子女可调（每行1/2/3 → 每屏约2/4/6个）
                    holder.b.contactsRecycler.layoutManager =
                        GridLayoutManager(this@HomeActivity, Prefs.contactColumns)
                    holder.b.contactsRecycler.setHasFixedSize(true)
                    holder.b.contactsRecycler.itemAnimator = null
                    holder.b.contactsRecycler.adapter = contactAdapter
                    // 绑定期间不能 notify 嵌套 adapter，延迟到下一帧
                    handler.post { refreshContacts() }
                    bindSosBar(holder.b.sosBar)
                }
                is LifeHolder -> bindLifePage(holder)
                is ToolsHolder -> bindToolsPage(holder)
                is AppsHolder -> {
                    // 每页用自己的 holder 渲染（共享字段会让异步回调渲染错页 → 页面内容重复）
                    val hb = holder.b
                    val appPage = position - 2
                    renderAppsGrid(hb, appPage)
                    val pagesBefore = totalAppPages()
                    loadAppsAsync { apps ->
                        if (totalAppPages() != pagesBefore) {
                            // 页数变化：整体重绑，各页在 onBind 里渲染自己的页码
                            runCatching { binding.pager.adapter?.notifyDataSetChanged() }
                        } else {
                            // 页数不变：只刷新本页内容
                            renderAppsGrid(hb, appPage.coerceIn(0, totalAppPages() - 1))
                        }
                    }
                }
            }
        }

        inner class ContactsHolder(val b: PageContactsBinding) : RecyclerView.ViewHolder(b.root)
        inner class LifeHolder(val b: PageLifeBinding) : RecyclerView.ViewHolder(b.root)
        inner class AppsHolder(val b: PageAppsBinding) : RecyclerView.ViewHolder(b.root)
        inner class ToolsHolder(val b: PageToolsBinding) : RecyclerView.ViewHolder(b.root)
    }

    private fun bindToolsPage(holder: PagesAdapter.ToolsHolder) {
        toolsBinding = holder.b
        renderTorchCard(holder.b)
        renderHomeAddressCard(holder.b)
        holder.b.cardTorch.setOnClickListener { toggleTorch() }
        holder.b.cardHome.setOnClickListener { showHomeAddressDialog() }
    }

    // ================= 便捷页：手电筒 + 家庭地址 =================

    private var toolsBinding: PageToolsBinding? = null
    private var torchOn = false

    private fun renderTorchCard(b: PageToolsBinding) {
        b.torchEmoji.text = if (torchOn) "💡" else "🔆"
        b.torchLabel.text = if (torchOn) "手电筒开着 · 点这里关闭" else "手电筒 · 点这里打开"
    }

    private fun toggleTorch() {
        runCatching {
            val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) {
                Speaker.speak("这台手机没有手电筒")
                return
            }
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
            Speaker.speak(if (torchOn) "手电筒打开了" else "手电筒关了")
            toolsBinding?.let { renderTorchCard(it) }
        }.onFailure {
            Speaker.speak("手电筒打开失败")
        }
    }

    // ---- 家庭地址大卡（走失求助：大字地址 + 一键导航） ----
    private fun renderHomeAddressCard(b: PageToolsBinding) {
        val addr = Prefs.homeAddress
        b.homeLabel.text = if (addr.isBlank()) "我的家 · 点这里看地址" else "🏠 $addr"
    }

    private fun showHomeAddressDialog() {
        val addr = Prefs.homeAddress
        if (addr.isBlank()) {
            showBigInfo(
                title = "还没有填写家庭地址",
                message = "请子女进入「子女设置」\n填写家庭地址后\n这里会显示大字地址和一键导航",
                okColor = android.graphics.Color.parseColor("#FB8C00")
            )
            return
        }
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), 0)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        box.addView(TextView(this).apply {
            text = "🏠 我家的地址"
            textSize = 26f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.text_main))
        })
        box.addView(TextView(this).apply {
            text = addr
            textSize = 24f
            setLineSpacing(0f, 1.35f)
            setTextColor(0xFF1565C0.toInt())
            setPadding(0, dp(14), 0, dp(8))
        })
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(box)
            .setPositiveButton("🧭 导航回家") { _, _ -> navigateHome(addr) }
            .setNegativeButton("关闭", null)
            .show()
        Speaker.speak("这是家里的地址，点导航回家")
    }

    /** geo: 通用导航（高德/百度/系统地图都会接管），没有地图应用时语音提示 */
    private fun navigateHome(address: String) {
        val q = java.net.URLEncoder.encode(address, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure {
                Speaker.speak("手机里没有地图应用，请让子女安装高德地图")
                Toast.makeText(this, "未找到地图应用", Toast.LENGTH_SHORT).show()
            }
    }

    // ================= 生活页 =================

    private fun bindLifePage(holder: PagesAdapter.LifeHolder) {
        val b = holder.b
        lifeBinding = b

        applyCompactLife(b)
        renderLifeHeader()
        renderWeatherCard(b, lastWeather ?: WeatherFetcher.cached(this))

        // 天气卡：点击语音播报（不识字的老人也能"听"天气）
        b.weatherCard.setOnClickListener {
            val w = lastWeather ?: WeatherFetcher.cached(this)
            if (w != null) {
                Speaker.speak(
                    "${WeatherFetcher.displayName()}今天${w.desc}，现在${w.temp}度，" +
                        "最高${w.todayMax}度，最低${w.todayMin}度。${w.tip}"
                )
            } else {
                Speaker.speak("天气还没有收到，稍后再看看")
            }
        }

        // 语音问答入口（生活页，功能开关控制显隐）
        b.aiEntry.visibility = if (Prefs.aiEnabled) View.VISIBLE else View.GONE
        b.aiEntry.setOnClickListener {
            startActivity(Intent(this, AiChatActivity::class.java))
        }

        renderCalendar(b)
        b.btnPrevMonth.setOnClickListener {
            shownMonth.add(Calendar.MONTH, -1)
            renderCalendar(b)
        }
        b.btnNextMonth.setOnClickListener {
            shownMonth.add(Calendar.MONTH, 1)
            renderCalendar(b)
        }
    }

    private fun renderLifeHeader() {
        val b = lifeBinding ?: return
        b.lifeDate.text = dateFmt.format(Date())
        val lunar = LunarCalendar.lunarString(Calendar.getInstance())
        b.lifeLunar.text = if (lunar.isBlank()) "" else "农历$lunar"
    }

    private fun maybeFetchWeather() {
        if (WeatherFetcher.isFresh()) return
        WeatherFetcher.refreshAsync(applicationContext) { w ->
            if (w != null) {
                lastWeather = w
                lifeBinding?.let { renderWeatherCard(it, w) }
                // 天气类健康提醒（高温/低温/雨雪）随刷新评估
                com.safphere.launcher.reminder.ReminderEngine.evaluateWeather(applicationContext)
            }
        }
    }

    /**
     * 小屏/低分辨率老人机压缩生活页固定内容，给下方日历留出足够高度。
     * 两档：<700dp（5吋720P老手机）超紧凑；700~880dp 紧凑；≥880dp 保持大字。
     */
    private fun applyCompactLife(b: PageLifeBinding) {
        val hdp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        if (hdp >= 880) return   // 大屏维持原大字
        val ultra = hdp < 700
        b.weatherEmoji.textSize = if (ultra) 34f else 40f
        b.weatherTemp.textSize = if (ultra) 26f else 30f
        b.weatherDesc.textSize = if (ultra) 15f else 17f
        b.weatherTip.textSize = if (ultra) 15f else 17f
        b.forecast0.textSize = if (ultra) 13f else 14f
        b.forecast1.textSize = if (ultra) 13f else 14f
        b.forecast2.textSize = if (ultra) 13f else 14f
        b.weatherMeta.textSize = 12f
        val p = (resources.displayMetrics.density * (if (ultra) 10 else 12)).toInt()
        b.weatherCard.setPadding(p, p, p, p)
    }

    private fun renderWeatherCard(b: PageLifeBinding, w: WeatherFetcher.Weather?) {
        if (w == null) {
            b.weatherEmoji.text = "🌤️"
            b.weatherTemp.text = "--°"
            b.weatherDesc.text = "天气获取中…点这里重试"
            b.weatherTip.text = "联网后会自动更新"
            b.forecast0.text = ""
            b.forecast1.text = ""
            b.forecast2.text = ""
            b.weatherMeta.text = WeatherFetcher.displayName()
            return
        }
        b.weatherEmoji.text = w.emoji
        b.weatherTemp.text = "${w.temp}°C"
        b.weatherDesc.text = "${w.desc} · 今天 ${w.todayMin}~${w.todayMax}°"
        b.weatherTip.text = w.tip
        val fmts = listOf(b.forecast0, b.forecast1, b.forecast2)
        for (i in fmts.indices) {
            fmts[i].text = w.days.getOrNull(i)?.let { "${it.label}\n${it.emoji}\n${it.min}~${it.max}°" } ?: ""
        }
        val upd = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(Prefs.weatherFetchedAt))
        b.weatherMeta.text = "${WeatherFetcher.displayName()} · 更新于 $upd"
    }

    /** 月历：7行弹性均分剩余高度（小屏/大字号永不裁剪），今天高亮，点日期看详情 */
    private fun renderCalendar(b: PageLifeBinding) {
        val titleFmt = SimpleDateFormat("yyyy年M月", Locale.CHINA)
        b.calTitle.text = titleFmt.format(shownMonth.time)

        val today = Calendar.getInstance()
        val first = (shownMonth.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val lead = first.get(Calendar.DAY_OF_WEEK) - 1   // 周日=0

        val cells = mutableListOf<String>()
        val states = mutableListOf<Int>() // -1=周头 0=非本月 1=本月 2=今天
        val cellCals = mutableListOf<Calendar?>()
        listOf("日", "一", "二", "三", "四", "五", "六").forEach {
            cells.add(it); states.add(-1); cellCals.add(null)
        }
        val cal = (first.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -lead) }
        repeat(42) {
            val isThisMonth = cal.get(Calendar.MONTH) == shownMonth.get(Calendar.MONTH) &&
                cal.get(Calendar.YEAR) == shownMonth.get(Calendar.YEAR)
            val isToday = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                cal.get(Calendar.MONTH) == today.get(Calendar.MONTH) &&
                cal.get(Calendar.DAY_OF_MONTH) == today.get(Calendar.DAY_OF_MONTH)
            cells.add(cal.get(Calendar.DAY_OF_MONTH).toString())
            states.add(when {
                isToday -> 2
                isThisMonth -> 1
                else -> 0
            })
            cellCals.add(cal.clone() as Calendar)
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }

        val dpv = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        b.calRows.removeAllViews()
        for (row in 0 until 7) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            for (col in 0 until 7) {
                val pos = row * 7 + col
                val tv = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    gravity = android.view.Gravity.CENTER
                    val st = states[pos]
                    text = cells[pos]
                    when (st) {
                        -1 -> {
                            textSize = 12f
                            setTextColor(getColor(R.color.text_secondary))
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                        }
                        2 -> {
                            textSize = 15f
                            setTextColor(getColor(R.color.red))
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            background = com.safphere.launcher.util.AvatarUtils.circleBg(getColor(R.color.chip_bad_bg))
                        }
                        1 -> {
                            textSize = 15f
                            setTextColor(getColor(R.color.text_main))
                        }
                        else -> {
                            textSize = 15f
                            setTextColor(getColor(R.color.divider))
                        }
                    }
                    setOnClickListener { cellCals[pos]?.let { showDayInfo(it) } }
                }
                rowView.addView(tv)
            }
            b.calRows.addView(rowView)
        }
        // 布局完成后按每行实际高度校准字号：行高不足时缩小文字，绝不重叠（小屏/大字体都安全）
        b.calRows.post { fitCalendarText(b) }
    }

    /** 按日历每行实测高度缩放单元格字号（9~15sp），行高未定（布局未完成）时重试，绝不重叠 */
    private fun fitCalendarText(b: PageLifeBinding) {
        val rows = b.calRows.childCount
        if (rows == 0) return
        val h = b.calRows.height
        if (h <= 0) {
            // 布局未完成：延迟重试（页面上屏/翻页时序不定；有上限， detach 后不再空转）
            val tries = (b.calRows.getTag(R.id.calRows) as? Int) ?: 0
            if (tries < 20) {
                b.calRows.setTag(R.id.calRows, tries + 1)
                b.calRows.postDelayed({ fitCalendarText(b) }, 64)
            }
            return
        }
        b.calRows.setTag(R.id.calRows, 0)
        val density = resources.displayMetrics.density
        val rowH = h.toFloat() / rows
        val sp = (rowH / density * 0.55f).coerceIn(9f, 15f)
        for (r in 0 until rows) {
            val row = b.calRows.getChildAt(r) as? LinearLayout ?: continue
            for (c in 0 until row.childCount) {
                (row.getChildAt(c) as? TextView)?.textSize = sp
            }
        }
    }

    /** 点日历日期 → 大字详情（公历/星期/农历） */
    private fun showDayInfo(cal: Calendar) {
        val dateStr = SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(cal.time)
        val weekStr = SimpleDateFormat("EEEE", Locale.CHINA).format(cal.time)
        val lunar = LunarCalendar.lunarString(cal)
        showBigInfo(
            title = dateStr,
            message = "$weekStr\n" + if (lunar.isBlank()) "" else "农历$lunar",
            okColor = android.graphics.Color.parseColor("#1E88E5")
        )
    }

    /** 应用列表：磁盘快照秒出 → 后台枚举校准（标签/图标解码全在后台），完成后回写快照 */
    // 并发合并：onResume 与页面绑定同时请求时只枚举一次，回调共享
    private var appsLoadInFlight = false
    private val appsLoadCallbacks = mutableListOf<(List<AppEntry>) -> Unit>()

    private fun loadAppsAsync(onLoaded: (List<AppEntry>) -> Unit) {
        // 内容级签名：白名单同数量换包、列数变化也能触发重载（旧签名只看 size，会漏刷新）
        val sig = (Prefs.whitelist().sorted().joinToString(",") +
            "|all=" + Prefs.showAllApps +
            "|ac=" + Prefs.appColumns +
            "|cc=" + Prefs.contactColumns).hashCode()
        if (sig != lastAppsSig) {
            appsCacheDirty = true
            lastAppsSig = sig
        }
        if (!appsCacheDirty && appsCache.isNotEmpty()) {
            onLoaded(appsCache)
            return
        }
        appsLoadCallbacks.add(onLoaded)
        if (appsLoadInFlight) return
        appsLoadInFlight = true
        val pm = applicationContext.packageManager
        appLoadExecutor.execute {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val allowed = Prefs.whitelist()
            val showAll = Prefs.showAllApps
            val self = packageName
            val list = pm.queryIntentActivities(main, 0)
                .filter { showAll || allowed.contains(it.activityInfo.packageName) }
                .filter { it.activityInfo.packageName != self }
                .distinctBy { it.activityInfo.packageName }
                .map { ri ->
                    // 标签加载放后台：首次冷启动这是主线程卡顿大户
                    AppEntry(
                        ri.activityInfo.packageName,
                        runCatching { ri.loadLabel(pm).toString() }.getOrDefault("")
                    )
                }
                .sortedBy { it.label }
            // 图标后台预热：主线程之后只查内存/磁盘，不再解码
            list.forEach { e ->
                IconDiskCache.prefetch(applicationContext, e.pkg) {
                    pm.getApplicationIcon(e.pkg)
                }
            }
            IconDiskCache.prune(applicationContext, list.map { it.pkg }.toSet())
            AppsSnapshot.save(applicationContext, list.map { AppsSnapshot.Entry(it.pkg, it.label) })
            appsCache = list
            appsCacheDirty = false
            // 延迟到下一帧：此刻可能仍处于外层 RecyclerView 布局中，
            // 同步 notifyDataSetChanged 会抛 "Cannot call this method while RecyclerView is computing a layout"
            runOnUiThread {
                handler.post {
                    appsLoadInFlight = false
                    val cbs = ArrayList(appsLoadCallbacks)
                    appsLoadCallbacks.clear()
                    cbs.forEach { cb -> runCatching { cb(list) } }
                }
            }
        }
    }

    /** 应用页网格：每页 4行×列数（不滚动，◀▶翻页），空位留白 */
    private fun renderAppsGrid(b: PageAppsBinding, appPage: Int) {
        val cols = Prefs.appColumns.coerceIn(2, 4)
        val capacity = 4 * cols
        val totalAppPages = kotlin.math.max(
            1, kotlin.math.ceil(appsCache.size.toDouble() / capacity).toInt())
        val safe = appPage.coerceIn(0, totalAppPages - 1)
        b.appsPageLabel.text = "第${safe + 1}/$totalAppPages 页" +
            if (Prefs.showAllApps) "" else "（白名单模式）"
        b.btnAppSearch.setOnClickListener { showAppSearchDialog() }

        b.appsRows.removeAllViews()
        val pageApps = appsCache.drop(safe * capacity).take(capacity)
        val dpv = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        for (row in 0 until 4) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            for (col in 0 until cols) {
                val app = pageApps.getOrNull(row * cols + col)
                val cell = if (app != null) makeAppCell(app, dpv) else View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dpv(4), 1f)
                }
                rowView.addView(cell)
            }
            b.appsRows.addView(rowView)
        }
    }

    // 图标走二级缓存（内存→磁盘→实时），标签直接用快照里已解析好的
    private fun iconOf(pkg: String): android.graphics.drawable.Drawable? =
        IconDiskCache.get(this, pkg) { packageManager.getApplicationIcon(pkg) }

    /** 应用搜索弹窗：边输边筛（名称/包名），点结果直接打开；老人可用输入法手写/语音 */
    private fun showAppSearchDialog() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), 0)
        }
        val input = EditText(this).apply {
            hint = "输入应用名字"
            textSize = 18f
            setSingleLine(true)
        }
        box.addView(input)
        val empty = TextView(this).apply {
            text = "没有找到，换个名字试试"
            textSize = 17f
            setTextColor(getColor(R.color.text_secondary))
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(30), 0, dp(30))
            visibility = View.GONE
        }
        box.addView(empty)
        val list = android.widget.ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(520))
            dividerHeight = dp(1)
        }
        box.addView(list)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("搜索应用（共${appsCache.size}个）")
            .setView(box)
            .setNegativeButton("关闭", null)
            .create()

        val adapter = object : android.widget.BaseAdapter() {
            var hits: List<AppEntry> = appsCache
            override fun getCount() = hits.size
            override fun getItem(position: Int) = hits[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                }
                val e = hits[position]
                val icon = row.findViewById<android.widget.ImageView>(R.id.appIcon)
                    ?: android.widget.ImageView(this@HomeActivity).apply {
                        id = R.id.appIcon
                        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                    }
                // 复用时先摘掉旧位置
                (icon.parent as? ViewGroup)?.removeView(icon)
                row.addView(icon, 0)
                icon.setImageDrawable(iconOf(e.pkg))
                val label = row.findViewById<TextView>(R.id.appLabel)
                    ?: TextView(this@HomeActivity).apply {
                        id = R.id.appLabel
                        layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = dp(14)
                        }
                    }
                (label.parent as? ViewGroup)?.removeView(label)
                row.addView(label)
                label.text = e.label
                label.textSize = 19f
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val e = adapter.hits[position]
            dialog.dismiss()
            launchApp(e.pkg)
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim() ?: ""
                adapter.hits = if (q.isEmpty()) appsCache else appsCache.filter {
                    it.label.contains(q, true) || it.pkg.contains(q, true)
                }
                adapter.notifyDataSetChanged()
                empty.visibility = if (adapter.hits.isEmpty()) View.VISIBLE else View.GONE
                list.visibility = if (adapter.hits.isEmpty()) View.GONE else View.VISIBLE
            }
        })
        dialog.show()
        // 弹出后聚焦拉起输入法，方便直接输入
        input.postDelayed({ runCatching {
            input.requestFocus()
            val im = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            im.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } }, 200)
    }

    private fun makeAppCell(app: AppEntry, dpv: (Int) -> Int): View {
        val cell = ItemAppBinding.inflate(layoutInflater).root
        cell.layoutParams = LinearLayout.LayoutParams(
            0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        cell.setPadding(dpv(4), dpv(6), dpv(4), dpv(6))
        cell.findViewById<android.widget.ImageView>(R.id.appIcon).apply {
            setImageDrawable(iconOf(app.pkg))
            layoutParams = android.widget.LinearLayout.LayoutParams(dpv(52), dpv(52))
        }
        cell.findViewById<TextView>(R.id.appLabel).apply {
            textSize = 13f
            text = app.label
        }
        cell.setOnClickListener { launchApp(app.pkg) }
        // 长按 = 拖拽到底部快捷栏槽位更换
        cell.setOnLongClickListener { v ->
            // 触觉反馈提示进入拖拽（可拖到底部快捷栏更换）
            runCatching {
                val vib = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= 26)
                    vib.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") vib.vibrate(30)
            }
            val data = android.content.ClipData.newPlainText(
                "dock_pkg", app.pkg
            )
            v.startDragAndDrop(data, View.DragShadowBuilder(v), null, 0)
            android.widget.Toast.makeText(
                this, "拖到底部快捷栏即可更换", android.widget.Toast.LENGTH_SHORT).show()
            true
        }
        return cell
    }

    /** 底部快捷栏槽位更换 */
    private fun setDockSlot(idx: Int, pkg: String) {
        val list = Prefs.dockApps().toMutableList()
        while (list.size < 3) list.add("")
        list[idx] = pkg
        Prefs.saveDockApps(list)
        buildDock()
    }

    private fun dockDragListener(idx: Int) = View.OnDragListener { v, event ->
        when (event.action) {
            // 必须接受 DRAG_STARTED 才会成为放置目标（此前漏掉导致拖了没反应）
            android.view.DragEvent.ACTION_DRAG_STARTED -> true
            android.view.DragEvent.ACTION_DRAG_ENTERED,
            android.view.DragEvent.ACTION_DRAG_LOCATION -> { v.alpha = 0.45f; true }
            android.view.DragEvent.ACTION_DRAG_EXITED -> { v.alpha = 1f; true }
            android.view.DragEvent.ACTION_DROP -> {
                v.alpha = 1f
                val pkg = event.clipData.getItemAt(0).text?.toString().orEmpty()
                if (pkg.isNotBlank()) {
                    setDockSlot(idx, pkg)
                    android.widget.Toast.makeText(
                        this, "快捷栏已更换", android.widget.Toast.LENGTH_SHORT).show()
                    true
                } else false
            }
            android.view.DragEvent.ACTION_DRAG_ENDED -> { v.alpha = 1f; true }
            else -> true
        }
    }


    companion object {
        private const val REQ_CALL = 100
        private const val REQ_END = 102
        private const val TOOLS_PAGE_TYPE = 998
        @Volatile
        var isResumed: Boolean = false
            private set
    }
}
