package com.safphere.launcher.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：仅用于 performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) 弹出系统电源菜单，
 * 配合界面大字引导老人点击「重启」。不读取屏幕内容（canRetrieveWindowContent=false）。
 */
class ElderAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    companion object {
        @Volatile
        var instance: ElderAccessibilityService? = null
            private set
    }
}
