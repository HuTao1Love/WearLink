package dev.wearlink.mobile.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import dev.wearlink.mobile.sync.WatchSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class Tab(val title: String, val icon: ImageVector) {
    Home("VPN", Icons.Default.PowerSettingsNew),
    Servers("Серверы", Icons.Default.Dns),
    Apps("Приложения", Icons.Default.Apps),
    Add("Добавить", Icons.Default.Add),
}

@Composable
fun WearLinkTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Lets nested screens show messages without threading the host state everywhere. */
class Messages(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    fun show(message: String) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(message)
        }
    }
}

@Composable
fun MainScreen(incoming: MutableStateFlow<String?>) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val messages = remember { Messages(snackbar, scope) }
    val context = LocalContext.current
    val shared by incoming.collectAsState()

    LaunchedEffect(shared) {
        if (shared != null) tab = Tab.Add
    }
    LaunchedEffect(Unit) {
        WatchSync.refreshStatus(context)
        WatchSync.results.collect { result ->
            messages.show(
                if (result.error != null) {
                    "Часы: ${result.error}"
                } else {
                    "На часы добавлено серверов: ${result.added}" + (result.title?.let { " ($it)" } ?: "")
                },
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.title) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.Home -> HomeScreen(modifier, messages, onOpenServers = { tab = Tab.Servers })
            Tab.Servers -> ServersScreen(modifier, messages)
            Tab.Apps -> AppsScreen(modifier)
            Tab.Add -> AddScreen(
                modifier = modifier,
                messages = messages,
                initialText = shared,
                onConsumed = { incoming.value = null },
                onAdded = { tab = Tab.Servers },
            )
        }
    }
}
