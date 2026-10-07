package dev.wearlink.core.data

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

object InstalledApps {

    /** Apps that can use the network, minus ourselves, sorted by label. */
    suspend fun load(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true }
            .mapNotNull { info ->
                val app = info.applicationInfo ?: return@mapNotNull null
                val flags = app.flags
                val system = flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                    flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0 &&
                    pm.getLaunchIntentForPackage(info.packageName) == null
                AppEntry(info.packageName, app.loadLabel(pm).toString(), system)
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
