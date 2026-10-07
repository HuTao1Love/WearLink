package dev.wearlink.mobile

import android.app.Application
import android.content.ComponentName
import android.service.quicksettings.TileService
import dev.wearlink.core.WearLinkCore
import dev.wearlink.core.vpn.VpnController
import dev.wearlink.mobile.qs.VpnQsTileService
import dev.wearlink.mobile.sync.WatchSync
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MobileApp : Application() {

    override fun onCreate() {
        super.onCreate()
        WearLinkCore.init(this, BuildConfig.UPDATE_BASE_URL)
        WatchSync.init(this)

        // Keep the Quick Settings tile in sync with the VPN state and the default server.
        WearLinkCore.scope.launch {
            combine(VpnController.state, WearLinkCore.store.data) { state, data -> state to data.selectedServer?.name }
                .distinctUntilChanged()
                .collect {
                    TileService.requestListeningState(this@MobileApp, ComponentName(this@MobileApp, VpnQsTileService::class.java))
                }
        }
    }
}
