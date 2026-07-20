package ai.gonder.push

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * GonderPush is the entry point for the Gönder Android push SDK.
 *
 * Basic usage (in Application.onCreate or MainActivity):
 * ```
 * GonderPush.initialize(this, "YOUR_APP_ID")
 * GonderPush.addClickListener { n -> /* deep link */ }
 * GonderPush.registerForPushNotifications()
 * ```
 */
object GonderPush {

    private const val TAG = "GonderPush"
    private const val PREFS = "gonder.push"
    private const val KEY_DEVICE_ID = "gonder.push.deviceId"
    private const val KEY_APP_ID = "gonder.push.appId"
    private const val KEY_BASE_URL = "gonder.push.baseUrl"
    private const val KEY_EXTERNAL_ID = "gonder.push.externalId"
    private const val KEY_DEVICE_TOKEN = "gonder.push.deviceToken"
    private const val KEY_OPTED_IN = "gonder.push.optedIn"
    private const val KEY_TAGS = "gonder.push.tags"
    private const val KEY_CONSENT_REQUIRED = "gonder.push.consentRequired"
    private const val KEY_CONSENT_GIVEN = "gonder.push.consentGiven"

    const val DEBUG_LOG_ACTION = "ai.gonder.push.DEBUG_LOG"
    const val EXTRA_CAMPAIGN_ID = "campaignId"
    const val EXTRA_URL = "url"
    const val EXTRA_TITLE = "gonder.title"
    const val EXTRA_BODY = "gonder.body"
    const val EXTRA_DATA_JSON = "gonder.dataJson"

    private val executor = Executors.newSingleThreadExecutor()

    private val clickListeners = CopyOnWriteArraySet<GonderClickListener>()
    private val foregroundListeners =
        CopyOnWriteArraySet<GonderForegroundLifecycleListener>()
    private val permissionObservers = CopyOnWriteArraySet<GonderPermissionObserver>()
    private val subscriptionObservers =
        CopyOnWriteArraySet<GonderSubscriptionObserver>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var appId: String? = null

    @Volatile
    private var baseUrl: String = "https://gonder.ai"

    @Volatile
    private var externalId: String? = null

    @Volatile
    private var deviceToken: String? = null

    @Volatile
    private var optedIn: Boolean = true

    @Volatile
    private var consentRequired: Boolean = false

    @Volatile
    private var consentGiven: Boolean = false

    @Volatile
    private var logLevel: LogLevel = LogLevel.WARN

    private var tags: MutableMap<String, String> = linkedMapOf()

    /**
     * Initialize the SDK with your public App ID (from Gönder → Platforms → Android).
     */
    @JvmStatic
    @JvmOverloads
    fun initialize(context: Context, appId: String, baseUrl: String = "https://gonder.ai") {
        val app = context.applicationContext
        appContext = app
        this.appId = appId
        this.baseUrl = baseUrl.trimEnd('/')
        prefs(app).edit()
            .putString(KEY_APP_ID, appId)
            .putString(KEY_BASE_URL, this.baseUrl)
            .apply()

        externalId = prefs(app).getString(KEY_EXTERNAL_ID, null)
        deviceToken = prefs(app).getString(KEY_DEVICE_TOKEN, null)
        optedIn = prefs(app).getBoolean(KEY_OPTED_IN, true)
        consentRequired = prefs(app).getBoolean(KEY_CONSENT_REQUIRED, false)
        consentGiven = prefs(app).getBoolean(KEY_CONSENT_GIVEN, false)
        tags = loadTags(prefs(app))

        ensureNotificationChannel(app)
        debugLog("Initialized appId=$appId baseUrl=${this.baseUrl}", LogLevel.INFO)

        deviceToken?.let { sendRegistration(it) }
        notifySubscriptionObservers()
    }

    @JvmStatic
    fun setLogLevel(level: LogLevel) {
        logLevel = level
    }

    @JvmStatic
    fun addClickListener(listener: GonderClickListener) {
        clickListeners.add(listener)
    }

    @JvmStatic
    fun removeClickListener(listener: GonderClickListener) {
        clickListeners.remove(listener)
    }

    @JvmStatic
    fun addForegroundLifecycleListener(listener: GonderForegroundLifecycleListener) {
        foregroundListeners.add(listener)
    }

    @JvmStatic
    fun removeForegroundLifecycleListener(listener: GonderForegroundLifecycleListener) {
        foregroundListeners.remove(listener)
    }

    @JvmStatic
    fun addPermissionObserver(observer: GonderPermissionObserver) {
        permissionObservers.add(observer)
    }

    @JvmStatic
    fun removePermissionObserver(observer: GonderPermissionObserver) {
        permissionObservers.remove(observer)
    }

