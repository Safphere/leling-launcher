package com.safphere.launcher.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.safphere.launcher.data.Prefs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 无障碍服务（乐龄桌面 · 广告卫士 + 一键重启）：
 *
 * 1) 一键重启：performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) 弹出系统电源菜单，
 *    配合界面大字引导老人点击「重启」。
 * 2) 广告卫士：检测第三方应用开屏页上的「跳过」控件，自动替老人点击，实现防开屏应用广告。
 *
 * 安全边界：
 * - 只点击文本为「跳过 / Skip」（可带倒计时数字）的控件，绝不碰「× / 下载 / 安装 / 领取」
 * - 自身与系统界面不在处理范围；同一应用 8 秒内最多处理一次
 * - 节点仅在内存中短暂读取用于定位按钮，不存储、不上传任何屏幕内容
 * - 可在子女模式中关闭（ad_skip_enabled，默认开启）
 */
class ElderAccessibilityService : AccessibilityService() {

    private val cooldowns = ConcurrentHashMap<String, Long>()
    private val executor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        executor.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !Prefs.adSkipEnabled) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in EXCLUDED_PACKAGES) return
        val now = SystemClock.elapsedRealtime()
        if (now - (cooldowns[pkg] ?: 0L) < COOLDOWN_MS) return
        executor.execute {
            if (!Prefs.adSkipEnabled) return@execute
            if (trySkipAd(pkg)) cooldowns[pkg] = SystemClock.elapsedRealtime()
        }
    }

    override fun onInterrupt() {}

    /** 在当前窗口中查找「跳过」控件并自动点击；成功返回 true */
    private fun trySkipAd(pkg: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findSkipNode(root) ?: return false
        val clicked = if (target.node.isClickable) {
            target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            val r = Rect()
            target.node.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) false else tapCenter(r)
        }
        if (clicked) android.util.Log.i(TAG, "ad guard: auto-clicked skip in $pkg")
        return clicked
    }

    private fun tapCenter(r: Rect): Boolean {
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build(),
            null,
            null
        )
    }

    private data class SkipTarget(val node: AccessibilityNodeInfo)

    /** 广度优先查找「跳过 / Skip」控件（可带倒计时数字），最多遍历 400 个节点 */
    private fun findSkipNode(root: AccessibilityNodeInfo): SkipTarget? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val n = queue.removeFirst()
            visited++
            val text = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            if (SKIP_REGEX.containsMatchIn(text) || SKIP_REGEX.containsMatchIn(desc)) {
                if (n.isClickable) return SkipTarget(n)
                var p: AccessibilityNodeInfo? = n.parent
                var hops = 0
                while (p != null && hops < 3) {
                    if (p.isClickable) return SkipTarget(p)
                    p = p.parent
                    hops++
                }
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) return SkipTarget(n)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    companion object {
        private const val TAG = "AdGuard"
        private const val COOLDOWN_MS = 8_000L
        private const val MAX_NODES = 400
        private val EXCLUDED_PACKAGES = setOf("com.android.systemui")

        /** 只认「跳过 / Skip」（可带倒计时数字） */
        private val SKIP_REGEX = Regex("(\u8DF3\u8FC7\\s*|skip)", RegexOption.IGNORE_CASE)

        @Volatile
        var instance: ElderAccessibilityService? = null
            private set
    }
}
