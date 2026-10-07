package dev.wearlink.wear.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.wearlink.core.R
import dev.wearlink.core.WearLinkCore
import dev.wearlink.core.data.Format
import dev.wearlink.core.update.UpdateState
import dev.wearlink.core.update.Updater
import dev.wearlink.core.vpn.IpCheck
import dev.wearlink.core.vpn.TunnelMode
import dev.wearlink.core.vpn.VpnController
import dev.wearlink.core.vpn.VpnState
import dev.wearlink.shared.model.RoutingMode
import kotlinx.coroutines.launch

@Composable
fun MainScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by VpnController.state.collectAsState()
    val data by WearLinkCore.store.data.collectAsState()
    var ip by remember { mutableStateOf<String?>(null) }
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val proxyMode = remember { VpnController.mode(context) == TunnelMode.PROXY }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) VpnController.start(context)
    }

    fun toggle() {
        when {
            state.isActive -> VpnController.stop()
            data.selectedServer == null -> onNavigate(Routes.ADD)
            VpnController.mode(context) == TunnelMode.PROXY -> {
                val missing = VpnController.missingPermission(context)
                if (missing != null) ip = missing else VpnController.start(context)
            }
            else -> VpnController.consentIntent(context)?.let(consent::launch) ?: VpnController.start(context)
        }
    }

    val color by animateColorAsState(
        when (state) {
            is VpnState.Connected -> Color(0xFF2E7D32)
            is VpnState.Connecting -> Color(0xFFF9A825)
            else -> Color(0xFF3C3C3C)
        },
        label = "toggle",
    )

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable(onClick = ::toggle)
                            .semantics { contentDescription = if (state.isActive) "Отключить VPN" else "Включить VPN" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_wearlink), contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    Text(
                        Format.state(state),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (proxyMode) {
                        Text(
                            "Режим прокси",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = { onNavigate(Routes.SERVERS) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("Сервер") },
                    secondaryLabel = { Text(data.selectedServer?.name ?: "Не выбран", maxLines = 1) },
                )
            }
            item {
                Button(
                    onClick = { onNavigate(Routes.APPS) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Приложения") },
                    secondaryLabel = {
                        Text(
                            when (data.routing.mode) {
                                RoutingMode.ALL -> "Все через VPN"
                                RoutingMode.ONLY_SELECTED -> "Только выбранные: ${data.routing.packages.size}"
                                RoutingMode.ALL_EXCEPT -> "Кроме: ${data.routing.packages.size}"
                            },
                        )
                    },
                )
            }
            item {
                Button(
                    onClick = { onNavigate(Routes.LISTS) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Icon(Icons.Default.Place, contentDescription = null) },
                    label = { Text("Маршрутизация") },
                    secondaryLabel = {
                        Text(
                            if (data.lists.enabled) "По спискам: ${data.lists.sites.size + data.lists.ips.size}" else "Весь трафик",
                        )
                    },
                )
            }
            item {
                Button(
                    onClick = { onNavigate(Routes.ADD) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    label = { Text("Добавить") },
                )
            }
            item {
                Button(
                    onClick = {
                        ip = "Проверка…"
                        scope.launch { ip = IpCheck.check() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(),
                    label = { Text("Мой IP") },
                    secondaryLabel = ip?.let { { Text(it, maxLines = 6) } },
                )
            }
            item { UpdateButton() }
        }
    }
}

/** Checks GitHub Releases; a second tap downloads and installs the watch APK. */
@Composable
private fun UpdateButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsState()
    // The watch cuts network access when the screen goes to ambient, which kills the download.
    val view = LocalView.current
    DisposableEffect(state is UpdateState.Downloading) {
        view.keepScreenOn = state is UpdateState.Downloading
        onDispose { view.keepScreenOn = false }
    }
    Button(
        onClick = {
            scope.launch {
                val current = state
                if (current is UpdateState.Available) {
                    Updater.install(context, current.manifest, isWatch = true)
                } else if (current !is UpdateState.Downloading && current != UpdateState.Installing) {
                    Updater.check(context)
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(),
        icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
        label = { Text(if (state is UpdateState.Available) "Установить" else "Обновление") },
        secondaryLabel = {
            Text(
                when (val s = state) {
                    UpdateState.Idle -> "Версия ${Updater.currentVersionName(context)}"
                    UpdateState.Checking -> "Проверка…"
                    is UpdateState.UpToDate -> "Последняя: ${s.versionName}"
                    is UpdateState.Available -> "Версия ${s.manifest.versionName}"
                    is UpdateState.Downloading -> "Загрузка ${(s.progress * 100).toInt()}%"
                    UpdateState.Installing -> "Установка…"
                    is UpdateState.Error -> s.message
                },
                maxLines = 2,
            )
        },
    )
}
