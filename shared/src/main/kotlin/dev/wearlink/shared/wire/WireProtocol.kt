package dev.wearlink.shared.wire

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Phone → watch transfer over the Wearable Data Layer. */
object WireProtocol {
    /** DataItem path prefix; the phone appends a unique id so repeated imports are distinct items. */
    const val IMPORT_PATH_PREFIX = "/wearlink/import/"

    /** Message path the watch answers on. */
    const val ACK_PATH = "/wearlink/ack"

    /** Message telling the watch to download and install the latest release itself. */
    const val UPDATE_PATH = "/wearlink/update"

    const val KEY_REQUEST = "request"

    /** Capability declared in res/values/wear.xml of each app. */
    const val CAPABILITY_WATCH = "wearlink_watch"
    const val CAPABILITY_PHONE = "wearlink_phone"

    val json = Json { ignoreUnknownKeys = true }
}

@Serializable
data class ImportRequest(
    val id: String,
    /** What the user entered: links or a subscription URL. */
    val text: String,
    /** Subscription body the phone already downloaded, used if the watch cannot reach the URL. */
    val subscriptionBody: String? = null,
    val subscriptionHeaders: Map<String, String> = emptyMap(),
)

@Serializable
data class ImportResult(
    val id: String,
    val added: Int = 0,
    val title: String? = null,
    val error: String? = null,
)
