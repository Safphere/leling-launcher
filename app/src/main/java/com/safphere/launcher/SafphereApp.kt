package com.safphere.launcher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.safphere.launcher.data.Prefs
import com.safphere.launcher.sim.SimMonitor
import com.safphere.launcher.tts.Speaker

class SafphereApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 必须最先同步初始化：主线程随后的 Prefs 读取不能依赖后台线程时序
        com.safphere.launcher.data.Prefs.init(this)
        // debug 构建：空数据时预置演示联系人（方便开发者克隆即测；release 不含）
        if (BuildConfig.DEBUG && Prefs.contacts().isEmpty()) {
            Prefs.saveContacts(listOf(com.safphere.launcher.data.Contact(
                id = 1, name = "儿子", phone = "13800138000", emergency = true)))
        }
        // 白名单/快捷栏初始化移后台线程（避免主线程枚举全部应用拖慢冷启动）
        Thread { Prefs.ensureDefaultWhitelist(this) }.start()
        Speaker.init(this)
        SimMonitor.scheduleNext(this)
        registerSimStateListener()
        registerSmsListener()
        com.safphere.launcher.guard.GuardTicker.start(this)

        // 悬浮球开关开着且无障碍已启用：进程重启后恢复悬浮球
        if (Prefs.floatingBallEnabled &&
            com.safphere.launcher.perm.PermCatalog.accessibilityEnabled(this)
        ) {
            runCatching { com.safphere.launcher.agent.FloatingBallService.start(this) }
        }

        // 屏幕点亮即做一次防沉迷检查并恢复循环（闹钟可能被系统省电延迟）
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action == Intent.ACTION_SCREEN_OFF) {
                        // 息屏停闹钟（防沉迷只管亮屏时段，省电）
                        com.safphere.launcher.guard.GuardWatchdogReceiver.cancel(c)
                        com.safphere.launcher.guard.GuardTicker.stop()
                        return
                    }
                    if (!com.safphere.launcher.data.Prefs.guardEnabled) return
                    Thread {
                        runCatching {
                            com.safphere.launcher.guard.GuardEnforcer.enforceForeground(c.applicationContext)
                        }
                    }.start()
                    com.safphere.launcher.guard.GuardWatchdogReceiver.scheduleNext(c.applicationContext)
                    com.safphere.launcher.guard.GuardTicker.start(c)
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** 实时接收运营商回复短信（manifest注册兜底，动态注册保进程存活时必达） */
    private fun registerSmsListener() {
        ContextCompat.registerReceiver(
            this,
            com.safphere.launcher.flow.SmsReceiver(),
            IntentFilter("android.provider.Telephony.SMS_RECEIVED"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** 实时监听SIM卡插拔/松动（受保护系统广播，动态注册；action/extra为hidden常量，用字面量） */
    private fun registerSimStateListener() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getStringExtra(EXTRA_SIM_STATE)
                SimMonitor.onSimStateChanged(context, state)
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(ACTION_SIM_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    companion object {
        private const val ACTION_SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED"
        private const val EXTRA_SIM_STATE = "ss"
        @Volatile
        var instance: SafphereApp? = null
            private set
    }
}
