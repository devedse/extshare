package org.arnor.extshare

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService

class RequestPermissionActivity : ComponentActivity() {

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val rearDisplay = intent.getBooleanExtra(EXTRA_REAR_DISPLAY, false)
            if (result.resultCode == RESULT_OK && result.data != null) {
                DebugLog.log(TAG, "Screen-capture consent granted (rearDisplay=$rearDisplay)")
                MirrorService.start(this, result.resultCode, result.data!!, externalSurface = rearDisplay)
                Toast.makeText(this, "Mirroring enabled.", Toast.LENGTH_SHORT).show()
            } else {
                DebugLog.log(TAG, "Screen-capture consent denied")
                // Let MainActivity close the cover session it opened for this request.
                if (rearDisplay) sendBroadcast(Intent(MainActivity.ACTION_STOP_MIRRORING).setPackage(packageName))
                Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestProjection()
    }

    companion object {
        private const val TAG = "RequestPermission"

        /** Set when MainActivity already holds the cover session and capture should render into it. */
        const val EXTRA_REAR_DISPLAY = "rear_display"
    }

    private fun requestProjection() {
        val manager: MediaProjectionManager? = getSystemService()
        if (manager == null) {
            Toast.makeText(this, "MediaProjection unavailable on this device.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val intent: Intent = ProjectionStore.captureIntent(manager, TestSettings.get(this, TestSettings.WHOLE_SCREEN))
        launcher.launch(intent)
    }
}
