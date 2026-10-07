package dev.wearlink.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import dev.wearlink.core.WearLinkCore
import dev.wearlink.core.data.AppEntry
import dev.wearlink.core.data.InstalledApps
import dev.wearlink.core.vpn.TunnelMode
import dev.wearlink.core.vpn.VpnController
import dev.wearlink.shared.model.RoutingMode
import kotlinx.coroutines.launch

@Composable
fun AppsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by WearLinkCore.store.data.collectAsState()
    val routing = data.routing
    var showSystem by rememberSaveable { mutableStateOf(false) }
    val apps by produceState<List<AppEntry>?>(null) { value = InstalledApps.load(context) }
    val listState = rememberScalingLazyListState()

    // Chosen apps float to the top as of opening the screen, so rows don't jump on tap.
    val pinned = remember(apps) { routing.packages }
    val visible = remember(apps, showSystem) {
        apps.orEmpty()
            .filter { showSystem || !it.isSystem || it.packageName in pinned }
            .sortedByDescending { it.packageName in pinned }
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Через VPN") } }
            if (VpnController.mode(context) == TunnelMode.PROXY) {
                item {
                    Text(
                        "Часы работают в режиме прокси: он общий для всех приложений, выбор недоступен.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                return@ScalingLazyColumn
            }
            listOf(
                RoutingMode.ALL to "Все приложения",
                RoutingMode.ONLY_SELECTED to "Только выбранные",
                RoutingMode.ALL_EXCEPT to "Все, кроме выбранных",
            ).forEach { (mode, label) ->
                item {
                    RadioButton(
                        selected = routing.mode == mode,
                        onSelect = { scope.launch { WearLinkCore.store.setRouting(routing.copy(mode = mode)) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(label) },
                    )
                }
            }
            if (routing.mode != RoutingMode.ALL) {
                item {
                    SwitchButton(
                        checked = showSystem,
                        onCheckedChange = { showSystem = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Системные") },
                    )
                }
                if (apps == null) item { Text("Загрузка…") }
                items(visible, key = { it.packageName }) { app ->
                    val checked = app.packageName in routing.packages
                    CheckboxButton(
                        checked = checked,
                        onCheckedChange = { isChecked ->
                            val packages = if (isChecked) routing.packages + app.packageName else routing.packages - app.packageName
                            scope.launch { WearLinkCore.store.setRouting(routing.copy(packages = packages)) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}
