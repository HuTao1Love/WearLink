package dev.wearlink.core.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dev.wearlink.core.WearLinkCore
import dev.wearlink.core.data.SubscriptionFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** update.json published next to the APKs in every GitHub release (see scripts/release.sh). */
@Serializable
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val notes: String = "",
    val phoneApk: String,
    val watchApk: String,
    val phoneSha256: String = "",
    val watchSha256: String = "",
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val versionName: String) : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Error(val message: String) : UpdateState
}

/** Checks the latest release and installs it over the running app with PackageInstaller. */
object Updater {

    private val json = Json { ignoreUnknownKeys = true }

    /** APKs are tens of MB and a watch on Bluetooth is slow: no overall deadline, only a stall timeout. */
    private val downloadClient by lazy {
        SubscriptionFetcher.client.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    internal fun setState(state: UpdateState) {
        _state.value = state
    }

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    private fun currentVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /** Returns the newer release, or null when this build is current. Updates [state] either way. */
    suspend fun check(context: Context): UpdateManifest? {
        _state.value = UpdateState.Checking
        return try {
            val manifest = withContext(Dispatchers.IO) {
                json.decodeFromString(UpdateManifest.serializer(), download(WearLinkCore.updateBaseUrl + MANIFEST))
            }
            if (manifest.versionCode > currentVersionCode(context)) {
                _state.value = UpdateState.Available(manifest)
                manifest
            } else {
                _state.value = UpdateState.UpToDate(currentVersionName(context))
                null
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Error("Не удалось проверить обновления: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    /** Downloads this device's APK from [manifest] and hands it to the system installer. */
    suspend fun install(context: Context, manifest: UpdateManifest, isWatch: Boolean) {
        val apkName = if (isWatch) manifest.watchApk else manifest.phoneApk
        val expectedSha = if (isWatch) manifest.watchSha256 else manifest.phoneSha256
        try {
            val apk = withContext(Dispatchers.IO) { downloadApk(context, WearLinkCore.updateBaseUrl + apkName) }
            if (expectedSha.isNotEmpty() && !sha256(apk).equals(expectedSha, ignoreCase = true)) {
                apk.delete()
                throw IllegalStateException("контрольная сумма не совпала")
            }
            _state.value = UpdateState.Installing
            withContext(Dispatchers.IO) { commit(context, apk) }
        } catch (e: Exception) {
            Log.w(TAG, "update failed", e)
            _state.value = UpdateState.Error("Обновление не удалось: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun checkAndInstall(context: Context, isWatch: Boolean) {
        val manifest = check(context) ?: return
        install(context, manifest, isWatch)
    }

    private fun download(url: String): String =
        SubscriptionFetcher.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body.string()
        }

    /**
     * Downloads with resume: a watch on Bluetooth or one whose screen goes to ambient drops
     * long transfers, so on failure we reconnect with a Range header and continue.
     */
    private fun downloadApk(context: Context, url: String): File {
        val target = File(File(context.cacheDir, "update").apply { mkdirs() }, "update.apk")
        target.delete()
        _state.value = UpdateState.Downloading(0f)
        var total: Long? = null
        var lastError: Exception? = null
        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            val offset = target.length()
            val request = Request.Builder().url(url).apply {
                if (offset > 0) header("Range", "bytes=$offset-")
            }.build()
            try {
                downloadClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    // 206 continues the file; 200 means the server ignored Range, so start over.
                    val resumed = response.code == 206
                    val body = response.body
                    if (total == null || !resumed) {
                        total = body.contentLength().takeIf { it > 0 }?.let { if (resumed) it + offset else it }
                    }
                    java.io.FileOutputStream(target, resumed).use { out ->
                        body.byteStream().use { input -> copyWithProgress(input, out, if (resumed) offset else 0L, total) }
                    }
                }
                if (total == null || target.length() >= total!!) return target
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "download attempt ${attempt + 1} failed at ${target.length()} bytes", e)
            }
        }
        throw lastError ?: IllegalStateException("загрузка прервалась")
    }

    private fun copyWithProgress(input: java.io.InputStream, out: java.io.OutputStream, start: Long, total: Long?) {
        val buffer = ByteArray(64 * 1024)
        var done = start
        var lastReported = 0f
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            done += read
            if (total != null) {
                val progress = done.toFloat() / total
                if (progress - lastReported >= 0.02f) {
                    lastReported = progress
                    _state.value = UpdateState.Downloading(progress)
                }
            }
        }
    }

    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // An app updating itself may skip the confirmation dialog on Android 12+.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    /** False until the user allows this app to install apps (Settings → Install unknown apps). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun isWatch(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val MANIFEST = "update.json"
    private const val DOWNLOAD_ATTEMPTS = 6
    private const val TAG = "WearLinkUpdate"
}
