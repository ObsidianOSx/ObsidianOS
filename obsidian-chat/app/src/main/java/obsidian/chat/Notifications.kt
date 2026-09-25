package obsidian.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Tells you a message arrived, and nothing else: no sender, no preview, no count of who talks to
 * you. Anyone glancing at the phone — or at a lock screen — learns only that the app has something
 * waiting, which they would see anyway by opening it.
 */
object Notifications {
    private const val CHANNEL = "sealed-messages"
    private const val ID = 1
    private const val UPDATE_CHANNEL = "system-updates"
    private const val UPDATE_ID = 2

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL, "New messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Tells you a sealed message has arrived, without showing anything about it"
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)

        // Updates get their own channel, so someone can silence message alerts without also
        // silencing the one that tells them their phone is missing security fixes.
        manager.createNotificationChannel(
            NotificationChannel(UPDATE_CHANNEL, "System updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a new version of OBSIDIAN is available"
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )
    }

    fun sealedMessageArrived(context: Context) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("New sealed message")
            .setContentText("Open OBSIDIAN to read it")
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(ID, notification)
    }

    /**
     * A security update is waiting. Worth interrupting someone for: the alternative is a phone that
     * quietly stays on an old system because nobody happened to open the right screen.
     */
    fun updateAvailable(context: Context, version: String) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, UPDATE_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("System update $version")
            .setContentText("Open OBSIDIAN, then Security, to install it")
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(UPDATE_ID, notification)
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}
