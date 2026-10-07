package dev.wearlink.core.data

import dev.wearlink.core.vpn.VpnState
import dev.wearlink.shared.model.Subscription
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Human-readable strings shared by the phone and watch UIs. */
object Format {

    fun bytes(value: Long): String {
        if (value < 1024) return "$value Б"
        val units = listOf("КБ", "МБ", "ГБ", "ТБ")
        var size = value.toDouble() / 1024
        var unit = 0
        while (size >= 1024 && unit < units.lastIndex) {
            size /= 1024
            unit++
        }
        return String.format(Locale.US, if (size >= 100) "%.0f %s" else "%.1f %s", size, units[unit])
    }

    fun traffic(subscription: Subscription): String? {
        val used = subscription.upload + subscription.download
        return when {
            subscription.total > 0 -> "${bytes(used)} из ${bytes(subscription.total)}"
            used > 0 -> bytes(used)
            else -> null
        }
    }

    fun expire(subscription: Subscription): String? {
        if (subscription.expire <= 0) return null
        val millis = subscription.expire * 1000
        if (millis < System.currentTimeMillis()) return "истекла"
        return "до " + SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(millis))
    }

    fun state(state: VpnState): String = when (state) {
        VpnState.Stopped -> "Отключено"
        is VpnState.Connecting -> "Подключение…"
        is VpnState.Connected -> "Подключено"
        is VpnState.Error -> state.message
    }
}
