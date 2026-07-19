package ai.gonder.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives FCM token refreshes and display messages.
 * Registered in the SDK AndroidManifest; merged into the host app.
 *
 * Important: when the app is in the foreground, FCM delivers notification
 * messages here instead of the system tray — we must display them ourselves.
 */
class GonderPushMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        GonderPush.ensureRestored(applicationContext)
        GonderPush.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        GonderPush.ensureRestored(applicationContext)

        val title =
            message.notification?.title
                ?: message.data["title"]
                ?: message.data["heading"]
                ?: "Notification"
        val body =
            message.notification?.body
                ?: message.data["body"]
                ?: message.data["content"]
                ?: ""
        val campaignId = message.data["campaignId"]
        val url = message.data["url"]

        Log.d(TAG, "onMessageReceived title=$title dataKeys=${message.data.keys}")

        // Always display when we receive the message. Background notification
        // payloads are usually shown by the system without calling this method;
        // when we ARE called (foreground, or data messages), we must show it.
        showNotification(title, body, campaignId, url)

        if (!campaignId.isNullOrEmpty()) {
            // Delivered/opened tracking for taps is handled when the activity opens.
        }
    }

    private fun showNotification(
        title: String,
        body: String,
        campaignId: String?,
        url: String?,
    ) {
        ensureChannel()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent()
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (!campaignId.isNullOrEmpty()) {
            launchIntent.putExtra("campaignId", campaignId)
        }
        if (!url.isNullOrEmpty()) {
            launchIntent.putExtra("url", url)
        }

        val pending = PendingIntent.getActivity(
            this,
            (campaignId?.hashCode() ?: System.currentTimeMillis().toInt()),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Prefer app icon; fall back to a system drawable if missing/invalid.
        val iconRes = if (applicationInfo.icon != 0) {
            applicationInfo.icon
        } else {
            android.R.drawable.ic_dialog_info
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pending)
            .build()

        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gönder Push",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Push notifications from Gönder"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "GonderPush"
        const val CHANNEL_ID = "gonder_push"
    }
}
