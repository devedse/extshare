package org.arnor.extshare

import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import org.arnor.extshare.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var rearMirror: RearDisplayMirror

    private val requestProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                ProjectionStore.saveGrant(this, result.resultCode, result.data!!)
                startMirroring()
                Toast.makeText(this, "Mirroring enabled.", Toast.LENGTH_SHORT).show()
            } else {
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
            onChanged = { renderState() },
            onEnded = { if (MirrorService.isRunning.get()) MirrorService.stop(this) }
        )

        binding.requestPermission.setOnClickListener {
            requestMediaProjection()
        }

        binding.startButton.setOnClickListener {
            if (ProjectionStore.hasGrant(this)) {
                startMirroring()
            } else {
                requestMediaProjection()
            }
            renderState()
        }

        binding.stopButton.setOnClickListener {
            rearMirror.stop()
            MirrorService.stop(this)
            renderState()
        }

        binding.testButton.setOnClickListener {
            runExternalDisplayTest()
        }

        renderState()
    }

    override fun onResume() {
        super.onResume()
        renderState()
    }

    private fun startMirroring() {
        // Foldables like the Galaxy Z Fold only power the cover panel through a rear-display session.
        if (rearMirror.isAvailable) {
            MirrorService.start(this, externalSurface = true)
            rearMirror.start()
        } else {
            MirrorService.start(this)
        }
    }

    private fun requestMediaProjection() {
        val manager: MediaProjectionManager? = getSystemService()
        if (manager == null) {
            Toast.makeText(this, "MediaProjection unavailable on this device.", Toast.LENGTH_LONG).show()
            return
        }
        requestProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun renderState() {
        val hasGrant = ProjectionStore.hasGrant(this)
        val running = MirrorService.isRunning.get()

        binding.requestPermission.isEnabled = true
        binding.startButton.isEnabled = hasGrant && !running
        binding.stopButton.isEnabled = running

        binding.statusLabel.text = when {
            running -> getString(R.string.status_running)
            hasGrant -> getString(R.string.status_ready)
            else -> getString(R.string.status_missing_permission)
        }

        binding.statusDetails.text = when {
            running -> "Your main display should now be mirrored onto the external display."
            hasGrant -> "Tap Start or use the Quick Settings tile to mirror whenever the cover display is available."
            else -> "Tap Grant screen capture to allow mirroring to the external/cover display."
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

        val methodDetails = DualScreenHelper.describeDualMethods()
        val details = "${rearMirror.describe()}\n${probe.report}"

        if (rearMirror.isAvailable) {
            setTestResult("Cover display is reachable via rear-display presentation; Start mirroring will use it.\n$details\n$methodDetails")
            return
        }

        if (external == null) {
            setTestResult("No external/cover display detected.\n$details\n$methodDetails")
            return
        }

        try {
            val presentation = DisplayTestPresentation(this, external)
            presentation.show()
            val listed = probe.primaryList.any { it.displayId == external.displayId }
            val note = if (listed) "" else " Warning: this display is not in DisplayManager's active list, so it is likely powered off and nothing will appear."
            setTestResult("Displayed test card on display ${external.displayId} (${external.name}).$note\n$details\n$methodDetails")
            Handler(Looper.getMainLooper()).postDelayed({ presentation.dismiss() }, 2000)
        } catch (t: Throwable) {
            setTestResult("Failed to present on display ${external.displayId}: ${t.message}\n$details\n$methodDetails")
        }
    }

    private fun setTestResult(text: String) {
        binding.testResult.text = "${getString(R.string.test_result_prefix)} $text"
    }

    private fun safeState(display: android.view.Display): Int = try {
        display.state
    } catch (_: Exception) {
        -1
    }
}
