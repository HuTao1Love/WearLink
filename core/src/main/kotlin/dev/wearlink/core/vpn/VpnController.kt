package dev.wearlink.core.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** VPN: VpnService TUN (phones). PROXY: system HTTP proxy, for watch firmware without a VPN service. */
enum class TunnelMode { VPN, PROXY }

sealed interface VpnState {
    data object Stopped : VpnState
    data class Connecting(val serverName: String) : VpnState
    data class Connected(val serverName: String) : VpnState
    data class Error(val message: String) : VpnState

    val isActive: Boolean get() = this is Connecting || this is Connected
}

object VpnController {

    private val _state = MutableStateFlow<VpnState>(VpnState.Stopped)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    internal fun setState(state: VpnState) {
        _state.value = state
    }

    /**
     * False on builds without the system VPN service: stock Wear OS does not start
     * VpnManagerService on watches, and VpnService.prepare() then throws.
     */
    fun isSupported(context: Context) = runCatching { VpnService.prepare(context) }.isSuccess

    /** True when the system VPN consent has been given (or granted via appops). */
    fun isPrepared(context: Context) = runCatching { VpnService.prepare(context) == null }.getOrDefault(false)

    /** Consent intent to launch, null when already allowed. Throws [UnsupportedOperationException] without VPN support. */
    fun consentIntent(context: Context): Intent? = try {
        VpnService.prepare(context)
    } catch (e: RuntimeException) {
        throw UnsupportedOperationException(UNSUPPORTED_MESSAGE, e)
    }

    const val UNSUPPORTED_MESSAGE = "Прошивка не поддерживает VPN"

    fun mode(context: Context): TunnelMode = if (isSupported(context)) TunnelMode.VPN else TunnelMode.PROXY

    /** Null when the tunnel can start; otherwise what the user has to do first. */
    fun missingPermission(context: Context): String? =
        if (mode(context) == TunnelMode.PROXY && !SystemProxy.canWrite(context)) {
            "Нужно разрешение. Один раз выполните на компьютере:\n" + SystemProxy.grantCommand.format(context.packageName)
        } else {
            null
        }

    /** Starts the tunnel. The caller must be in the foreground and [isPrepared] must be true. */
    fun start(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, BoxVpnService::class.java).setAction(BoxVpnService.ACTION_START),
        )
    }

    fun stop() {
        val service = BoxVpnService.instance
        if (service != null) service.stopVpn() else _state.value = VpnState.Stopped
    }

    /** Rebuilds the config (e.g. after the zkeen lists were refreshed) if the tunnel is up. */
    fun reloadIfRunning() {
        BoxVpnService.instance?.serviceReload()
    }

    fun toggle(context: Context) {
        if (state.value.isActive) stop() else start(context)
    }

    /** Intent that toggles the VPN, asking for consent first when needed. Safe to fire from tiles. */
    fun toggleIntent(context: Context): Intent =
        Intent(context, ToggleActivity::class.java)
            .setAction(ToggleActivity.ACTION_TOGGLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
}
