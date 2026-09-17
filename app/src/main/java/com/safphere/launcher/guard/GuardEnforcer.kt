package com.safphere.launcher.guard

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import com.safphere.launcher.admin.ElderDeviceAdminReceiver
import com.safphere.launcher.data.Prefs

/**
 * 管控执行器：
 * - 软管控：弹出全屏大字阻断页（任何设备可用）
 * - 硬管控（Device Owner）：隐藏违规应用（进程被系统杀死，无法再打开）；
 *   条件恢复（时段结束/额度重置/规则关闭时自动取消隐藏）
 */
object GuardEnforcer {

    private const val TAG = "GuardEnforcer"

    /** 软管控：显示阻断页（点击"知道了"回到桌面） */
    fun softBlock(context: Context, block: GuardBlock) {
        val rule = AppGuard.ruleOf(block.pkg)
        BlockActivity.start(context, block, rule)
    }

    /** 硬管控：DO 隐藏应用。成功返回 true（非 DO / 关键应用返回 false） */
    fun hideApp(context: Context, pkg: String): Boolean {
        if (AppGuard.isCritical(context, pkg)) return false
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return false
        val ok = runCatching {
            dpm.setApplicationHidden(ElderDeviceAdminReceiver.component(context), pkg, true)
        }.getOrDefault(false)
        if (ok) {
            val set = Prefs.guardHidden()
            set.add(pkg)
            Prefs.saveGuardHidden(set)
            Log.i(TAG, "hidden $pkg")
        }
        return ok
    }

    /** 恢复所有被我们隐藏且当前已不违规的应用（每天看门狗都会调） */
    fun unhideIfAllowed(context: Context) {
        val hidden = Prefs.guardHidden()
        if (hidden.isEmpty()) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val it = hidden.iterator()
        val remain = mutableSetOf<String>()
        while (it.hasNext()) {
            val pkg = it.next()
            val stillBlocked = Prefs.guardEnabled && AppGuard.check(context, pkg) != null
            if (!stillBlocked) {
                runCatching {
                    dpm.setApplicationHidden(ElderDeviceAdminReceiver.component(context), pkg, false)
                }
                Log.i(TAG, "unhidden $pkg")
            } else {
                remain.add(pkg)
            }
        }
        Prefs.saveGuardHidden(remain)
    }

    /** 对当前前台应用执行检查并管控（看门狗每分钟调用）。返回是否发生了阻断 */
    fun enforceForeground(context: Context): Boolean {
        if (!Prefs.guardEnabled) {
            unhideIfAllowed(context)
            return false
        }
        val fg = AppGuard.currentForeground(context)
        if (fg == null) return false
        val block = AppGuard.check(context, fg) ?: run {
            unhideIfAllowed(context); return false
        }
        Log.w(TAG, "block ${block.pkg} type=${block.type}")
        val hidden = hideApp(context, block.pkg)
        softBlock(context, block)
        if (!hidden) {
            // 非 DO：阻断页覆盖；用户点"知道了"回桌面后，启动拦截会挡住再次打开
        }
        return true
    }
}
