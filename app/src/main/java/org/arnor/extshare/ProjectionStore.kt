package org.arnor.extshare

import android.content.Intent

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
}
