package dev.wearlink.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wearlink.core.WearLinkCore
import dev.wearlink.core.data.Format
import dev.wearlink.mobile.sync.WatchSync
import dev.wearlink.shared.model.Server
import dev.wearlink.shared.model.Subscription
import kotlinx.coroutines.launch

@Composable
fun ServersScreen(modifier: Modifier, messages: Messages) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by WearLinkCore.store.data.collectAsState()
    val store = WearLinkCore.store

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
                    onDelete = { scope.launch { store.deleteSubscription(subscription.id) } },
                )
            }
            items(data.serversOf(subscription.id), key = { it.id }) { server ->
                ServerRow(
                    server = server,
                    selected = server.id == data.selectedServerId,
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
private fun SubscriptionHeader(subscription: Subscription, onRefresh: () -> Unit, onSend: () -> Unit, onDelete: () -> Unit) {
    val details = listOfNotNull(Format.traffic(subscription), Format.expire(subscription), subscription.lastError)
        .joinToString(" · ")
        .ifEmpty { null }
    GroupTitle(subscription.name, details) {
        OverflowMenu(
            listOf(
                "Обновить" to onRefresh,
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
    onSelect: () -> Unit,
    onSend: () -> Unit,
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
        OverflowMenu(listOfNotNull("Отправить на часы" to onSend, onDelete?.let { "Удалить" to it }))
    }
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
