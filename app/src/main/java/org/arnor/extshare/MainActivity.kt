package org.arnor.extshare

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Rational
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import org.arnor.extshare.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var rearMirror: RearDisplayMirror
    private val handler = Handler(Looper.getMainLooper())
    private var shownLog = ""
    private var lastReopenAt = 0L
    private var isTopResumed = false
    // Set when the cover session must wait until ExtShare is the focused app again.
    private var pendingRearStart = false
    // Set while entering picture-in-picture before asking for one-app consent.
    private var pendingOneAppConsent = false

    private val logRefresher = object : Runnable {
        override fun run() {
            val log = DebugLog.dump()
            if (log != shownLog) {
                shownLog = log
                binding.eventLog.text = log
            }
            handler.postDelayed(this, 1000L)
        }
    }

    // Stop action shown in the picture-in-picture controls.
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            DebugLog.log(TAG, "Stop requested via broadcast")
            stopMirroring()
        }
    }

    private val requestProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                DebugLog.log(TAG, "Screen-capture consent granted")
                startMirroring(result.resultCode, result.data!!)
            } else {
                DebugLog.log(TAG, "Screen-capture consent denied")
                Toast.makeText(this, "Permission is required to mirror the screen.", Toast.LENGTH_SHORT).show()
            }
            renderState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        rearMirror = RearDisplayMirror(
            activity = this,
            onChanged = {
                renderState()
                maybeReopenCover()
            },
            onEnded = {
                if (MirrorService.isRunning.get()) {
                    if (TestSettings.get(this, TestSettings.KEEP_CAPTURE)) {
                        DebugLog.log(TAG, "Cover session ended; capture kept running")
                    } else {
                        MirrorService.stop(this)
                    }
                }
            }
        )

        binding.startButton.setOnClickListener {
            val wholeScreen = TestSettings.get(this, TestSettings.WHOLE_SCREEN)
            if (!wholeScreen && rearMirror.isAvailable && isTopResumed) {
                startOneAppShare()
            } else {
                requestMediaProjection()
            }
            renderState()
        }

        binding.stopButton.setOnClickListener {
            DebugLog.log(TAG, "Stop tapped")
            stopMirroring()
        }

        ContextCompat.registerReceiver(
            this, stopReceiver, IntentFilter(ACTION_STOP_MIRRORING), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        binding.testButton.setOnClickListener {
            runExternalDisplayTest()
        }

        setUpTestLab()
        DebugLog.log(TAG, "Activity created (multiWindow=$isInMultiWindowMode)")
        renderState()
    }

    private fun setUpTestLab() {
        val wholeScreen = TestSettings.get(this, TestSettings.WHOLE_SCREEN)
        binding.captureMode.check(if (wholeScreen) R.id.captureWholeScreen else R.id.captureChoose)
        binding.captureMode.setOnCheckedChangeListener { _, checkedId ->
            TestSettings.set(this, TestSettings.WHOLE_SCREEN, checkedId == R.id.captureWholeScreen)
        }

        bindSwitch(binding.keepCapture, TestSettings.KEEP_CAPTURE)
        bindSwitch(binding.autoReopen, TestSettings.AUTO_REOPEN)
        bindSwitch(binding.autoPip, TestSettings.AUTO_PIP) { updatePipParams() }
        bindSwitch(binding.floatingBubble, TestSettings.FLOATING_BUBBLE) {
            if (TestSettings.get(this, TestSettings.FLOATING_BUBBLE) && !FloatingBubble.canShow(this)) {
                DebugLog.log(TAG, "Opening overlay permission settings")
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            updatePipParams()
        }
        updatePipParams()

        binding.enterPip.setOnClickListener { enterPip("button") }

        binding.copyLog.setOnClickListener {
            val text = "ExtShare ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE}, " +
                "${Build.MANUFACTURER} ${Build.MODEL}\n${DebugLog.dump()}"
            getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("ExtShare log", text))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        }
        binding.clearLog.setOnClickListener { DebugLog.clear() }
    }

    private fun bindSwitch(view: CompoundButton, key: String, onChange: () -> Unit = {}) {
        view.isChecked = TestSettings.get(this, key)
        view.setOnCheckedChangeListener { _, checked ->
            TestSettings.set(this, key, checked)
            onChange()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stopReceiver)
    }

    private fun stopMirroring() {
        FloatingBubble.hide(this)
        pendingRearStart = false
        pendingOneAppConsent = false
        rearMirror.stop()
        MirrorService.stop(this)
        renderState()
    }

    override fun onStart() {
        super.onStart()
        FloatingBubble.hide(this)
        MirrorService.onStateChanged = { renderState() }
        renderState()
        handler.post(logRefresher)
    }

    override fun onStop() {
        super.onStop()
        DebugLog.log(TAG, "Activity stopped")
        if (MirrorService.isRunning.get() && useBubble()) FloatingBubble.show(this)
        MirrorService.onStateChanged = null
        handler.removeCallbacks(logRefresher)
    }

    override fun onResume() {
        super.onResume()
        DebugLog.log(TAG, "Activity resumed (multiWindow=$isInMultiWindowMode, pip=$isInPictureInPictureMode)")
        renderState()
        maybeReopenCover()
    }

    override fun onPause() {
        super.onPause()
        DebugLog.log(TAG, "Activity paused")
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        DebugLog.log(TAG, "Top resumed activity=$isTopResumedActivity")
        isTopResumed = isTopResumedActivity
        if (isTopResumedActivity && pendingRearStart) {
            pendingRearStart = false
            DebugLog.log(TAG, "ExtShare is focused again; opening cover session")
            rearMirror.start()
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        DebugLog.log(TAG, "Multi-window mode=$isInMultiWindowMode")
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        DebugLog.log(TAG, "Picture-in-picture mode=$isInPictureInPictureMode")
        binding.mainContent.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        binding.pipContent.visibility = if (isInPictureInPictureMode) View.VISIBLE else View.GONE
        if (isInPictureInPictureMode && pendingOneAppConsent) {
            pendingOneAppConsent = false
            launchOneAppConsent()
        }
        maybeReopenCover()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP automatically via setAutoEnterEnabled.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && shouldAutoPip()) {
            enterPip("leaving app")
        }
    }

    /** Picture-in-picture keeps ExtShare visible, which keeps the cover session alive in other apps. */
    private fun shouldAutoPip(): Boolean =
        TestSettings.get(this, TestSettings.AUTO_PIP) && MirrorService.isRunning.get() && !useBubble()

    /** The floating-button experiment replaces picture-in-picture when enabled and permitted. */
    private fun useBubble(): Boolean =
        TestSettings.get(this, TestSettings.FLOATING_BUBBLE) && FloatingBubble.canShow(this)

    private fun pipParams(): PictureInPictureParams {
        // A wide window keeps the picture-in-picture overlay small.
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(2, 1))
        if (MirrorService.isRunning.get()) {
            val stopIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_STOP_MIRRORING).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE
            )
            val stopTitle = getString(R.string.pip_stop)
            builder.setActions(listOf(
                RemoteAction(Icon.createWithResource(this, R.drawable.ic_stop), stopTitle, stopTitle, stopIntent)
            ))
        } else {
            builder.setActions(emptyList())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(shouldAutoPip())
        }
        return builder.build()
    }

    private fun updatePipParams() {
        runCatching { setPictureInPictureParams(pipParams()) }
            .onFailure { DebugLog.log(TAG, "setPictureInPictureParams failed", it) }
    }

    private fun enterPip(reason: String): Boolean {
        val entered = runCatching { enterPictureInPictureMode(pipParams()) }
            .onFailure { DebugLog.log(TAG, "enterPictureInPictureMode failed", it) }
            .getOrDefault(false)
        DebugLog.log(TAG, "Enter picture-in-picture ($reason) -> $entered")
        return entered
    }

    /**
     * Sharing one app makes Android open that app over ExtShare, which would end the cover session.
     * So open the cover while ExtShare is focused, shrink to picture-in-picture, then ask for consent.
     */
    private fun startOneAppShare() {
        DebugLog.log(TAG, "One-app share: opening cover and shrinking before asking for consent")
        if (!rearMirror.start()) {
            requestMediaProjection()
            return
        }
        pendingOneAppConsent = true
        if (!enterPip("one-app share")) {
            pendingOneAppConsent = false
            launchOneAppConsent()
        }
    }

    private fun launchOneAppConsent() {
        // A separate task: launched from our pinned task, the dialog would pull ExtShare back to full screen.
        DebugLog.log(TAG, "Requesting one-app screen-capture consent in a separate task")
        startActivity(
            Intent(this, RequestPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(RequestPermissionActivity.EXTRA_REAR_DISPLAY, true)
        )
    }

    /** Re-opens the cover session while capture is still running, e.g. after returning to ExtShare. */
    private fun maybeReopenCover() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (!TestSettings.get(this, TestSettings.AUTO_REOPEN)) return
        if (!MirrorService.isRunning.get() || !MirrorService.isRearMode) return
        if (rearMirror.isActive || !rearMirror.isAvailable) return
        // Rate-limit so a session the system ends immediately doesn't reopen in a loop.
        val now = SystemClock.elapsedRealtime()
        if (now - lastReopenAt < REOPEN_INTERVAL_MS) return
        lastReopenAt = now
        DebugLog.log(TAG, "Reopening cover session for running capture")
        rearMirror.start()
    }

    private fun startMirroring(resultCode: Int, resultData: android.content.Intent) {
        // Foldables like the Galaxy Z Fold only power the cover panel through a rear-display session.
        if (rearMirror.isAvailable) {
            MirrorService.start(this, resultCode, resultData, externalSurface = true)
            // The consent result arrives before ExtShare regains focus, and Android only lets
            // the focused app switch the cover on.
            if (rearMirror.isActive) {
                DebugLog.log(TAG, "Cover session already open")
            } else if (isTopResumed) {
                rearMirror.start()
            } else {
                DebugLog.log(TAG, "Waiting for ExtShare to be focused before opening cover session")
                pendingRearStart = true
            }
        } else {
            MirrorService.start(this, resultCode, resultData)
        }
    }

    private fun requestMediaProjection() {
        val manager: MediaProjectionManager? = getSystemService()
        if (manager == null) {
            Toast.makeText(this, "MediaProjection unavailable on this device.", Toast.LENGTH_LONG).show()
            return
        }
        val wholeScreen = TestSettings.get(this, TestSettings.WHOLE_SCREEN)
        DebugLog.log(TAG, "Requesting screen-capture consent (wholeScreen=$wholeScreen)")
        requestProjectionLauncher.launch(ProjectionStore.captureIntent(manager, wholeScreen))
    }

    private fun renderState() {
        val running = MirrorService.isRunning.get()
        val coverDark = running && MirrorService.isRearMode && !rearMirror.isActive

        // Start asks for screen-capture consent itself; consent can't be reused between sessions.
        binding.requestPermission.visibility = View.GONE
        binding.startButton.isEnabled = !running
        binding.stopButton.isEnabled = running

        binding.statusLabel.text = getString(if (running) R.string.status_running else R.string.status_ready)

        binding.statusDetails.text = when {
            coverDark -> "Still capturing, but the cover session has ended. Return to ExtShare to reopen it."
            running -> "Your main display should now be mirrored onto the external display."
            else -> "Tap Start or use the Quick Settings tile. Android asks for screen-capture permission each time."
        }
        binding.pipStatus.text = when {
            !running -> getString(R.string.status_ready)
            coverDark -> getString(R.string.pip_cover_off)
            else -> getString(R.string.pip_mirroring)
        }
        updatePipParams()

        if (binding.testResult.text.isNullOrEmpty()) {
            binding.testResult.text = "${getString(R.string.test_result_prefix)} Not run yet."
        }
    }

    private fun runExternalDisplayTest() {
        val dm: DisplayManager? = getSystemService()
        if (dm == null) {
            setTestResult("DisplayManager unavailable.")
            return
        }
        val probe = DisplayProbe.collect(this)

        val external = DisplayProbe.pickExternalDisplay(this)

        val details = "${rearMirror.describe()}\n${probe.report}"

        if (rearMirror.isAvailable) {
            val message = when {
                rearMirror.isActive -> "A cover session is already running; stop mirroring first to run the test."
                rearMirror.startTestCard() -> "Showing a magenta test card on the cover for ${RearDisplayMirror.TEST_CARD_MS / 1000} seconds."
                else -> "Could not request a cover session."
            }
            setTestResult("$message\n$details")
            return
        }

        if (external == null) {
            setTestResult("No external/cover display detected.\n$details")
            return
        }

        try {
            val presentation = DisplayTestPresentation(this, external)
            presentation.show()
            val listed = probe.primaryList.any { it.displayId == external.displayId }
            val note = if (listed) "" else " Warning: this display is not in DisplayManager's active list, so it is likely powered off and nothing will appear."
            setTestResult("Displayed test card on display ${external.displayId} (${external.name}).$note\n$details")
            handler.postDelayed({ presentation.dismiss() }, 2000)
        } catch (t: Throwable) {
            setTestResult("Failed to present on display ${external.displayId}: ${t.message}\n$details")
        }
    }

    private fun setTestResult(text: String) {
        binding.testResult.text = "${getString(R.string.test_result_prefix)} $text"
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REOPEN_INTERVAL_MS = 5000L
        const val ACTION_STOP_MIRRORING = "org.arnor.extshare.STOP_MIRRORING"
    }
}
