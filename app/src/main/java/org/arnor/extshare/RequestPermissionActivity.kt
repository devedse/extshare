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
            if (result.resultCode == RESULT_OK && result.data != null) {
                MirrorService.start(this, result.resultCode, result.data!!)
                Toast.makeText(this, "Mirroring enabled.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestProjection()
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
