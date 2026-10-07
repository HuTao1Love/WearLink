package dev.wearlink.mobile.ui

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.wearlink.core.WearLinkCore
import dev.wearlink.core.data.FetchedSubscription
import dev.wearlink.core.data.SubscriptionFetcher
import dev.wearlink.mobile.sync.WatchStatus
import dev.wearlink.mobile.sync.WatchSync
import dev.wearlink.shared.parse.ImportClassifier
import dev.wearlink.shared.parse.ImportInput
import kotlinx.coroutines.launch

@Composable
fun AddScreen(
    modifier: Modifier,
    messages: Messages,
    initialText: String?,
    onConsumed: () -> Unit,
    onAdded: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val watch by WatchSync.status.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var toPhone by rememberSaveable { mutableStateOf(true) }
    var toWatch by rememberSaveable { mutableStateOf(watch is WatchStatus.Ready) }
    var busy by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(initialText) {
        if (initialText != null) {
            if (initialText.isNotBlank()) text = initialText
            onConsumed()
        }
    }
    LaunchedEffect(watch) {
        if (watch is WatchStatus.Ready) toWatch = true
    }

    fun paste() {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        val value = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (value.isNullOrBlank()) messages.show("Буфер обмена пуст") else text = value
    }

    fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let { text = it } }
            .addOnFailureListener { messages.show("Сканер недоступен: ${it.message}") }
    }

    fun submit() = scope.launch {
        busy = true
        try {
            val input = ImportClassifier.classify(text)
            if (input is ImportInput.Invalid) {
                messages.show(input.reason)
                return@launch
            }
            val report = mutableListOf<String>()
            var fetched: FetchedSubscription? = null
            if (toPhone) {
                val outcome = WearLinkCore.importer.import(text)
                fetched = outcome.fetched
                report += "добавлено серверов: ${outcome.added}"
            }
            if (toWatch) {
                // Hand the watch the downloaded body too, in case it cannot reach the provider itself.
                if (fetched == null && input is ImportInput.SubscriptionUrl) {
                    fetched = runCatching { SubscriptionFetcher.fetch(input.url) }.getOrNull()
                }
                WatchSync.send(context, text, fetched)
                report += "отправлено на часы"
            }
            messages.show(report.joinToString(", ").replaceFirstChar { it.uppercase() })
            text = ""
            if (toPhone) onAdded()
        } catch (e: Exception) {
            messages.show(e.message ?: "Ошибка")
        } finally {
            busy = false
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ссылка или подписка", style = MaterialTheme.typography.titleLarge)
        Text(
            "vless, vmess, trojan, ss, hysteria2, hysteria, tuic, anytls или адрес подписки https://…",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            placeholder = { Text("Вставьте сюда") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ::paste) {
                Icon(Icons.Default.ContentPaste, contentDescription = null)
                Text("Вставить", Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = ::scan) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Text("QR-код", Modifier.padding(start = 8.dp))
            }
        }
        CheckRow("Добавить на телефон", toPhone) { toPhone = it }
        CheckRow(
            when (val w = watch) {
                is WatchStatus.Ready -> "Отправить на часы (${w.watchName})"
                is WatchStatus.NoApp -> "Отправить на часы (установите WearLink на часы)"
                else -> "Отправить на часы (часы не подключены)"
            },
            toWatch,
        ) { toWatch = it }
        Button(
            onClick = { submit() },
            enabled = !busy && text.isNotBlank() && (toPhone || toWatch),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
            Text("Добавить")
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}
