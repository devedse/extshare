package org.arnor.extshare

import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
            requestMediaProjection()
            renderState()
        }

        binding.stopButton.setOnClickListener {
            DebugLog.log(TAG, "Stop tapped")
            pendingRearStart = false
            rearMirror.stop()
            MirrorService.stop(this)
            renderState()
        }

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

    override fun onStart() {
        super.onStart()
        handler.post(logRefresher)
    }

    override fun onStop() {
        super.onStop()
        DebugLog.log(TAG, "Activity stopped")
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
        maybeReopenCover()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP automatically via setAutoEnterEnabled.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && TestSettings.get(this, TestSettings.AUTO_PIP)) {
            enterPip("leaving app")
        }
    }

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(3, 4))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(TestSettings.get(this, TestSettings.AUTO_PIP))
        }
        return builder.build()
    }

    private fun updatePipParams() {
        runCatching { setPictureInPictureParams(pipParams()) }
            .onFailure { DebugLog.log(TAG, "setPictureInPictureParams failed", it) }
    }

    private fun enterPip(reason: String) {
        val entered = runCatching { enterPictureInPictureMode(pipParams()) }
            .onFailure { DebugLog.log(TAG, "enterPictureInPictureMode failed", it) }
            .getOrDefault(false)
        DebugLog.log(TAG, "Enter picture-in-picture ($reason) -> $entered")
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
            if (isTopResumed) {
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
    }
}
