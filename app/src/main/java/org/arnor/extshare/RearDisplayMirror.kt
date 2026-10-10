package org.arnor.extshare

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Binder
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.core.ExperimentalWindowApi
import kotlinx.coroutines.launch

/**
 * Shows the mirror on the rear (cover) panel using Jetpack WindowManager's "dual screen"
 * presentation (WindowAreaController#presentContentOnWindowArea).
 *
 * Devices like the Galaxy Z Fold expose only one logical display at a time. The cover panel is
 * not listed as a second Display while unfolded, so a regular Presentation can't reach it. This
 * API asks the system to power the rear panel and gives us a window on it.
 */
@OptIn(ExperimentalWindowApi::class)
class RearDisplayMirror(
    private val activity: ComponentActivity,
    private val onChanged: () -> Unit,
    private val onEnded: () -> Unit
) : WindowAreaPresentationSessionCallback {

    private val controller = WindowAreaController.getOrCreate()
    private var token: Binder? = null
    private var session: WindowAreaSessionPresenter? = null

    var status: WindowAreaCapability.Status? = null
        private set

    val isAvailable: Boolean
        get() = status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE ||
            status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE

    val isActive: Boolean
        get() = session != null

    init {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.windowAreaInfos.collect { infos ->
                    val rear = infos.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING }
                    token = rear?.token
                    status = rear
                        ?.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)
                        ?.status
                        ?: WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED
                    DebugLog.log(TAG, "Rear window areas=${infos.size}, present status=$status")
                    onChanged()
                }
            }
        }
    }

    fun describe(): String = "Rear display presentation (WindowAreaController): ${status ?: "unknown"}"

    fun start(): Boolean {
        val areaToken = token
        if (session != null) return true
        if (areaToken == null || !isAvailable) return false
        controller.presentContentOnWindowArea(
            areaToken,
            activity,
            ContextCompat.getMainExecutor(activity),
            this
        )
        return true
    }

    fun stop() {
        session?.close()
    }

    override fun onSessionStarted(session: WindowAreaSessionPresenter) {
        DebugLog.log(TAG, "Rear presentation session started")
        this.session = session
        val surfaceView = SurfaceView(session.context)
        surfaceView.holder.setFormat(PixelFormat.RGBA_8888)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                DebugLog.log(TAG, "Rear surface created ${holder.surfaceFrame}")
                MirrorService.attachExternalSurface(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                DebugLog.log(TAG, "Rear surface changed ${width}x$height")
                MirrorService.attachExternalSurface(holder)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                DebugLog.log(TAG, "Rear surface destroyed")
                MirrorService.detachExternalSurface()
            }
        })
        // Briefly label the cover so it's clear the window renders even if no frames arrive.
        val label = TextView(session.context).apply {
            text = "ExtShare cover window"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(24, 12, 24, 12)
        }
        label.postDelayed({ label.visibility = View.GONE }, 5000L)
        session.setContentView(FrameLayout(session.context).apply {
            addView(surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(label, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        })
        onChanged()
    }

    override fun onSessionEnded(t: Throwable?) {
        DebugLog.log(TAG, "Rear presentation session ended", t)
        session = null
        MirrorService.detachExternalSurface()
        onEnded()
        onChanged()
    }

    override fun onContainerVisibilityChanged(isVisible: Boolean) {
        DebugLog.log(TAG, "Rear presentation visible=$isVisible")
    }

    companion object {
        private const val TAG = "RearDisplayMirror"
    }
}
