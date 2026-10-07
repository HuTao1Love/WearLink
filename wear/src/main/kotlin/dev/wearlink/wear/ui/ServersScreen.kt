package dev.wearlink.wear.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.wearlink.core.WearLinkCore
import dev.wearlink.core.data.Format
import dev.wearlink.shared.model.Server
import kotlinx.coroutines.launch

/** Tap a server to make it the default; long-press to delete. */
@Composable
fun ServersScreen(onDelete: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by WearLinkCore.store.data.collectAsState()
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Серверы") } }
            if (data.servers.isEmpty()) {
                item {
                    Text(
                        "Пусто. Отправьте ссылку или подписку с телефона.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            data.subscriptions.forEach { subscription ->
                item(key = "sub-${subscription.id}") {
                    val details = listOfNotNull(Format.traffic(subscription), Format.expire(subscription), subscription.lastError)
                    Button(
                        onClick = {
                            scope.launch {
                                val message = runCatching { WearLinkCore.importer.refresh(subscription.id) }
                                    .fold({ "Обновлено: ${it.added}" }, { it.message ?: "Ошибка" })
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onLongClick = { onDelete("sub:${subscription.id}") },
                        onLongClickLabel = "Удалить подписку",
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(),
                        icon = { Icon(Icons.Default.Refresh, contentDescription = "Обновить") },
                        label = { Text(subscription.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = details.takeIf { it.isNotEmpty() }?.let { { Text(it.joinToString(" · "), maxLines = 2) } },
                    )
                }
                items(data.serversOf(subscription.id), key = { it.id }) { server ->
                    ServerButton(server, server.id == data.selectedServerId, onDelete = null)
                }
            }
            val single = data.serversOf(null)
            if (single.isNotEmpty()) {
                item(key = "single") { ListHeader { Text("Отдельные") } }
                items(single, key = { it.id }) { server ->
                    ServerButton(server, server.id == data.selectedServerId, onDelete = { onDelete(server.id) })
                }
            }
        }
    }
}

@Composable
private fun ServerButton(server: Server, selected: Boolean, onDelete: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    Button(
        onClick = { scope.launch { WearLinkCore.store.select(server.id) } },
        onLongClick = onDelete,
        onLongClickLabel = onDelete?.let { "Удалить" },
        enabled = server.isUsable,
        modifier = Modifier.fillMaxWidth(),
        colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        icon = if (selected) {
            { Icon(Icons.Default.CheckCircle, contentDescription = "Выбран") }
        } else {
            null
        },
        label = { Text(server.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(server.error ?: server.protocolLabel, maxLines = 1) },
    )
}
