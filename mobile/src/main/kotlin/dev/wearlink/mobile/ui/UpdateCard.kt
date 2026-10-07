package dev.wearlink.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wearlink.core.update.UpdateState
import dev.wearlink.core.update.Updater
import dev.wearlink.mobile.sync.WatchSync
import kotlinx.coroutines.launch

/** "Update phone and watch" block at the bottom of the home screen. */
@Composable
fun UpdateCard(messages: Messages) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsState()

    LaunchedEffect(Unit) {
        if (state is UpdateState.Idle) Updater.check(context)
    }

    fun check() = scope.launch { Updater.check(context) }

    when (val s = state) {
        is UpdateState.Available -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Доступна версия ${s.manifest.versionName}", style = MaterialTheme.typography.titleMedium)
                if (s.manifest.notes.isNotBlank()) {
                    Text(s.manifest.notes, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                Button(
                    onClick = {
                        scope.launch {
                            // Ask the watch first: installing our own APK kills this process.
                            val watches = WatchSync.requestWatchUpdate(context)
                            if (watches > 0) messages.show("Часы скачают обновление сами. Если спросят, подтвердите на часах")
                            Updater.install(context, s.manifest, isWatch = false)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Обновить телефон и часы") }
            }
        }
        is UpdateState.Downloading -> Column(Modifier.fillMaxWidth()) {
            Text("Загрузка обновления…", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        UpdateState.Installing -> Text("Установка…", style = MaterialTheme.typography.bodyMedium)
        UpdateState.Checking -> Text("Проверка обновлений…", style = MaterialTheme.typography.bodySmall)
        is UpdateState.Error -> Column {
            Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { check() }) { Text("Повторить") }
        }
        is UpdateState.UpToDate -> TextButton(onClick = { check() }) { Text("Версия ${s.versionName} · проверить обновления") }
        UpdateState.Idle -> TextButton(onClick = { check() }) { Text("Проверить обновления") }
    }
}
