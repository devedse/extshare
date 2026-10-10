package org.arnor.extshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.service.quicksettings.TileService
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class MirrorService : Service(), DisplayManager.DisplayListener {

    private val channelId = "mirror_channel"
    private val notificationId = 42

    private lateinit var displayManager: DisplayManager
    private val retryHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: MirrorPresentation? = null
    private var targetDisplayId: Int? = null
    // True when the cover panel is driven by RearDisplayMirror instead of a Presentation.
    private var useExternalSurface = false
    // Last logged state per display, so only real power changes are logged.
    private val loggedDisplayStates = mutableMapOf<Int, Int>()
    private val retryRunnable = Runnable {
        if (isRunning.get()) {
            startPresentation()
        }
    }

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(this, null)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultData = intent?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra(EXTRA_RESULT_DATA)
            }
        }
        if (mediaProjection == null && resultData == null) {
            DebugLog.log(TAG, "No screen-capture consent in start intent; stopping.")
            // startForegroundService() requires startForeground() even when bailing out.
            runCatching { startForeground(notificationId, buildNotification()) }
                .onFailure { DebugLog.log(TAG, "startForeground without consent failed", it) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isRunning.get()) {
            isRunning.set(true)
            onStateChanged?.invoke()
        }
        useExternalSurface = intent?.getBooleanExtra(EXTRA_EXTERNAL_SURFACE, false) ?: useExternalSurface
        instance = this
        DebugLog.log(TAG, "Service started (rearDisplay=$useExternalSurface)")
        if (!useExternalSurface) {
            val dualMsg = DualScreenHelper.attemptEnableDualScreen()
            Log.d(TAG, "Dual screen toggle attempt: $dualMsg")
        }
        startForeground(notificationId, buildNotification())
        if (resultData != null) {
            startProjection(intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0, resultData)
        }
        updateTileState()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        displayManager.unregisterDisplayListener(this)
        if (instance === this) instance = null
        DebugLog.log(TAG, "Service stopped")
        FloatingBubble.hide(this)
        releaseAll()
        isRunning.set(false)
        onStateChanged?.invoke()
        retryHandler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        updateTileState()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProjection(resultCode: Int, resultData: Intent) {
        if (mediaProjection != null) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            DebugLog.log(TAG, "MediaProjectionManager unavailable; stopping.")
            stopSelf()
            return
        }
        val projection = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (t: Throwable) {
            DebugLog.log(TAG, "getMediaProjection failed", t)
            null
        }
        if (projection == null) {
            DebugLog.log(TAG, "getMediaProjection returned null; stopping.")
            stopSelf()
            return
        }
        DebugLog.log(TAG, "MediaProjection obtained")
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                DebugLog.log(TAG, "MediaProjection stopped by system")
                stopSelf()
            }

            // Android 14+: reports single-app capture size and whether the shared app is visible.
            override fun onCapturedContentResize(width: Int, height: Int) {
                DebugLog.log(TAG, "Captured content size ${width}x$height")
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                DebugLog.log(TAG, "Captured content visible=$isVisible")
            }
        }, null)
        if (useExternalSurface) {
            externalSurface?.let { attachVirtualDisplay(it) }
                ?: DebugLog.log(TAG, "Waiting for rear-display surface")
        } else {
            startPresentation()
        }
    }

    private fun startPresentation() {
        retryHandler.removeCallbacksAndMessages(null)
        val display = chooseExternalDisplay()
        if (display == null) {
            DebugLog.log(TAG, "No external display found; waiting for one to appear.")
            targetDisplayId = null
            scheduleRetry()
            return
        }
        targetDisplayId = display.displayId
        if (display.state == Display.STATE_OFF) {
            Log.d(TAG, "Target display ${display.displayId} is OFF; launching waker activity.")
            WakerActivity.launch(this, display.displayId)
            val toggleMsg = DisplayPowerToggler.forceOn(display)
            val cliMsg = CliHelper.wakeCover(this)
            Log.d(TAG, "Display power toggle attempts: $toggleMsg | CLI: $cliMsg")
        }
        presentation?.dismiss()
        presentation = MirrorPresentation(
            context = this,
            display = display,
            onSurfaceReady = { holder -> attachVirtualDisplay(holder) },
            onSurfaceDestroyed = { detachVirtualDisplay() }
        )
        try {
            presentation?.show()
            DebugLog.log(TAG, "Presentation shown on display ${display.displayId} ${display.name}")
        } catch (t: Throwable) {
            DebugLog.log(TAG, "Failed to show presentation on display ${display.displayId}", t)
            stopSelf()
        }
    }

    private fun attachVirtualDisplay(holder: SurfaceHolder) {
        val projection = mediaProjection ?: return
        val targetDisplay = targetDisplayId?.let { displayManager.getDisplay(it) }
        val sourceMetrics = mainDisplayMetrics() ?: return
        var width = sourceMetrics.widthPixels
        var height = sourceMetrics.heightPixels

        if (useExternalSurface) {
            // Render at the cover panel's size; the mirror is letterboxed into it.
            val frame = holder.surfaceFrame
            if (frame.width() > 0 && frame.height() > 0) {
                width = frame.width()
                height = frame.height()
            }
        } else {
            val targetMetrics = displayMetricsFor(targetDisplay) ?: sourceMetrics
            // Match the surface to the external panel; capture uses the main display size.
            holder.setFixedSize(
                targetMetrics.widthPixels.coerceAtLeast(1),
                targetMetrics.heightPixels.coerceAtLeast(1)
            )
        }

        // Android 14+ allows only one createVirtualDisplay per MediaProjection; reuse it.
        virtualDisplay?.let {
            it.resize(width, height, sourceMetrics.densityDpi)
            it.surface = holder.surface
            DebugLog.log(TAG, "Re-targeted virtual display to ${width}x$height")
            return
        }
        Log.d(
            TAG,
            "Creating virtual display source=${sourceMetrics.widthPixels}x${sourceMetrics.heightPixels}@" +
                "${sourceMetrics.densityDpi} targetSurface=${holder.surfaceFrame} " +
                "displayId=$targetDisplay external=$useExternalSurface"
        )
        virtualDisplay = try {
            projection.createVirtualDisplay(
                "mirror-virtual-display",
                width,
                height,
                sourceMetrics.densityDpi,
                // AUTO_MIRROR mirrors the default display. SECURE would need a system permission.
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                holder.surface,
                object : VirtualDisplay.Callback() {
                    override fun onStopped() {
                        DebugLog.log(TAG, "Virtual display stopped")
                        stopSelf()
                    }
                },
                null
            )
        } catch (t: Throwable) {
            DebugLog.log(TAG, "createVirtualDisplay failed", t)
            null
        }
        if (virtualDisplay == null) {
            DebugLog.log(TAG, "Failed to create virtual display; stopping.")
            stopSelf()
        } else {
            DebugLog.log(TAG, "Virtual display created ${width}x$height")
        }
    }

    private fun detachVirtualDisplay() {
        virtualDisplay?.surface = null
    }

    private fun releaseAll() {
        virtualDisplay?.release()
        virtualDisplay = null
        presentation?.dismiss()
        presentation = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun chooseExternalDisplay(): Display? {
        val probe = DisplayProbe.collect(this)
        Log.d(TAG, "Display probe:\n${probe.report}")
        val external = DisplayProbe.pickExternalDisplay(this)
        if (external != null) {
            Log.d(TAG, "Using external display ${external.displayId} ${external.name}")
            return external
        }
        Log.w(TAG, "No external/non-default display found.")
        return null
    }

    private fun scheduleRetry() {
        retryHandler.postDelayed(retryRunnable, 1500L)
    }

    private fun displayMetricsFor(display: Display?): DisplayMetrics? {
        if (display == null) return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return metrics
    }

    private fun mainDisplayMetrics(): DisplayMetrics? {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return metrics
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Foreground service for mirroring"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content))
            .setOngoing(true)
            .build()
    }

    private fun logDisplayState(displayId: Int, event: String) {
        val display = displayManager.getDisplay(displayId)
        val state = display?.state ?: -1
        if (event == "changed" && loggedDisplayStates[displayId] == state) return
        loggedDisplayStates[displayId] = state
        val stateName = when (state) {
            Display.STATE_ON -> "ON"
            Display.STATE_OFF -> "OFF"
            Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> "DOZE"
            -1 -> "gone"
            else -> state.toString()
        }
        DebugLog.log(TAG, "Display $displayId ${display?.name ?: ""} $event, state=$stateName")
    }

    override fun onDisplayAdded(displayId: Int) {
        logDisplayState(displayId, "added")
        if (!useExternalSurface && targetDisplayId == null) {
            startPresentation()
        }
    }

    override fun onDisplayRemoved(displayId: Int) {
        logDisplayState(displayId, "removed")
        if (displayId == targetDisplayId) {
            stopSelf()
        }
    }

    override fun onDisplayChanged(displayId: Int) {
        logDisplayState(displayId, "changed")
        if (displayId == targetDisplayId) {
            startPresentation()
        }
    }

    private fun updateTileState() {
        TileService.requestListeningState(
            this,
            ComponentName(this, MirrorTileService::class.java)
        )
    }

    companion object {
        private const val TAG = "MirrorService"
        private const val EXTRA_EXTERNAL_SURFACE = "external_surface"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        val isRunning: AtomicBoolean = AtomicBoolean(false)

        /** Main-thread listener for start/stop, used by MainActivity to refresh its UI. */
        var onStateChanged: (() -> Unit)? = null

        // Main-thread only: the running service and the surface handed over by RearDisplayMirror.
        private var instance: MirrorService? = null
        private var externalSurface: SurfaceHolder? = null

        /** Starts capture with a fresh screen-capture consent result. */
        fun start(context: Context, resultCode: Int, resultData: Intent, externalSurface: Boolean = false) {
            val intent = Intent(context, MirrorService::class.java)
                .putExtra(EXTRA_EXTERNAL_SURFACE, externalSurface)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, intent)
        }

        /** True while capturing for a rear-display session (attached or waiting for a surface). */
        val isRearMode: Boolean
            get() = instance?.useExternalSurface == true

        fun attachExternalSurface(holder: SurfaceHolder) {
            externalSurface = holder
            instance?.takeIf { it.useExternalSurface }?.attachVirtualDisplay(holder)
        }

        fun detachExternalSurface() {
            externalSurface = null
            instance?.takeIf { it.useExternalSurface }?.detachVirtualDisplay()
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MirrorService::class.java))
        }
    }
}
