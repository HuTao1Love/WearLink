package dev.wearlink.wear

import android.app.Application
import dev.wearlink.core.WearLinkCore
import dev.wearlink.core.vpn.VpnController
import dev.wearlink.wear.tile.VpnTileService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class WearApp : Application() {

    override fun onCreate() {
        super.onCreate()
        WearLinkCore.init(this, BuildConfig.UPDATE_BASE_URL)

        // Refresh the tile whenever the VPN state or the default server changes.
        WearLinkCore.scope.launch {
            combine(VpnController.state, WearLinkCore.store.data) { state, data -> state to data.selectedServer?.name }
                .distinctUntilChanged()
                .collect { VpnTileService.requestUpdate(this@WearApp) }
        }
    }
}
