package dev.wearlink.wear.ui

import android.app.RemoteInput
import android.content.Intent
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Phone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import androidx.wear.remote.interactions.RemoteActivityHelper
import androidx.concurrent.futures.await
import dev.wearlink.core.WearLinkCore
import kotlinx.coroutines.launch

private const val INPUT_KEY = "link"

@Composable
fun AddScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()

    val keyboard = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(INPUT_KEY)?.toString()
        if (!text.isNullOrBlank()) {
            scope.launch {
                val message = runCatching { WearLinkCore.importer.import(text) }
                    .fold({ "Добавлено серверов: ${it.added}" }, { it.message ?: "Ошибка" })
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                onDone()
            }
        }
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Добавить") } }
            item {
                Text(
                    "Удобнее всего — из приложения WearLink на телефоне: вставьте ссылку и нажмите «Отправить на часы».",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                Button(
                    onClick = {
                        scope.launch {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("wearlink://add"))
                                .addCategory(Intent.CATEGORY_BROWSABLE)
                            val message = runCatching { RemoteActivityHelper(context).startRemoteActivity(intent).await() }
                                .fold({ "Откройте телефон" }, { "Телефон недоступен" })
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Default.Phone, contentDescription = null) },
                    label = { Text("Открыть на телефоне") },
                )
            }
            item {
                Button(
                    onClick = {
                        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                        RemoteInputIntentHelper.putRemoteInputsExtra(
                            intent,
                            listOf(
                                RemoteInput.Builder(INPUT_KEY)
                                    .setLabel("Ссылка или подписка")
                                    .wearableExtender {
                                        setEmojisAllowed(false)
                                        setInputActionType(EditorInfo.IME_ACTION_DONE)
                                    }
                                    .build(),
                            ),
                        )
                        keyboard.launch(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Icon(Icons.Default.Create, contentDescription = null) },
                    label = { Text("Ввести вручную") },
                )
            }
        }
    }
}
