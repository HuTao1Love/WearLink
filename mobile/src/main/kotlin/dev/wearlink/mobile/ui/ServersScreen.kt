package dev.wearlink.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wearlink.core.WearLinkCore
import dev.wearlink.core.data.Format
import dev.wearlink.core.data.PingResult
import dev.wearlink.core.data.ServerPing
import dev.wearlink.mobile.sync.WatchSync
import dev.wearlink.shared.model.Server
import dev.wearlink.shared.model.Subscription
import kotlinx.coroutines.launch

@Composable
fun ServersScreen(modifier: Modifier, messages: Messages) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by WearLinkCore.store.data.collectAsState()
    val pings by ServerPing.results.collectAsState()
    val store = WearLinkCore.store

    fun ping(servers: List<Server>) = scope.launch { ServerPing.ping(context, servers) }

    fun sendToWatch(text: String) = scope.launch {
        runCatching { WatchSync.send(context, text) }
            .onSuccess { messages.show("Отправлено на часы") }
            .onFailure { messages.show(it.message ?: "Не удалось отправить") }
    }

    if (data.servers.isEmpty()) {
        Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Серверов пока нет. Добавьте ссылку или подписку на вкладке «Добавить».")
        }
        return
    }

    LazyColumn(modifier.fillMaxSize()) {
        item(key = "ping") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("TCP-пинг до серверов", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = { ping(data.servers) }) { Text("Проверить пинг") }
            }
        }
        data.subscriptions.forEach { subscription ->
            item(key = "sub-${subscription.id}") {
                SubscriptionHeader(
                    subscription = subscription,
                    onRefresh = {
                        scope.launch {
                            runCatching { WearLinkCore.importer.refresh(subscription.id) }
                                .onSuccess { messages.show("Обновлено: ${it.added} серверов") }
                                .onFailure { messages.show(it.message ?: "Ошибка обновления") }
                        }
                    },
                    onSend = { sendToWatch(subscription.url) },
                    onPing = { ping(data.serversOf(subscription.id)) },
                    onDelete = { scope.launch { store.deleteSubscription(subscription.id) } },
                )
            }
            data.autoServer(subscription.id)?.let { auto ->
                item(key = auto.id) {
                    ServerRow(
                        server = auto,
                        selected = auto.id == data.selectedServerId,
                        ping = null,
                        onSelect = { scope.launch { store.select(auto.id) } },
                        onSend = null,
                        onDelete = null,
                    )
                }
            }
            items(data.serversOf(subscription.id), key = { it.id }) { server ->
                ServerRow(
                    server = server,
                    selected = server.id == data.selectedServerId,
                    ping = pings[server.id],
                    onSelect = { scope.launch { store.select(server.id) } },
                    onSend = { sendToWatch(server.link) },
                    onDelete = null,
                )
            }
        }
        val single = data.serversOf(null)
        if (single.isNotEmpty()) {
            item(key = "single") { GroupTitle("Отдельные серверы", null) }
            items(single, key = { it.id }) { server ->
                ServerRow(
                    server = server,
                    selected = server.id == data.selectedServerId,
                    ping = pings[server.id],
                    onSelect = { scope.launch { store.select(server.id) } },
                    onSend = { sendToWatch(server.link) },
                    onDelete = { scope.launch { store.deleteServer(server.id) } },
                )
            }
        }
    }
}

@Composable
private fun GroupTitle(title: String, subtitle: String?, actions: @Composable () -> Unit = {}) {
    Column {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            actions()
        }
    }
}

@Composable
private fun SubscriptionHeader(
    subscription: Subscription,
    onRefresh: () -> Unit,
    onSend: () -> Unit,
    onPing: () -> Unit,
    onDelete: () -> Unit,
) {
    val details = listOfNotNull(Format.traffic(subscription), Format.expire(subscription), subscription.lastError)
        .joinToString(" · ")
        .ifEmpty { null }
    GroupTitle(subscription.name, details) {
        OverflowMenu(
            listOf(
                "Обновить" to onRefresh,
                "Проверить пинг" to onPing,
                "Отправить на часы" to onSend,
                "Удалить подписку" to onDelete,
            ),
        )
    }
}

@Composable
private fun ServerRow(
    server: Server,
    selected: Boolean,
    ping: PingResult?,
    onSelect: () -> Unit,
    onSend: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = server.isUsable, onClick = onSelect)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = server.isUsable)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                server.error ?: server.protocolLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (server.isUsable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        ping?.let { PingLabel(it) }
        val menu = listOfNotNull(onSend?.let { "Отправить на часы" to it }, onDelete?.let { "Удалить" to it })
        if (menu.isNotEmpty()) OverflowMenu(menu) else Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun PingLabel(result: PingResult) {
    val (text, color) = when (result) {
        PingResult.Testing -> "…" to MaterialTheme.colorScheme.onSurfaceVariant
        PingResult.Failed -> "нет" to MaterialTheme.colorScheme.error
        PingResult.Udp -> "UDP" to MaterialTheme.colorScheme.onSurfaceVariant
        is PingResult.Ok -> "${result.ms} мс" to when {
            result.ms < 150 -> Color(0xFF2E7D32)
            result.ms < 400 -> Color(0xFFF9A825)
            else -> MaterialTheme.colorScheme.error
        }
    }
    Text(text, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun OverflowMenu(entries: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Меню") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    action()
                })
            }
        }
    }
}
