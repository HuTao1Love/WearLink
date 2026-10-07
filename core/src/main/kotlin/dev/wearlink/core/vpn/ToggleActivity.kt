package dev.wearlink.core.vpn

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.wearlink.core.WearLinkCore

/**
 * Invisible trampoline used by tiles and widgets: the VPN service may only be started from the
 * foreground, and the first start needs the system consent dialog.
 */
class ToggleActivity : ComponentActivity() {

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            VpnController.start(this)
        } else {
            VpnController.setState(VpnState.Error("Нет разрешения на VPN"))
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        WearLinkCore.init(application)

        val wantsStop = intent.action == ACTION_STOP ||
            (intent.action != ACTION_START && VpnController.state.value.isActive)
        if (wantsStop) {
            VpnController.stop()
            finish()
            return
        }
        if (WearLinkCore.store.current.selectedServer == null) {
            VpnController.setState(VpnState.Error("Сначала добавьте сервер"))
            packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
            finish()
            return
        }
        if (VpnController.mode(this) == TunnelMode.PROXY) {
            // No consent dialog for the system proxy; only the adb-granted permission.
            val missing = VpnController.missingPermission(this)
            if (missing != null) VpnController.setState(VpnState.Error(missing)) else VpnController.start(this)
            finish()
            return
        }
        val prepare = try {
            VpnController.consentIntent(this)
        } catch (e: UnsupportedOperationException) {
            VpnController.setState(VpnState.Error(VpnController.UNSUPPORTED_MESSAGE))
            finish()
            return
        }
        if (prepare == null) {
            VpnController.start(this)
            finish()
        } else {
            consent.launch(prepare)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "dev.wearlink.TOGGLE"
        const val ACTION_START = "dev.wearlink.START"
        const val ACTION_STOP = "dev.wearlink.STOP"
    }
}
