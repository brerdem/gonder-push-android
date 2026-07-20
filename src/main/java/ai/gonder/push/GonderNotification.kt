package ai.gonder.push

/**
 * Normalized push payload delivered to click / foreground listeners.
 */
data class GonderNotification(
    val title: String? = null,
    val body: String? = null,
    val campaignId: String? = null,
    val url: String? = null,
    val additionalData: Map<String, String> = emptyMap(),
)

fun interface GonderClickListener {
    fun onClick(notification: GonderNotification)
}

/**
 * Called when a push arrives while the app is in the foreground.
 * Return false to suppress the system/tray notification.
 */
fun interface GonderForegroundLifecycleListener {
    fun onWillDisplay(notification: GonderNotification): Boolean
}

fun interface GonderPermissionObserver {
    fun onPermissionChanged(granted: Boolean)
}

data class PushSubscriptionState(
    val deviceId: String,
    val deviceToken: String?,
    val optedIn: Boolean,
    val externalId: String?,
)

fun interface GonderSubscriptionObserver {
    fun onSubscriptionChanged(state: PushSubscriptionState)
}

enum class LogLevel {
    NONE,
    ERROR,
    WARN,
    INFO,
    DEBUG,
    VERBOSE,
}
