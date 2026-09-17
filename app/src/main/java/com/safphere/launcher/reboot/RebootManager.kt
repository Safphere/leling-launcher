package com.safphere.launcher.reboot

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import com.safphere.launcher.admin.ElderDeviceAdminReceiver
import com.safphere.launcher.a11y.ElderAccessibilityService

/**
 * 一键重启三级降级策略：
 * 1. Device Owner（设备所有者）→ DevicePolicyManager.reboot() 静默重启（最优）
 * 2. 无障碍服务 → 弹出系统电源菜单 + 界面引导点「重启」
 * 3. 都没有 → 返回 MANUAL，由界面展示长按电源键的图文引导
 */
object RebootManager {

    private const val TAG = "RebootManager"

    enum class Method { DEVICE_OWNER, POWER_DIALOG, MANUAL }

    fun isDeviceOwner(context: Context): Boolean = runCatching {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isDeviceOwnerApp(context.packageName)
    }.getOrDefault(false)

    /**
     * 尝试重启。返回实际采用的方式：
     * - DEVICE_OWNER：重启已下发（函数通常不会返回，系统立即开始重启）
     * - POWER_DIALOG：已弹出系统电源菜单，调用方应显示「请点击重启」引导
     * - MANUAL：无权限，调用方应显示长按电源键引导
     */
    fun tryReboot(context: Context): Method {
        // 1) Device Owner 静默重启
        runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.reboot(ElderDeviceAdminReceiver.component(context))
                return Method.DEVICE_OWNER
            }
        }.onFailure { Log.w(TAG, "device-owner reboot failed", it) }

        // 2) 无障碍弹电源菜单（任一已启用的无障碍服务都可执行全局动作）
        runCatching {
            val ok = ElderAccessibilityService.instance
                ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) == true ||
                com.safphere.launcher.agent.AgentAccessibilityService.instance
                    ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) == true
            if (ok) return Method.POWER_DIALOG
        }.onFailure { Log.w(TAG, "power dialog failed", it) }

        // 3) 手动引导
        return Method.MANUAL
    }
}
