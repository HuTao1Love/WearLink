package dev.wearlink.core.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Wakes the app after a reboot. Starting the process runs WearLinkCore.init(), which clears a
 * system proxy left pointing at sing-box, so the watch keeps its internet.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (BoxVpnService.instance == null) SystemProxy.disable(context)
    }
}
