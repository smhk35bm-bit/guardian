package com.guardian.parent.service.collectors

import android.content.Context
import android.content.pm.PackageManager
import com.guardian.parent.core.CollectorIds
import com.guardian.parent.core.Logger
import com.guardian.parent.data.CollectedItem
import com.guardian.parent.data.ItemKind
import com.guardian.parent.data.NotificationPayload
import com.guardian.parent.service.notifications.NotificationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Uploads the notifications buffered by
 * [com.guardian.parent.service.notifications.GuardianNotificationListener].
 *
 * The listener itself only runs while the user keeps "notification access"
 * enabled for Guardian, so this collector returns an empty list whenever that
 * access is missing — there is nothing to read and nothing to fake.
 */
class NotificationCollector(private val context: Context) : Collector {

    override val id: String = CollectorIds.NOTIFICATIONS

    override suspend fun collect(): List<CollectedItem> = withContext(Dispatchers.IO) {
        try {
            if (!NotificationStore.hasAccess(context)) {
                Logger.d("Notification access is not granted, skipping the report")
                return@withContext emptyList()
            }
            val pending = NotificationStore.drain(context)
            if (pending.isEmpty()) return@withContext emptyList()

            val items = ArrayList<CollectedItem>(pending.size)
            for (entry in pending) {
                val packageName = entry.optString("package")
                if (packageName.isBlank()) continue
                val payload = NotificationPayload(
                    packageName = packageName,
                    label = labelFor(packageName),
                    title = entry.optString("title").takeIf { it.isNotBlank() },
                    text = entry.optString("text").takeIf { it.isNotBlank() },
                    at = entry.optLong("at", System.currentTimeMillis())
                )
                items += CollectedItem(ItemKind.EVENT, payload = payload.toJson())
            }
            Logger.d("Collected ${items.size} notifications")
            items
        } catch (t: Throwable) {
            Logger.e("Notification collection failed", t)
            emptyList()
        }
    }

    private fun labelFor(packageName: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (t: PackageManager.NameNotFoundException) {
        null
    } catch (t: Throwable) {
        null
    }
}
