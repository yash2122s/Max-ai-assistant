package com.example.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.example.network.agent.WindowsToolExecutor

@RequiresApi(Build.VERSION_CODES.N)
class DesktopLinkTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val context = applicationContext
        val isEnabled = WindowsToolExecutor.isDesktopConnectionEnabled(context)
        val newState = !isEnabled
        WindowsToolExecutor.setDesktopConnectionEnabled(context, newState)
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isEnabled = WindowsToolExecutor.isDesktopConnectionEnabled(applicationContext)
        val isConnected = WindowsToolExecutor.isAgentAvailable()

        tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "MAX Desktop Link"
        tile.subtitle = if (!isEnabled) "Disabled" else if (isConnected) "Connected" else "Searching..."
        tile.updateTile()
    }
}
