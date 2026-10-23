package com.guardian.parent.service.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.guardian.parent.core.Logger

/**
 * Captures the notifications shown on the device.
 *
 * This is the standard, user-consented Android mechanism: the service stays
 * inert until the parent enables "الوصول إلى الإشعارات" for Guardian in the
 * system settings, and the system shows our name in that list permanently.
 * Nothing is intercepted covertly — if the access is revoked the service is
 * disconnected by Android itself.
 *
 * Only the visible title/text of a notification is stored; the queue is
 * bounded and drained by [com.guardian.parent.service.collectors.NotificationCollector].
 */
class GuardianNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Logger.i("Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Logger.i("Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            if (sbn.packageName == packageName) return
            val notification = sbn.notification ?: return

            // Group summaries only repeat the content of their children.
            if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

            val extras = notification.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            val text = (
                extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
                )?.toString()?.trim()

            if (title.isNullOrBlank() && text.isNullOrBlank()) return
            NotificationStore.append(
                context = this,
                packageName = sbn.packageName,
                title = title,
                text = text,
                at = sbn.postTime
            )
        } catch (t: Throwable) {
            Logger.w("Unable to read an incoming notification", t)
        }
    }
}
