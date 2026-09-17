package com.safphere.launcher.guard

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * 进程内看门狗：Launcher 常驻进程 + 主线程 Handler 每分钟检查一次前台应用。
 * AlarmManager 在部分ROM/模拟器上会被延迟，此路径保证屏幕亮着时（正是使用时段）必查。
 * 进程被杀时由 SCREEN_ON 广播 / AlarmManager 补充拉起并重启本循环。
 */
object GuardTicker {

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val app = com.safphere.launcher.SafphereApp.instance ?: return
            heartbeat(app, "tick")
            Thread {
                runCatching { GuardEnforcer.enforceForeground(app) }
                    .onFailure { android.util.Log.w("GuardTicker", "enforce: ${it.message}") }
            }.start()
            handler.postDelayed(this, 60_000L)
        }
    }

    fun start(context: Context) {
        heartbeat(context, "start invoked enabled=" + com.safphere.launcher.data.Prefs.guardEnabled)
        if (running) return
        if (!com.safphere.launcher.data.Prefs.guardEnabled) return
        running = true
        heartbeat(context, "ticker running")
        handler.post(tick)
        android.util.Log.w("GuardTicker", "started")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    /** 开关切换时重启循环 */
    fun restart(context: Context) {
        stop()
        start(context)
    }

    /** 文件心跳：仅 debug 构建，写私有目录，无敏感数据，5KB 上限（超出截断） */
    private fun heartbeat(context: Context, msg: String) {
        if (!com.safphere.launcher.BuildConfig.DEBUG) return
        runCatching {
            val f = java.io.File(context.filesDir, "guard_heartbeat.txt")
            if (f.length() > 5_000) f.delete()
            f.appendText("${System.currentTimeMillis()} $msg\n")
        }
    }
}
