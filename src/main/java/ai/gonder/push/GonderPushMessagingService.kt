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
import org.json.JSONObject

/**
 * Receives FCM token refreshes and display messages.
 * Registered in the SDK AndroidManifest; merged into the host app.
 *
 * Important: when the app is in the foreground, FCM delivers notification
 * messages here instead of the system tray — we must display them ourselves
 * unless a foreground lifecycle listener suppresses them.
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

        val data = message.data
        val title =
            message.notification?.title
                ?: data["title"]
                ?: data["heading"]
                ?: "Notification"
        val body =
            message.notification?.body
                ?: data["body"]
                ?: data["content"]
                ?: ""

        val notification = GonderPush.notificationFromRemoteData(title, body, data)
        Log.d(TAG, "onMessageReceived title=$title dataKeys=${data.keys}")

        if (!GonderPush.shouldDisplayInForeground(notification)) {
            Log.d(TAG, "Foreground listener suppressed display")
            return
        }

        showNotification(notification)
    }

    private fun showNotification(notification: GonderNotification) {
        ensureChannel()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val openIntent = Intent(this, GonderPushOpenReceiver::class.java).apply {
            action = GonderPushOpenReceiver.ACTION_OPEN
            putExtra(GonderPush.EXTRA_CAMPAIGN_ID, notification.campaignId)
            putExtra(GonderPush.EXTRA_URL, notification.url)
            putExtra(GonderPush.EXTRA_TITLE, notification.title)
            putExtra(GonderPush.EXTRA_BODY, notification.body)
            putExtra(
                GonderPush.EXTRA_DATA_JSON,
                JSONObject(notification.additionalData as Map<*, *>).toString()
            )
        }

        val pending = PendingIntent.getBroadcast(
            this,
            (notification.campaignId?.hashCode() ?: System.currentTimeMillis().toInt()),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconRes = if (applicationInfo.icon != 0) {
            applicationInfo.icon
        } else {
            android.R.drawable.ic_dialog_info
        }

        val built = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(notification.title ?: "Notification")
            .setContentText(notification.body.orEmpty())
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(notification.body.orEmpty())
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pending)
            .build()

        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), built)
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
