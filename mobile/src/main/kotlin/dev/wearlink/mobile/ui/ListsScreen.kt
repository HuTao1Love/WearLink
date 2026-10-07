package dev.wearlink.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import dev.wearlink.core.WearLinkCore
import dev.wearlink.core.data.GeoCategory
import dev.wearlink.core.data.GeoLists
import dev.wearlink.core.vpn.VpnController
import dev.wearlink.shared.model.ListRouting
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Selective routing: only the chosen zkeen categories go through the proxy. */
@Composable
fun ListsScreen(modifier: Modifier, messages: Messages) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by WearLinkCore.store.data.collectAsState()
    val catalog by GeoLists.catalog.collectAsState()
    var downloading by remember { mutableStateOf(false) }
    val lists = data.lists

    fun save(updated: ListRouting) = scope.launch { WearLinkCore.store.setLists(updated) }

    fun download() = scope.launch {
        downloading = true
        runCatching { GeoLists.update(context) }
            .onSuccess {
                messages.show("Списки обновлены")
                VpnController.reloadIfRunning()
            }
            .onFailure { messages.show(it.message ?: "Ошибка загрузки списков") }
        downloading = false
    }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Только по спискам", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (lists.enabled) {
                                "Через VPN идут только сайты и сервисы из отмеченных категорий, остальное — напрямую."
                            } else {
                                "Сейчас через VPN идёт весь трафик. Включите, чтобы пускать через VPN только нужное."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = lists.enabled, onCheckedChange = {
                        save(lists.copy(enabled = it))
                        if (it && catalog == null) download()
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        catalog?.let { "Списки zkeen от " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.updatedAt)) }
                            ?: "Списки ещё не скачаны",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (downloading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        OutlinedButton(onClick = { download() }) { Text(if (catalog == null) "Скачать" else "Обновить") }
                    }
                }
            }
        }
        val current = catalog ?: return@LazyColumn
        item { Section("Домены", "zkeen-domains", onAll = { save(lists.copy(sites = current.sites.map { it.name }.toSet())) }, onNone = { save(lists.copy(sites = emptySet())) }) }
        items(current.sites, key = { "s-" + it.name }) { category ->
            CategoryRow(category, category.name in lists.sites, lists.enabled) { checked ->
                save(lists.copy(sites = if (checked) lists.sites + category.name else lists.sites - category.name))
            }
        }
        item { Section("IP-адреса", "zkeen-ip", onAll = { save(lists.copy(ips = current.ips.map { it.name }.toSet())) }, onNone = { save(lists.copy(ips = emptySet())) }) }
        items(current.ips, key = { "i-" + it.name }) { category ->
            CategoryRow(category, category.name in lists.ips, lists.enabled) { checked ->
                save(lists.copy(ips = if (checked) lists.ips + category.name else lists.ips - category.name))
            }
        }
    }
}

@Composable
private fun Section(title: String, source: String, onAll: () -> Unit, onNone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(source, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onAll) { Text("Все") }
        TextButton(onClick = onNone) { Text("Ни одной") }
    }
}

@Composable
private fun CategoryRow(category: GeoCategory, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(GeoLists.label(category.name))
            Text("${category.count} записей", style = MaterialTheme.typography.bodySmall)
        }
    }
}
