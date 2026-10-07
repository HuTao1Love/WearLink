package dev.wearlink.mobile.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.wearlink.core.WearLinkCore
import dev.wearlink.core.data.Format
import dev.wearlink.core.vpn.IpCheck
import dev.wearlink.core.vpn.VpnController
import dev.wearlink.core.vpn.VpnState
import dev.wearlink.mobile.sync.WatchStatus
import dev.wearlink.mobile.sync.WatchSync
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(modifier: Modifier, messages: Messages, onOpenServers: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by VpnController.state.collectAsState()
    val data by WearLinkCore.store.data.collectAsState()
    val watch by WatchSync.status.collectAsState()
    var ip by remember { mutableStateOf<String?>(null) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) VpnController.start(context) else messages.show("Нет разрешения на VPN")
    }

    fun toggle() {
        when {
            state.isActive -> VpnController.stop()
            data.selectedServer == null -> messages.show("Сначала добавьте сервер")
            else -> try {
                VpnController.consentIntent(context)?.let(consent::launch) ?: VpnController.start(context)
            } catch (e: UnsupportedOperationException) {
                messages.show(VpnController.UNSUPPORTED_MESSAGE)
            }
        }
    }

    val color by animateColorAsState(
        when (state) {
            is VpnState.Connected -> Color(0xFF2E7D32)
            is VpnState.Connecting -> Color(0xFFF9A825)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "toggle",
    )

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            onClick = ::toggle,
            shape = CircleShape,
            color = color,
            modifier = Modifier.size(180.dp),
        ) {
            Icon(
                Icons.Default.PowerSettingsNew,
                contentDescription = if (state.isActive) "Отключить" else "Подключить",
                modifier = Modifier.padding(48.dp),
                tint = if (state.isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(Format.state(state), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenServers),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Сервер по умолчанию", style = MaterialTheme.typography.labelMedium)
                Text(
                    data.selectedServer?.name ?: "Не выбран — нажмите, чтобы выбрать",
                    style = MaterialTheme.typography.titleMedium,
                )
                data.selectedServer?.let { Text(it.protocolLabel, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.height(16.dp))

        FilledTonalButton(onClick = {
            ip = "Проверка…"
            scope.launch { ip = IpCheck.check() }
        }) { Text("Мой IP") }
        ip?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }

        Spacer(Modifier.height(24.dp))
        AssistChip(
            onClick = { scope.launch { WatchSync.refreshStatus(context) } },
            leadingIcon = { Icon(Icons.Default.Watch, contentDescription = null) },
            label = {
                Text(
                    when (val w = watch) {
                        WatchStatus.Checking -> "Поиск часов…"
                        WatchStatus.NoWearOs -> "Wear OS не найден на телефоне"
                        WatchStatus.NotConnected -> "Часы не подключены"
                        is WatchStatus.NoApp -> "${w.watchName}: приложение не установлено"
                        is WatchStatus.Ready -> "${w.watchName}: готово"
                    },
                )
            },
        )
        Spacer(Modifier.height(16.dp))
        UpdateCard(messages)
    }
}
