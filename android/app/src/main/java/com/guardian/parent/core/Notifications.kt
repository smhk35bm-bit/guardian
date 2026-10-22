package com.guardian.parent.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.guardian.parent.R
import com.guardian.parent.ui.MainActivity
import com.guardian.parent.ui.PermissionActivity

/**
 * Builds the permanent "monitoring is active" notification.
 *
 * This notification is the product's transparency guarantee: it is ongoing
 * (cannot be swiped away), it is posted on a low-importance channel so it
 * never plays a sound, and it always shows the shield icon in the status bar.
 * Nothing in this app is able to hide it.
 *
 * It also carries a "grant screen capture" action when the system consent is
 * missing. The action does not capture anything by itself: it opens the
 * permission screen, which lets Android display its own consent dialog.
 */
object Notifications {

    /** A louder channel, used only when the screen capture consent is gone. */
    const val CONSENT_CHANNEL_ID = "guardian_screen_consent"

    /** Id of the "screen capture consent needed" notification. */
    const val CONSENT_NOTIFICATION_ID = Config.NOTIFICATION_ID + 1

    /** Creates the channels used by the service. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            Config.NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_description)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)

        // The consent reminder has to be noticeable, otherwise the screen
        // capture silently stays off until somebody opens the app.
        val consent = NotificationChannel(
            CONSENT_CHANNEL_ID,
            context.getString(R.string.notif_consent_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notif_consent_channel_description)
            setShowBadge(true)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(consent)
    }

    /** The always-visible notification attached to the foreground service. */
    fun build(context: Context, deviceName: String, needsConsent: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = context.getString(R.string.notif_text, deviceName)
        val builder = NotificationCompat.Builder(context, Config.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (needsConsent) {
            builder
                .setSubText(context.getString(R.string.notif_consent_needed_short))
                .addAction(
                    0,
                    context.getString(R.string.notif_consent_action),
                    consentPendingIntent(context, 2)
                )
        }
        return builder.build()
    }

    /**
     * Posts (or refreshes) the reminder that the screen capture needs consent.
     *
     * Android requires a fresh consent per projection session, so this can
     * appear again after a reboot or after the system revoked the session.
     */
    fun notifyConsentNeeded(context: Context) {
        ensureChannel(context)
        val action = consentPendingIntent(context, 3)
        val notification = NotificationCompat.Builder(context, CONSENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle(context.getString(R.string.notif_consent_title))
            .setContentText(context.getString(R.string.notif_consent_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notif_consent_text))
            )
            .setContentIntent(action)
            .addAction(0, context.getString(R.string.notif_consent_action), action)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(CONSENT_NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Logger.w("Unable to post the screen capture consent notification", t)
        }
    }

    /** Removes the reminder once a consent is active again. */
    fun cancelConsent(context: Context) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.cancel(CONSENT_NOTIFICATION_ID)
        } catch (t: Throwable) {
            Logger.w("Unable to cancel the screen capture consent notification", t)
        }
    }

    /**
     * Opens [PermissionActivity] in the "ask for the screen capture consent"
     * mode, so a single tap on the notification shows the system dialog.
     */
    private fun consentPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, PermissionActivity::class.java).apply {
            putExtra(PermissionActivity.EXTRA_REQUEST_SCREEN_CAPTURE, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
