package dev.wearlink.core

import android.app.Application
import android.content.Context
import dev.wearlink.core.data.Importer
import dev.wearlink.core.data.Store
import dev.wearlink.core.data.SubscriptionWorker
import dev.wearlink.core.vpn.BoxVpnService
import dev.wearlink.core.vpn.SystemProxy
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Process-wide singletons shared by the phone and watch apps. Call [init] from Application.onCreate. */
object WearLinkCore {

    lateinit var app: Application
        private set
    lateinit var store: Store
        private set
    lateinit var importer: Importer
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Base URL of the release assets (update.json and the APKs); set by the app from BuildConfig. */
    var updateBaseUrl: String = ""
        private set

    /** Small icon for the VPN notification; apps may override with their own drawable. */
    var notificationIcon: Int = R.drawable.ic_wearlink

    private var libboxReady = false

    fun init(application: Application, updateBaseUrl: String? = null) {
        if (updateBaseUrl != null) this.updateBaseUrl = updateBaseUrl
        if (::app.isInitialized) return
        app = application
        store = Store(application)
        importer = Importer(store)
        SubscriptionWorker.schedule(application)
        // A fresh process means sing-box is not running: drop a system proxy left behind by a
        // crash or a kill, otherwise the watch would have no internet at all.
        if (BoxVpnService.instance == null) SystemProxy.disable(application)
    }

    /** Loads the Go runtime lazily: the watch UI should not pay for it until the VPN starts. */
    @Synchronized
    fun ensureLibbox(context: Context) {
        if (libboxReady) return
        val workingDir = File(context.filesDir, "box").apply { mkdirs() }
        Libbox.setup(SetupOptions().apply {
            basePath = context.filesDir.path
            workingPath = workingDir.path
            tempPath = context.cacheDir.path
            fixAndroidStack = true
            logMaxLines = 200
            debug = false
            crashReportSource = "WearLink"
        })
        libboxReady = true
    }
}
