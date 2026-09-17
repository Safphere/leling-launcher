package com.safphere.launcher.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/** 通过无障碍服务执行点击/滑动手势 */
object AgentGestures {

    /** 点击坐标 */
    fun tap(x: Float, y: Float, onDone: (Boolean) -> Unit) {
        val svc = AgentAccessibilityService.instance ?: return onDone(false)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { onDone(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { onDone(false) }
        }, null)
    }

    /** 从(x1,y1)滑到(x2,y2) */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, onDone: (Boolean) -> Unit) {
        val svc = AgentAccessibilityService.instance ?: return onDone(false)
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { onDone(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { onDone(false) }
        }, null)
    }
}
