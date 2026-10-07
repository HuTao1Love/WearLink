package dev.wearlink.wear

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import dev.wearlink.core.WearLinkCore
import dev.wearlink.wear.ui.WearLinkApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { WearLinkApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** vless:// / hy2:// links opened on the watch (e.g. `adb shell am start -d ...`). */
    private fun handleIntent(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        lifecycleScope.launch {
            val message = runCatching { WearLinkCore.importer.import(link) }
                .fold({ "Добавлено: ${it.title ?: it.added}" }, { it.message ?: "Ошибка" })
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }
}
