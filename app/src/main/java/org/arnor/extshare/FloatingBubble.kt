package org.arnor.extshare

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs

/**
 * Experimental floating button (overlay) shown instead of picture-in-picture while mirroring.
 * Tapping it brings ExtShare back; dragging moves it.
 */
object FloatingBubble {
    private const val TAG = "FloatingBubble"
    private const val SIZE_DP = 56
    private var view: ImageView? = null

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (view != null) return
        if (!canShow(context)) {
            DebugLog.log(TAG, "Overlay permission missing; not showing bubble")
            return
        }
        val app = context.applicationContext
        val windowManager = app.getSystemService(WindowManager::class.java)
        val size = (SIZE_DP * app.resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = app.resources.displayMetrics.widthPixels - size - size / 4
            y = app.resources.displayMetrics.heightPixels / 2
        }
        val bubble = ImageView(app).apply {
            setImageResource(R.mipmap.ic_launcher)
            contentDescription = "ExtShare"
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        bubble.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    windowManager.updateViewLayout(v, params)
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - downX) < size / 4 && abs(event.rawY - downY) < size / 4) {
                        DebugLog.log(TAG, "Bubble tapped; opening ExtShare")
                        app.startActivity(
                            Intent(app, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                    }
                }
            }
            true
        }
        runCatching { windowManager.addView(bubble, params) }
            .onSuccess {
                view = bubble
                DebugLog.log(TAG, "Bubble shown")
            }
            .onFailure { DebugLog.log(TAG, "Showing bubble failed", it) }
    }

    fun hide(context: Context) {
        val bubble = view ?: return
        view = null
        runCatching { context.applicationContext.getSystemService(WindowManager::class.java).removeView(bubble) }
        DebugLog.log(TAG, "Bubble hidden")
    }
}
