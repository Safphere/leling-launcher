package com.safphere.launcher.data

import android.content.Context
import android.content.Intent
import org.json.JSONArray

/** 全部本地配置：SharedPreferences + JSON，零第三方依赖 */
object Prefs {

    const val DEFAULT_AI_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    const val DEFAULT_AI_MODEL = "glm-5.3-flash"

    private lateinit var ctx: Context

    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
    }

    private val sp by lazy {
        // v3.4.1 之前存储名为 "minkelab"，一次性迁移到 "safphere"（commit 同步写，避免后续读漏）
        val legacy = ctx.getSharedPreferences("minkelab", Context.MODE_PRIVATE)
        val sp = ctx.getSharedPreferences("safphere", Context.MODE_PRIVATE)
        if (legacy.all.isNotEmpty() && sp.all.isEmpty()) {
            val e = sp.edit()
            for ((k, v) in legacy.all) {
                when (v) {
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Float -> e.putFloat(k, v)
                    is String -> e.putString(k, v)
                    is Set<*> -> e.putStringSet(k, v.map { it.toString() }.toSet())
                }
            }
            e.commit()
            legacy.edit().clear().commit()
        }
        sp
    }

    // ---------- 联系人 ----------
    fun contacts(): MutableList<Contact> {
        val arr = sp.getString("contacts", null) ?: return mutableListOf()
        return runCatching {
            JSONArray(arr).let { a ->
                MutableList(a.length()) { Contact.fromJson(a.getJSONObject(it)) }
            }
        }.getOrNull() ?: mutableListOf()
    }

    fun saveContacts(list: List<Contact>) {
        sp.edit().putString("contacts", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun contactById(id: Long): Contact? = contacts().firstOrNull { it.id == id }

    // ---------- 应用白名单 ----------
    fun whitelist(): MutableSet<String> {
        val arr = sp.getString("whitelist", null) ?: return LinkedHashSet()
        return runCatching {
            LinkedHashSet<String>().apply {
                val a = JSONArray(arr)
                for (i in 0 until a.length()) add(a.getString(i))
            }
        }.getOrNull() ?: LinkedHashSet()
    }

    fun saveWhitelist(set: Collection<String>) {
        sp.edit().putString("whitelist", JSONArray(set).toString()).putBoolean("whitelist_init", true)
            .apply()
    }

    /** 首次运行：从常用应用候选中挑出已安装的作为默认白名单 */
    /** 常用国产应用候选（微信/抖音/QQ/钉钉/哔哩哔哩/支付宝/淘宝/高德/百度/爱奇艺） */
    private val CN_APPS = listOf(
        "com.tencent.mm", "com.ss.android.ugc.aweme", "com.tencent.mobileqq",
        "com.alibaba.android.rimet", "tv.danmaku.bili", "com.eg.android.AlipayGphone",
        "com.taobao.taobao", "com.autonavi.minimap", "com.baidu.searchbox", "com.qiyi.video"
    )

    /** 常见内置应用候选（计算器/相册/文件管理/音乐/视频/邮箱/天气/录音/便签/指南针/应用商店），
     *  覆盖小米/华为/OPPO/vivo/原生各ROM，装了才加入默认白名单 */
    private val BUILTIN_APPS = listOf(
        // 计算器
        "com.miui.calculator", "com.android.calculator2", "com.sec.android.app.popupcalculator",
        "com.oneplus.calculator", "com.huawei.calculator", "com.google.android.calculator",
        // 相册/图库
        "com.miui.gallery", "com.google.android.apps.photos", "com.sec.android.gallery3d",
        "com.huawei.photos", "com.oppo.gallery3d", "com.vivo.gallery",
        // 文件管理
        "com.android.fileexplorer", "com.sec.android.app.myfiles", "com.huawei.hidisk",
        "com.heytap.filemanager",
        // 音乐
        "com.miui.player", "com.android.mediacenter", "com.sec.android.app.music",
        "com.huawei.music", "com.heytap.music",
        // 视频
        "com.miui.videoplayer", "com.huawei.video", "com.sec.android.app.videoplayer",
        // 邮箱
        "com.android.email", "com.miui.email", "com.google.android.gm",
        // 天气
        "com.miui.weather2", "com.miui.weather", "com.huawei.android.totemweather",
        "com.sec.android.daemonapp", "com.heytap.weather",
        // 录音机
        "com.android.soundrecorder", "com.miui.voicerecorder", "com.sec.android.app.voicenote",
        // 便签/笔记
        "com.miui.notes", "com.sec.android.app.memo", "com.example.android.notepad",
        // 指南针
        "com.miui.compass", "com.huawei.compass",
        // 应用商店
        "com.xiaomi.market", "com.huawei.appmarket", "com.heytap.market", "com.bbk.appstore"
    )

    /**
     * 系统必备应用白名单同步（每次启动执行，只增不减）：
     * - 按系统意图解析：拨号/短信/设置/浏览器/相机/时钟闹钟/日历 —— 与ROM无关，
     *   小米(MIUI/HyperOS)、华为、Pixel 各自的内置应用都能正确找到；
     * - 已安装的国产常用应用；
     * - 首次写入快捷栏默认（拨号/短信/相机）。
     * 用户在子女模式里取消勾选的应用不会被强制加回（当次运行跳过已被移除的记录）。
     */
    fun ensureDefaultWhitelist(context: Context) {
        init(context)
        val pm = context.packageManager
        val found = LinkedHashSet(whitelist())

        // 1) 系统能力应用（意图解析，ROM 无关）
        val must = listOf(
            Intent(Intent.ACTION_DIAL),                                    // 拨号
            Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:")),  // 短信
            Intent(android.provider.Settings.ACTION_SETTINGS),              // 设置
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://")),   // 浏览器
            Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), // 相机
            Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS),         // 时钟
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("content://com.android.calendar/time/")), // 日历
            Intent(android.provider.ContactsContract.Intents.SHOW_OR_CREATE_CONTACT,
                android.net.Uri.parse("content://contacts/people/1"))       // 联系人
        )
        for (i in must) {
            runCatching {
                pm.resolveActivity(
                    Intent(i).addCategory(Intent.CATEGORY_DEFAULT), 0
                )?.activityInfo?.packageName?.let { found.add(it) }
            }
        }

        // 2) 已安装的国产常用应用 + 常见内置应用
        val launcherApps = runCatching {
            pm.getInstalledApplications(0).map { it.packageName }
        }.getOrDefault(emptyList())
        for (pkg in CN_APPS) if (pkg in launcherApps) found.add(pkg)
        for (pkg in BUILTIN_APPS) if (pkg in launcherApps) found.add(pkg)

        if (found.none { it == context.packageName }) found.add(context.packageName)
        saveWhitelist(found)

        // 3) 首次写入快捷栏默认：拨号 / 短信 / 相机（按本机实际解析）
        if (dockApps().none { it.isNotBlank() }) {
            val dockDefaults = mutableListOf<String>()
            fun addResolve(intent: Intent) {
                runCatching {
                    pm.resolveActivity(intent, 0)?.activityInfo?.packageName
                }.getOrNull()?.let {
                    if (dockDefaults.size < 3 && it !in dockDefaults) dockDefaults.add(it)
                }
            }
            addResolve(Intent(Intent.ACTION_DIAL))
            addResolve(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:")))
            addResolve(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            saveDockApps(dockDefaults)
        }
    }

    // ---------- 基础配置 ----------
    var firstRunDone: Boolean
        get() = sp.getBoolean("first_run_done", false)
        set(v) = sp.edit().putBoolean("first_run_done", v).apply()

    var pin: String
        get() = sp.getString("pin", "1234") ?: "1234"
        set(v) = sp.edit().putString("pin", v).apply()

    // ---------- SIM检测 ----------
    var simCheckEnabled: Boolean
        get() = sp.getBoolean("sim_check_enabled", true)
        set(v) = sp.edit().putBoolean("sim_check_enabled", v).apply()

    var simHour: Int
        get() = sp.getInt("sim_hour", 9)
        set(v) = sp.edit().putInt("sim_hour", v).apply()

    var simMinute: Int
        get() = sp.getInt("sim_minute", 0)
        set(v) = sp.edit().putInt("sim_minute", v).apply()

    var lastSimAlertAt: Long
        get() = sp.getLong("last_sim_alert", 0L)
        set(v) = sp.edit().putLong("last_sim_alert", v).apply()

    var lastResumeCheckAt: Long
        get() = sp.getLong("last_resume_check", 0L)
        set(v) = sp.edit().putLong("last_resume_check", v).apply()

    // ---------- 语音 ----------
    var speakEnabled: Boolean
        get() = sp.getBoolean("speak_enabled", true)
        set(v) = sp.edit().putBoolean("speak_enabled", v).apply()

    var aiReadAloud: Boolean
        get() = sp.getBoolean("ai_read_aloud", true)
        set(v) = sp.edit().putBoolean("ai_read_aloud", v).apply()

    /** SpeechRecognizer 的 BCP-47 语言标签 */
    var aiLang: String
        get() = sp.getString("ai_lang", "zh-CN") ?: "zh-CN"
        set(v) = sp.edit().putString("ai_lang", v).apply()

    var aiUrl: String
        get() = sp.getString("ai_url", DEFAULT_AI_URL) ?: DEFAULT_AI_URL
        set(v) = sp.edit().putString("ai_url", v).apply()

    var aiModel: String
        get() = sp.getString("ai_model", DEFAULT_AI_MODEL) ?: DEFAULT_AI_MODEL
        set(v) = sp.edit().putString("ai_model", v).apply()

    var aiKey: String
        get() = sp.getString("ai_key", "") ?: ""
        set(v) = sp.edit().putString("ai_key", v).apply()

    // ---------- 选择性接管 ----------
    /** true=应用页显示全部应用（默认，什么都不藏）；false=仅显示子女勾选的白名单 */
    var showAllApps: Boolean
        get() = sp.getBoolean("show_all_apps", true)
        set(v) = sp.edit().putBoolean("show_all_apps", v).apply()

    /** 联系人每行列数（1=特大/每屏2个 2=大/每屏4-6个 3=中/每屏6个+） */
    var contactColumns: Int
        get() = sp.getInt("contact_columns", 2)
        set(v) = sp.edit().putInt("contact_columns", v).apply()

    /** 应用图标每行列数（2/3/4，越大图标越小） */
    var appColumns: Int
        get() = sp.getInt("app_columns", 3)
        set(v) = sp.edit().putInt("app_columns", v).apply()

    /** 底部快捷栏应用（最多3个包名，按顺序） */
    fun dockApps(): MutableList<String> {
        val arr = sp.getString("dock_apps", null) ?: return mutableListOf()
        return runCatching {
            JSONArray(arr).let { a ->
                MutableList(a.length()) { a.getString(it) }
            }
        }.getOrNull() ?: mutableListOf()
    }

    fun saveDockApps(list: List<String>) {
        sp.edit().putString("dock_apps", JSONArray(list.take(3)).toString()).apply()
    }

    // ---------- 流量智能管理 ----------
    var flowEnabled: Boolean
        get() = sp.getBoolean("flow_enabled", true)
        set(v) = sp.edit().putBoolean("flow_enabled", v).apply()

    /** 0移动 1联通 2电信 */
    var flowCarrier: Int
        get() = sp.getInt("flow_carrier", 0)
        set(v) = sp.edit().putInt("flow_carrier", v).apply()

    var flowQueryNumber: String
        get() = sp.getString("flow_query_number", "10086") ?: "10086"
        set(v) = sp.edit().putString("flow_query_number", v).apply()

    var flowQueryText: String
        get() = sp.getString("flow_query_text", "CXLL") ?: "CXLL"
        set(v) = sp.edit().putString("flow_query_text", v).apply()

    /** 剩余流量阈值（MB），低于即关闭数据 */
    var flowThresholdMb: Int
        get() = sp.getInt("flow_threshold_mb", 100)
        set(v) = sp.edit().putInt("flow_threshold_mb", v).apply()

    /** 月结日（1-28 号） */
    var flowBillingDay: Int
        get() = sp.getInt("flow_billing_day", 1)
        set(v) = sp.edit().putInt("flow_billing_day", v).apply()

    /** 最近一次解析到的剩余流量（MB）；-1=未知 */
    var flowRemainingMb: Float
        get() = sp.getFloat("flow_remaining_mb", -1f)
        set(v) = sp.edit().putFloat("flow_remaining_mb", v).apply()

    var flowLastQueryAt: Long
        get() = sp.getLong("flow_last_query_at", 0L)
        set(v) = sp.edit().putLong("flow_last_query_at", v).apply()

    var flowLastQueryOkAt: Long
        get() = sp.getLong("flow_last_query_ok_at", 0L)
        set(v) = sp.edit().putLong("flow_last_query_ok_at", v).apply()

    /** 移动数据是被本App关闭的（月结日需自动恢复） */
    var flowDataOffByUs: Boolean
        get() = sp.getBoolean("flow_data_off_by_us", false)
        set(v) = sp.edit().putBoolean("flow_data_off_by_us", v).apply()

    /** 最近一次月结恢复的年月（yyyy-MM，防重复执行） */
    var flowLastResetYm: String
        get() = sp.getString("flow_last_reset_ym", "") ?: ""
        set(v) = sp.edit().putString("flow_last_reset_ym", v).apply()

    // ---------- 天气 ----------
    /** 天气城市（子女可改；改后清空经纬度缓存触发重新定位） */
    var weatherCity: String
        get() = sp.getString("weather_city", "北京") ?: "北京"
        set(v) = sp.edit().putString("weather_city", v).apply()

    /** 天气位置：自动定位 true；false=手动城市 */
    var weatherAutoLocation: Boolean
        get() = sp.getBoolean("weather_auto_location", false)
        set(v) = sp.edit().putBoolean("weather_auto_location", v).apply()

    // ---------- 勿扰时段（健康提醒夜间静默，推迟到时段结束） ----------
    var quietEnabled: Boolean
        get() = sp.getBoolean("quiet_enabled", true)
        set(v) = sp.edit().putBoolean("quiet_enabled", v).apply()

    /** 勿扰开始（分钟数，默认 22:00 = 1320） */
    var quietStartMin: Int
        get() = sp.getInt("quiet_start_min", 22 * 60)
        set(v) = sp.edit().putInt("quiet_start_min", v).apply()

    /** 勿扰结束（分钟数，默认 07:00 = 420） */
    var quietEndMin: Int
        get() = sp.getInt("quiet_end_min", 7 * 60)
        set(v) = sp.edit().putInt("quiet_end_min", v).apply()

    // ---------- 家庭地址卡（便捷页大字卡 + 一键导航） ----------
    var homeAddress: String
        get() = sp.getString("home_address", "") ?: ""
        set(v) = sp.edit().putString("home_address", v).apply()

    // ---------- 远程状态查询（子女发 LLZT 短信） ----------
    var statusSmsEnabled: Boolean
        get() = sp.getBoolean("status_sms_enabled", true)
        set(v) { sp.edit().putBoolean("status_sms_enabled", v).commit() }

    // ---------- 提醒确认记录（ruleId -> yyyy-MM-dd，点「知道了」时记录） ----------
    fun reminderAckedAt(ruleId: String): String {
        val s = sp.getString("reminder_acked", null) ?: return ""
        return runCatching {
            org.json.JSONObject(s).optString(ruleId)
        }.getOrDefault("")
    }

    fun setReminderAcked(ruleId: String, date: String) {
        val s = sp.getString("reminder_acked", null) ?: "{}"
        val o = runCatching { org.json.JSONObject(s) }.getOrDefault(org.json.JSONObject())
        o.put(ruleId, date)
        sp.edit().putString("reminder_acked", o.toString()).apply()
    }

    /** 自动定位解析出的城市显示名（如"上海"；解析失败="当前位置"） */
    var weatherLocateName: String
        get() = sp.getString("weather_locate_name", "") ?: ""
        set(v) = sp.edit().putString("weather_locate_name", v).apply()

    /** 城市经纬度缓存 "lat,lon" */
    var weatherLatLon: String
        get() = sp.getString("weather_latlon", "") ?: ""
        set(v) = sp.edit().putString("weather_latlon", v).apply()

    /** 最近一次天气原始JSON（离线兜底展示） */
    var weatherCacheJson: String
        get() = sp.getString("weather_cache_json", "") ?: ""
        set(v) = sp.edit().putString("weather_cache_json", v).apply()

    var weatherFetchedAt: Long
        get() = sp.getLong("weather_fetched_at", 0L)
        set(v) = sp.edit().putLong("weather_fetched_at", v).apply()

    // ---------- 健康提醒 ----------
    /** 提醒总开关（关闭后撤销全部提醒闹钟） */
    var reminderEnabled: Boolean
        get() = sp.getBoolean("reminder_enabled", true)
        set(v) { sp.edit().putBoolean("reminder_enabled", v).commit() }   // 同步写，防进程被杀丢写入

    /** 提醒规则列表 */
    fun reminderRules(): MutableList<com.safphere.launcher.reminder.ReminderRule> {
        val arr = sp.getString("reminder_rules", null) ?: return mutableListOf()
        return runCatching {
            val a = org.json.JSONArray(arr)
            MutableList(a.length()) {
                com.safphere.launcher.reminder.ReminderRule.fromJson(a.getJSONObject(it))!!
            }.filterNotNullTo(mutableListOf())
        }.getOrDefault(mutableListOf())
    }

    fun saveReminderRules(list: List<com.safphere.launcher.reminder.ReminderRule>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("reminder_rules", arr.toString()).apply()
    }

    /** 天气提醒触发记录：ruleId -> "yyyy-MM-dd"（当天只提醒一次） */
    fun reminderFiredAt(ruleId: String): String =
        reminderFiredMap()[ruleId] ?: ""

    fun setReminderFiredAt(ruleId: String, date: String) {
        val map = reminderFiredMap()
        map[ruleId] = date
        sp.edit().putString("reminder_fired", org.json.JSONObject(map as Map<*, *>).toString()).apply()
    }

    /** 清掉非今天的记录，防止无限增长 */
    fun pruneReminderFired(today: String) {
        val map = reminderFiredMap().filterValues { it == today }
        sp.edit().putString("reminder_fired", org.json.JSONObject(map).toString()).apply()
    }

    private fun reminderFiredMap(): MutableMap<String, String> {
        val s = sp.getString("reminder_fired", null) ?: return mutableMapOf()
        return runCatching {
            val o = org.json.JSONObject(s)
            mutableMapOf<String, String>().apply { o.keys().forEach { k -> put(k, o.optString(k)) } }
        }.getOrDefault(mutableMapOf())
    }

    // ---------- 防沉迷 ----------
    /** 防沉迷总开关 */
    var guardEnabled: Boolean
        get() = sp.getBoolean("guard_enabled", false)
        set(v) { sp.edit().putBoolean("guard_enabled", v).commit() }   // 同步写，防进程被杀丢写入

    /** 紧急求助(SOS)功能开关：默认关，子女开启后桌面才显示求助条 */
    var sosEnabled: Boolean
        get() = sp.getBoolean("sos_enabled", false)
        set(v) { sp.edit().putBoolean("sos_enabled", v).commit() }

    /** AI语音问答功能开关：默认关，子女开启后生活页才显示入口 */
    var aiEnabled: Boolean
        get() = sp.getBoolean("ai_enabled", false)
        set(v) { sp.edit().putBoolean("ai_enabled", v).commit() }

    /** AI 悬浮球开关：默认关；需要应用的无障碍服务已开启才能显示 */
    var floatingBallEnabled: Boolean
        get() = sp.getBoolean("floating_ball_enabled", false)
        set(v) = sp.edit().putBoolean("floating_ball_enabled", v).apply()

    /** 规则：pkg -> ruleJson */
    fun guardRules(): MutableMap<String, String> {
        val arr = sp.getString("guard_rules", null) ?: return mutableMapOf()
        return runCatching {
            val o = org.json.JSONObject(arr)
            mutableMapOf<String, String>().apply { o.keys().forEach { k -> put(k, o.optString(k)) } }
        }.getOrDefault(mutableMapOf())
    }

    fun guardRule(pkg: String): com.safphere.launcher.guard.GuardRule? =
        guardRules()[pkg]?.let { runCatching {
            com.safphere.launcher.guard.GuardRule.fromJson(pkg, org.json.JSONObject(it))
        }.getOrNull() }

    fun saveGuardRule(rule: com.safphere.launcher.guard.GuardRule) {
        val map = guardRules()
        map[rule.pkg] = rule.toJson().toString()
        sp.edit().putString("guard_rules", org.json.JSONObject(map as Map<*, *>).toString()).apply()
    }

    fun removeGuardRule(pkg: String) {
        val map = guardRules()
        map.remove(pkg)
        sp.edit().putString("guard_rules", org.json.JSONObject(map as Map<*, *>).toString()).apply()
    }

    /** 被我们(DO)隐藏的应用集合（条件恢复用） */
    fun guardHidden(): MutableSet<String> {
        val arr = sp.getString("guard_hidden", null) ?: return mutableSetOf()
        return runCatching {
            val a = org.json.JSONArray(arr)
            mutableSetOf<String>().apply { for (i in 0 until a.length()) add(a.getString(i)) }
        }.getOrDefault(mutableSetOf())
    }

    fun saveGuardHidden(set: Set<String>) {
        sp.edit().putString("guard_hidden", org.json.JSONArray(set).toString()).apply()
    }
}
