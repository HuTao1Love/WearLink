package dev.wearlink.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.wearlink.core.WearLinkCore
import kotlinx.coroutines.launch

/** Confirmation for deleting a server, or a whole subscription when [id] starts with "sub:". */
@Composable
fun DeleteScreen(id: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val data by WearLinkCore.store.data.collectAsState()
    val listState = rememberScalingLazyListState()
    val subscriptionId = id.removePrefix("sub:").takeIf { id.startsWith("sub:") }
    val name = if (subscriptionId != null) {
        data.subscriptions.firstOrNull { it.id == subscriptionId }?.name
    } else {
        data.servers.firstOrNull { it.id == id }?.name
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(if (subscriptionId != null) "Удалить подписку?" else "Удалить сервер?") } }
            item { Text(name ?: "", textAlign = TextAlign.Center) }
            item {
                Button(
                    onClick = {
                        scope.launch {
                            if (subscriptionId != null) {
                                WearLinkCore.store.deleteSubscription(subscriptionId)
                            } else {
                                WearLinkCore.store.deleteServer(id)
                            }
                            onDone()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    label = { Text("Удалить") },
                )
            }
            item {
                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text("Отмена") },
                )
            }
        }
    }
}
