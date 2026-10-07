package dev.wearlink.wear.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import dev.wearlink.core.WearLinkCore
import dev.wearlink.core.data.GeoLists
import dev.wearlink.core.vpn.VpnController
import dev.wearlink.shared.model.ListRouting
import kotlinx.coroutines.launch

/** Selective routing by zkeen categories, same as on the phone. */
@Composable
fun ListsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by WearLinkCore.store.data.collectAsState()
    val catalog by GeoLists.catalog.collectAsState()
    var downloading by remember { mutableStateOf(false) }
    val lists = data.lists
    val listState = rememberScalingLazyListState()

    fun save(updated: ListRouting) = scope.launch { WearLinkCore.store.setLists(updated) }

    fun download() = scope.launch {
        downloading = true
        val message = runCatching { GeoLists.update(context) }
            .onSuccess { VpnController.reloadIfRunning() }
            .fold({ "Списки обновлены" }, { it.message ?: "Ошибка" })
        downloading = false
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Маршрутизация") } }
            item {
                SwitchButton(
                    checked = lists.enabled,
                    onCheckedChange = {
                        save(lists.copy(enabled = it))
                        if (it && catalog == null) download()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Только по спискам") },
                    secondaryLabel = { Text(if (lists.enabled) "Остальное напрямую" else "Сейчас: весь трафик") },
                )
            }
            item {
                Button(
                    onClick = { if (!downloading) download() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(),
                    icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    label = { Text(if (catalog == null) "Скачать списки" else "Обновить списки") },
                    secondaryLabel = { Text(if (downloading) "Загрузка…" else "zkeen") },
                )
            }
            val current = catalog
            if (current == null) {
                item { Text("Списки ещё не скачаны", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) }
            } else {
                item { ListHeader { Text("Домены") } }
                items(current.sites, key = { "s-" + it.name }) { category ->
                    CheckboxButton(
                        checked = category.name in lists.sites,
                        onCheckedChange = { save(lists.copy(sites = if (it) lists.sites + category.name else lists.sites - category.name)) },
                        enabled = lists.enabled,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(GeoLists.label(category.name), maxLines = 2) },
                    )
                }
                item { ListHeader { Text("IP-адреса") } }
                items(current.ips, key = { "i-" + it.name }) { category ->
                    CheckboxButton(
                        checked = category.name in lists.ips,
                        onCheckedChange = { save(lists.copy(ips = if (it) lists.ips + category.name else lists.ips - category.name)) },
                        enabled = lists.enabled,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(GeoLists.label(category.name), maxLines = 2) },
                    )
                }
            }
        }
    }
}
