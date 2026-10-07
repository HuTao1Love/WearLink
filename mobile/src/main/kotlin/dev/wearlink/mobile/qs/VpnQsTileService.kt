package dev.wearlink.mobile.qs

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.wearlink.core.R
import dev.wearlink.core.WearLinkCore
import dev.wearlink.core.vpn.VpnController
import dev.wearlink.core.vpn.VpnState

/** The Happ-style on/off button in the notification shade. */
class VpnQsTileService : TileService() {

    override fun onStartListening() = render()

    override fun onClick() {
        WearLinkCore.init(application)
        if (VpnController.state.value.isActive) {
            VpnController.stop()
            render()
            return
        }
        val canStartDirectly = WearLinkCore.store.current.selectedServer != null && VpnController.isPrepared(this)
        if (canStartDirectly && runCatching { VpnController.start(this) }.isSuccess) {
            render()
        } else {
            // Consent dialog or "add a server first": needs an activity.
            openToggleActivity()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openToggleActivity() {
        val intent = VpnController.toggleIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        WearLinkCore.init(application)
        val state = VpnController.state.value
        val server = WearLinkCore.store.current.selectedServer?.name
        tile.state = if (state.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "WearLink"
        tile.subtitle = when (state) {
            is VpnState.Connecting -> "Подключение…"
            is VpnState.Connected -> state.serverName
            is VpnState.Error -> "Ошибка"
            VpnState.Stopped -> server ?: "Нет сервера"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_wearlink)
        tile.updateTile()
    }
}