    @JvmStatic
    fun addSubscriptionObserver(observer: GonderSubscriptionObserver) {
        subscriptionObservers.add(observer)
        observer.onSubscriptionChanged(subscriptionState())
    }

    @JvmStatic
    fun removeSubscriptionObserver(observer: GonderSubscriptionObserver) {
        subscriptionObservers.remove(observer)
    }

    /**
     * Process a notification-open Intent (call from Activity.onCreate / onNewIntent).
     * Fires click listeners and tracks campaign opens.
     */
    @JvmStatic
    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val notification = notificationFromIntent(intent) ?: return
        fireClickListeners(notification)
        notification.campaignId?.let { trackOpened(it) }
    }

    internal fun shouldDisplayInForeground(notification: GonderNotification): Boolean {
        if (foregroundListeners.isEmpty()) return true
        var display = true
        for (listener in foregroundListeners) {
            try {
                if (!listener.onWillDisplay(notification)) display = false
            } catch (_: Exception) {
                // ignore
            }
        }
        return display
    }

    internal fun fireClickListeners(notification: GonderNotification) {
        for (listener in clickListeners) {
            try {
                listener.onClick(notification)
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(GonderPushMessagingService.CHANNEL_ID) != null) {
            return
        }
        val channel = NotificationChannel(
            GonderPushMessagingService.CHANNEL_ID,
            "Gönder Push",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Push notifications from Gönder"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Request notification permission (Android 13+) and fetch the FCM token.
     * Call after [initialize]. Prefer passing an [Activity] when available.
     */
    @JvmStatic
    @JvmOverloads
    fun registerForPushNotifications(activity: Activity? = null) {
        requestPermission(activity, fallbackToSettings = false)
        fetchAndRegisterToken()
    }

    /**
     * Request POST_NOTIFICATIONS on Android 13+. Returns current grant state.
     */
    @JvmStatic
    @JvmOverloads
    fun requestPermission(
        activity: Activity? = null,
        fallbackToSettings: Boolean = false,
    ): Boolean {
        val ctx = appContext ?: activity?.applicationContext ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notifyPermissionObservers(true)
            return true
        }
        val granted = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            notifyPermissionObservers(true)
            return true
        }
        val host = activity ?: (ctx as? Activity)
        if (host != null) {
            ActivityCompat.requestPermissions(
                host,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        } else {
            debugLog(
                "POST_NOTIFICATIONS not granted — pass an Activity to requestPermission",
                LogLevel.WARN
            )
        }
        if (fallbackToSettings && host != null) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", host.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                host.startActivity(intent)
            } catch (_: Exception) {
                // ignore
            }
        }
        notifyPermissionObservers(false)
        return false
    }

    /** Whether notification permission is currently granted. */
    @JvmStatic
    fun getPermission(): Boolean {
        val ctx = appContext ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Whether the OS may still show a permission dialog.
     * On Android this is approximate (true when not granted).
     */
    @JvmStatic
    fun getCanRequestPermission(): Boolean = !getPermission()

    /** Forward an FCM token (e.g. from [GonderPushMessagingService.onNewToken]). */
    @JvmStatic
    fun onNewToken(token: String) {
        handleDeviceToken(token)
    }

    @JvmStatic
    fun setExternalId(externalId: String) {
        this.externalId = externalId
        appContext?.let {
            prefs(it).edit().putString(KEY_EXTERNAL_ID, externalId).apply()
        }
        deviceToken?.let { sendRegistration(it) }
        notifySubscriptionObservers()
    }

    @JvmStatic
    fun removeExternalId() {
        this.externalId = null
        appContext?.let {
            prefs(it).edit().remove(KEY_EXTERNAL_ID).apply()
        }
        deviceToken?.let { sendRegistration(it) }
        notifySubscriptionObservers()
    }

    /** Alias for [setExternalId]. */
    @JvmStatic
    fun login(externalId: String) = setExternalId(externalId)

    /** Alias for [removeExternalId]. */
    @JvmStatic
    fun logout() = removeExternalId()

    @JvmStatic
    fun getExternalId(): String? = externalId

    @JvmStatic
    fun unsubscribe() {
        sendUnregister()
        optedIn = false
        persistOptedIn()
        notifySubscriptionObservers()
    }

    /** Soft-mute: keep the device registered but stop campaign delivery. */
    @JvmStatic
    fun optOut() {
        optedIn = false
        persistOptedIn()
        deviceToken?.let { sendRegistration(it) }
        notifySubscriptionObservers()
    }

    /** Re-enable campaign delivery after [optOut]. */
    @JvmStatic
    fun optIn() {
        optedIn = true
        persistOptedIn()
        if (deviceToken != null) {
            sendRegistration(deviceToken!!)
        } else {
            registerForPushNotifications()
        }
        notifySubscriptionObservers()
    }

    @JvmStatic
    fun isOptedIn(): Boolean = optedIn

    @JvmStatic
    fun addTag(key: String, value: String) {
        if (key.isBlank()) return
        tags[key] = value
        persistTags()
        deviceToken?.let { sendRegistration(it) }
    }

    @JvmStatic
    fun addTags(values: Map<String, String>) {
        tags.putAll(values)
        persistTags()
        deviceToken?.let { sendRegistration(it) }
    }

    @JvmStatic
    fun removeTag(key: String) {
        if (tags.remove(key) != null) {
            persistTags()
            deviceToken?.let { sendRegistration(it) }
        }
    }

    @JvmStatic
    fun removeTags(keys: Collection<String>) {
        var changed = false
        for (key in keys) {
            if (tags.remove(key) != null) changed = true
        }
        if (changed) {
            persistTags()
            deviceToken?.let { sendRegistration(it) }
        }
    }

    @JvmStatic
    fun getTags(): Map<String, String> = tags.toMap()

    @JvmStatic
    fun setConsentRequired(required: Boolean) {
        consentRequired = required
        appContext?.let {
            prefs(it).edit().putBoolean(KEY_CONSENT_REQUIRED, required).apply()
        }
    }

    @JvmStatic
    fun setConsentGiven(given: Boolean) {
        consentGiven = given
        appContext?.let {
            prefs(it).edit().putBoolean(KEY_CONSENT_GIVEN, given).apply()
        }
        if (given) {
            deviceToken?.let { sendRegistration(it) }
        }
    }

    @JvmStatic
    fun clearAllNotifications() {
        val ctx = appContext ?: return
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
    }

    /** Stable installation id persisted in SharedPreferences. */
    @JvmStatic
    fun getDeviceId(): String {
        val ctx = requireContext()
        val existing = prefs(ctx).getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrEmpty()) return existing
        val created = UUID.randomUUID().toString().lowercase(Locale.US)
        prefs(ctx).edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    @JvmStatic
    fun getDeviceToken(): String? = deviceToken

    internal fun handleDeviceToken(token: String) {
        val changed = deviceToken != token
        deviceToken = token
        appContext?.let {
            prefs(it).edit().putString(KEY_DEVICE_TOKEN, token).apply()
        }
        debugLog("FCM token received: ${token.take(12)}…", LogLevel.INFO)
        sendRegistration(token)
        if (changed) notifySubscriptionObservers()
    }

    internal fun trackOpened(campaignId: String) {
        val id = appId ?: return
        val body = JSONObject().apply {
            put("appId", id)
            put("campaignId", campaignId)
            put("event", "opened")
            put("deviceId", getDeviceId())
            deviceToken?.let { put("deviceToken", it) }
        }
        post("/api/mobile-push/track", body)
    }

    internal fun notificationFromRemoteData(
        title: String?,
        body: String?,
        data: Map<String, String>,
    ): GonderNotification {
        return GonderNotification(
            title = title,
            body = body,
            campaignId = data["campaignId"] ?: data["campaign_id"],
            url = data["url"] ?: data["launchURL"] ?: data["launchUrl"],
            additionalData = data,
        )
    }

    private fun notificationFromIntent(intent: Intent): GonderNotification? {
        val campaignId = intent.getStringExtra(EXTRA_CAMPAIGN_ID)
        val url = intent.getStringExtra(EXTRA_URL)
        val title = intent.getStringExtra(EXTRA_TITLE)
        val body = intent.getStringExtra(EXTRA_BODY)
        val dataJson = intent.getStringExtra(EXTRA_DATA_JSON)
        val data = linkedMapOf<String, String>()
        if (!dataJson.isNullOrEmpty()) {
            try {
                val obj = JSONObject(dataJson)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    data[key] = obj.optString(key)
                }
            } catch (_: Exception) {
                // ignore
            }
        }
        if (!campaignId.isNullOrEmpty()) data.putIfAbsent("campaignId", campaignId)
        if (!url.isNullOrEmpty()) data.putIfAbsent("url", url)
        if (campaignId.isNullOrEmpty() && url.isNullOrEmpty() && data.isEmpty()) {
            return null
        }
        return GonderNotification(
            title = title,
            body = body,
            campaignId = campaignId ?: data["campaignId"],
            url = url ?: data["url"],
            additionalData = data,
        )
    }

    private fun fetchAndRegisterToken() {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (!token.isNullOrEmpty()) {
                    handleDeviceToken(token)
                }
            }
            .addOnFailureListener { err ->
                debugLog("Failed to fetch FCM token: ${err.message}", LogLevel.ERROR)
                Log.e(TAG, "Failed to fetch FCM token", err)
            }
    }

    private fun sendRegistration(token: String) {
        val id = appId ?: run {
            debugLog("sendRegistration skipped — not initialized", LogLevel.WARN)
            return
        }
        val ctx = appContext ?: return
        val body = JSONObject().apply {
            put("appId", id)
            put("deviceToken", token)
            put("deviceId", getDeviceId())
            put("platform", "android")
            put("sdk", "native")
            put("environment", environment())
            put("bundleId", ctx.packageName)
            put("locale", Locale.getDefault().toLanguageTag())
            put("optedIn", optedIn)
            put("tags", JSONObject(tags as Map<*, *>))
            try {
                val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                @Suppress("DEPRECATION")
                put("appVersion", pkg.versionName ?: "")
            } catch (_: Exception) {
                // optional
            }
            externalId?.let { put("externalId", it) }
        }
        post("/api/mobile-push/register", body)
    }

    private fun sendUnregister() {
        val id = appId ?: return
        val body = JSONObject().apply {
            put("appId", id)
            put("deviceId", getDeviceId())
            deviceToken?.let { put("deviceToken", it) }
        }
        post("/api/mobile-push/unregister", body)
    }

    private fun environment(): String {
        val ctx = appContext ?: return "production"
        return try {
            val info = ctx.applicationInfo
            if ((info.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                "sandbox"
            } else {
                "production"
            }
        } catch (_: Exception) {
            "production"
        }
    }

    private fun canSendNetwork(): Boolean {
        if (!consentRequired) return true
        return consentGiven
    }

    private fun post(path: String, body: JSONObject) {
        if (!canSendNetwork()) {
            debugLog("$path skipped — consent required but not given", LogLevel.INFO)
            return
        }
        executor.execute {
            try {
                val url = URL("$baseUrl$path")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }
                conn.outputStream.use { os ->
                    os.write(body.toString().toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                debugLog("POST $path → $code", LogLevel.DEBUG)
                conn.disconnect()
            } catch (e: Exception) {
                debugLog("POST $path failed: ${e.message}", LogLevel.ERROR)
                Log.e(TAG, "POST $path failed", e)
            }
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun requireContext(): Context =
        appContext ?: error("GonderPush.initialize() must be called first")

    private fun persistOptedIn() {
        appContext?.let {
            prefs(it).edit().putBoolean(KEY_OPTED_IN, optedIn).apply()
        }
    }

    private fun persistTags() {
        appContext?.let {
            prefs(it).edit().putString(KEY_TAGS, JSONObject(tags as Map<*, *>).toString()).apply()
        }
    }

    private fun loadTags(prefs: SharedPreferences): MutableMap<String, String> {
        val raw = prefs.getString(KEY_TAGS, null) ?: return linkedMapOf()
        return try {
            val obj = JSONObject(raw)
            val map = linkedMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.optString(key)
            }
            map
        } catch (_: Exception) {
            linkedMapOf()
        }
    }

    private fun subscriptionState(): PushSubscriptionState =
        PushSubscriptionState(
            deviceId = try {
                getDeviceId()
            } catch (_: Exception) {
                ""
            },
            deviceToken = deviceToken,
            optedIn = optedIn,
            externalId = externalId,
        )

    private fun notifySubscriptionObservers() {
        val state = subscriptionState()
        for (observer in subscriptionObservers) {
            try {
                observer.onSubscriptionChanged(state)
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun notifyPermissionObservers(granted: Boolean) {
        for (observer in permissionObservers) {
            try {
                observer.onPermissionChanged(granted)
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    internal fun ensureRestored(context: Context) {
        if (appContext != null && appId != null) return
        val app = context.applicationContext
        appContext = app
        val p = prefs(app)
        appId = p.getString(KEY_APP_ID, null)
        baseUrl = p.getString(KEY_BASE_URL, null)?.trimEnd('/') ?: "https://gonder.ai"
        externalId = p.getString(KEY_EXTERNAL_ID, null)
        deviceToken = p.getString(KEY_DEVICE_TOKEN, null)
        optedIn = p.getBoolean(KEY_OPTED_IN, true)
        consentRequired = p.getBoolean(KEY_CONSENT_REQUIRED, false)
        consentGiven = p.getBoolean(KEY_CONSENT_GIVEN, false)
        tags = loadTags(p)
    }

    private fun debugLog(message: String, level: LogLevel = LogLevel.DEBUG) {
        if (logLevel == LogLevel.NONE || level.ordinal > logLevel.ordinal) return
        when (level) {
            LogLevel.ERROR -> Log.e(TAG, message)
            LogLevel.WARN -> Log.w(TAG, message)
            LogLevel.INFO -> Log.i(TAG, message)
            else -> Log.d(TAG, message)
        }
        val ctx = appContext ?: return
        try {
            val intent = Intent(DEBUG_LOG_ACTION)
            intent.setPackage(ctx.packageName)
            intent.putExtra("message", message)
            ctx.sendBroadcast(intent)
        } catch (_: Exception) {
            // ignore
        }
    }
}
