package ai.gonder.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles notification taps for notifications displayed by the SDK.
 * Fires click listeners, tracks opens, then launches the host app.
 */
class GonderPushOpenReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        GonderPush.ensureRestored(context.applicationContext)
        GonderPush.handleIntent(intent)

        // Click listeners + open tracking already ran via handleIntent above.
        // Launch the host app without re-attaching campaign extras to avoid
        // double-firing if the Activity also calls GonderPush.handleIntent.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        context.startActivity(launch)
    }

    companion object {
        const val ACTION_OPEN = "ai.gonder.push.OPEN_NOTIFICATION"
    }
}
