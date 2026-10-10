package org.arnor.extshare

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build

/**
 * Screen-capture consent helpers.
 *
 * The consent result Intent carries an IBinder token, so it is handed straight to MirrorService
 * in its start Intent (never persisted), and on Android 14+ each consent starts only one session.
 */
object ProjectionStore {
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
