package com.safphere.launcher.agent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.safphere.launcher.R

/**
 * 悬浮球：TYPE_ACCESSIBILITY_OVERLAY（需开启应用的无障碍服务才可使用）。
 * 可拖动；单击打开 AI 语音问答（onTapCallback 默认接线，可替换）。
 */
class FloatingBallService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var ballView: View? = null
    private var added = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 默认行为：点悬浮球打开 AI 语音问答
        onTapCallback = {
            val i = Intent(this, com.safphere.launcher.ai.AiChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        show()
        return START_STICKY
    }

    private fun show() {
        if (added || wm == null) return
        val size = (52 * resources.displayMetrics.density).toInt()
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_floating_ball)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0.85f
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40; y = 400
        }
        setupDrag(view, params)
        // TYPE_ACCESSIBILITY_OVERLAY 需要应用的无障碍服务已连接；未连接时安静退出（不崩）
        val ok = runCatching { wm!!.addView(view, params) }.isSuccess
        if (!ok) {
            android.util.Log.w("FloatingBall", "overlay unavailable (accessibility off?), stop self")
            stopSelf()
            return
        }
        ballView = view
        added = true
    }

    override fun onDestroy() {
        hide()
        super.onDestroy()
    }

    private fun hide() {
        if (!added) return
        runCatching { wm?.removeView(ballView) }
        added = false
    }

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (dx * dx + dy * dy > 100) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { wm?.updateViewLayout(v, params) }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!moved) onTapCallback?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    var onTapCallback: (() -> Unit)? = null

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, FloatingBallService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBallService::class.java))
        }
    }
}
