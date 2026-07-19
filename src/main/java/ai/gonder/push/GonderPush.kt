package ai.gonder.push

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * GonderPush is the entry point for the Gönder Android push SDK.
 *
 * Basic usage (in Application.onCreate or MainActivity):
 * ```
 * GonderPush.initialize(this, "YOUR_APP_ID")
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

    const val DEBUG_LOG_ACTION = "ai.gonder.push.DEBUG_LOG"

    private val executor = Executors.newSingleThreadExecutor()

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

        debugLog("Initialized appId=$appId baseUrl=${this.baseUrl}")

        // Re-register if we already have a token (e.g. App ID changed).
        deviceToken?.let { sendRegistration(it) }
    }

    /**
     * Request notification permission (Android 13+) and fetch the FCM token.
     * Call after [initialize].
     */
    @JvmStatic
    fun registerForPushNotifications() {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                val activity = ctx as? Activity
                if (activity != null) {
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        1001
                    )
                } else {
                    debugLog("POST_NOTIFICATIONS not granted — request from an Activity")
                }
            }
        }
        fetchAndRegisterToken()
    }

    /**
     * Forward an FCM token (e.g. from [GonderPushMessagingService.onNewToken]).
     */
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
    }

    @JvmStatic
    fun removeExternalId() {
        this.externalId = null
        appContext?.let {
            prefs(it).edit().remove(KEY_EXTERNAL_ID).apply()
        }
        deviceToken?.let { sendRegistration(it) }
    }

    @JvmStatic
    fun unsubscribe() {
        sendUnregister()
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
        deviceToken = token
        appContext?.let {
            prefs(it).edit().putString(KEY_DEVICE_TOKEN, token).apply()
        }
        debugLog("FCM token received: ${token.take(12)}…")
        sendRegistration(token)
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

    private fun fetchAndRegisterToken() {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (!token.isNullOrEmpty()) {
                    handleDeviceToken(token)
                }
            }
            .addOnFailureListener { err ->
                debugLog("Failed to fetch FCM token: ${err.message}")
                Log.e(TAG, "Failed to fetch FCM token", err)
            }
    }

    private fun sendRegistration(token: String) {
        val id = appId ?: run {
            debugLog("sendRegistration skipped — not initialized")
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

    private fun post(path: String, body: JSONObject) {
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
                debugLog("POST $path → $code")
                conn.disconnect()
            } catch (e: Exception) {
                debugLog("POST $path failed: ${e.message}")
                Log.e(TAG, "POST $path failed", e)
            }
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun requireContext(): Context =
        appContext ?: error("GonderPush.initialize() must be called first")

    internal fun ensureRestored(context: Context) {
        if (appContext != null && appId != null) return
        val app = context.applicationContext
        appContext = app
        val p = prefs(app)
        appId = p.getString(KEY_APP_ID, null)
        baseUrl = p.getString(KEY_BASE_URL, null)?.trimEnd('/') ?: "https://gonder.ai"
        externalId = p.getString(KEY_EXTERNAL_ID, null)
        deviceToken = p.getString(KEY_DEVICE_TOKEN, null)
    }

    private fun debugLog(message: String) {
        Log.d(TAG, message)
        val ctx = appContext ?: return
        try {
            val intent = android.content.Intent(DEBUG_LOG_ACTION)
            intent.setPackage(ctx.packageName)
            intent.putExtra("message", message)
            ctx.sendBroadcast(intent)
        } catch (_: Exception) {
            // ignore
        }
    }
}
