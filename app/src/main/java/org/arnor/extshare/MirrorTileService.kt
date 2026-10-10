package org.arnor.extshare

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class MirrorTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        val hasGrant = ProjectionStore.hasGrant()

        if (MirrorService.isRunning.get()) {
            MirrorService.stop(this)
        } else {
            if (hasGrant) {
                MirrorService.start(this)
            } else {
                val intent = Intent(this, RequestPermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivityAndCollapse(intent)
                return
            }
        }
        updateTileState()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        val running = MirrorService.isRunning.get()
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
