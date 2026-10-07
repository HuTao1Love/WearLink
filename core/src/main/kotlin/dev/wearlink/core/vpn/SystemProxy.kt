package dev.wearlink.core.vpn

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * The device-wide HTTP proxy (Settings.Global.HTTP_PROXY), pointed at sing-box's local
 * inbound on watches without a VPN service. Writing it needs WRITE_SECURE_SETTINGS, which a
 * side-loaded app can only get once over adb:
 *   adb shell pm grant dev.wearlink android.permission.WRITE_SECURE_SETTINGS
 */
object SystemProxy {

    const val PORT = 10808
    private const val HOST = "127.0.0.1"

    /** ":0" is what ConnectivityService treats as "no global proxy"; an empty value is not always applied. */
    private const val CLEARED = ":0"

    val grantCommand = "adb shell pm grant %s ${Manifest.permission.WRITE_SECURE_SETTINGS}"

    fun canWrite(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun enable(context: Context) {
        Settings.Global.putString(context.contentResolver, Settings.Global.HTTP_PROXY, "$HOST:$PORT")
    }

    fun disable(context: Context) {
        if (isOurs(context)) Settings.Global.putString(context.contentResolver, Settings.Global.HTTP_PROXY, CLEARED)
    }

    fun isOurs(context: Context) =
        Settings.Global.getString(context.contentResolver, Settings.Global.HTTP_PROXY) == "$HOST:$PORT"
}
