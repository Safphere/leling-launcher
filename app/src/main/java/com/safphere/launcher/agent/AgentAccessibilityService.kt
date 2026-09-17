package com.safphere.launcher.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * 扩展无障碍服务：截图(API30+) + 手势注入 + 悬浮球 overlay。
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
            private set

        /** 最近一次截图（不落盘，仅内存） */
        @Volatile var lastScreenshot: Bitmap? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 截图（API 30+）。回调在主线程 */
    fun takeScreenshot(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { onResult(null); return }
        takeScreenshot(0, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bmp = Bitmap.wrapHardwareBuffer(
                    screenshot.hardwareBuffer, screenshot.colorSpace)
                screenshot.hardwareBuffer.close()
                if (bmp != null) {
                    val soft = bmp.copy(Bitmap.Config.ARGB_8888, false)
                    bmp.recycle()
                    lastScreenshot = soft
                    onResult(soft)
                } else onResult(null)
            }
            override fun onFailure(errorCode: Int) { onResult(null) }
        })
    }
}
