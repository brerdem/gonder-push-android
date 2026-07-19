package ai.gonder.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives FCM token refreshes and display messages.
 * Registered in the SDK AndroidManifest; merged into the host app.
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
                ?: "Notification"
        val body =
            message.notification?.body
                ?: message.data["body"]
                ?: ""
        val campaignId = message.data["campaignId"]
        val url = message.data["url"]

        // If the system already displayed a notification payload while the app
        // is in the background, onMessageReceived may not be called for
        // notification-only messages. We still show when we get a data payload.
        if (message.notification != null && message.data.isEmpty()) {
            // System tray handles display; nothing to do.
            return
        }

        showNotification(title, body, campaignId, url)
    }

    private fun showNotification(
        title: String,
        body: String,
        campaignId: String?,
        url: String?,
    ) {
        val channelId = "gonder_push"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Gönder Push",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }

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
            campaignId?.hashCode() ?: 0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .build()

        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)

        if (!campaignId.isNullOrEmpty()) {
            // Track open when the user taps — host can also call track from Activity.
            // For data-only display we can't know tap here; Activity should check extras.
        }
    }
}
