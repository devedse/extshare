package org.arnor.extshare

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build

data class ProjectionGrant(val resultCode: Int, val dataIntent: Intent)

/**
 * Holds the screen-capture consent result in memory until MirrorService consumes it.
 *
 * The result Intent carries an IBinder token that does not survive persisting via
 * Intent.toUri(), and on Android 14+ each consent can only start a single capture
 * session anyway, so every start needs a fresh consent.
 */
object ProjectionStore {
    @Volatile
    private var grant: ProjectionGrant? = null

    fun saveGrant(resultCode: Int, intent: Intent) {
        grant = ProjectionGrant(resultCode, intent)
    }

    fun takeGrant(): ProjectionGrant? = grant.also { grant = null }

    fun hasGrant(): Boolean = grant != null

    /**
     * Consent intent. [wholeScreen] forces capturing the default display; otherwise Android's picker
     * lets the user share a single app or the entire screen.
     */
    fun captureIntent(manager: MediaProjectionManager, wholeScreen: Boolean): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val config = if (wholeScreen) {
                MediaProjectionConfig.createConfigForDefaultDisplay()
            } else {
                MediaProjectionConfig.createConfigForUserChoice()
            }
            manager.createScreenCaptureIntent(config)
        } else {
            manager.createScreenCaptureIntent()
        }
}
