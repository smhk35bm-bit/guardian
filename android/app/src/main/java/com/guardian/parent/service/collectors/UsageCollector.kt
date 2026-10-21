package com.guardian.parent.service.collectors

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.guardian.parent.core.CollectorIds
import com.guardian.parent.core.Config
import com.guardian.parent.core.Logger
import com.guardian.parent.core.TimeFmt
import com.guardian.parent.data.CollectedItem
import com.guardian.parent.data.ItemKind
import com.guardian.parent.data.UsageEventPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reports how long each application was in the foreground.
 *
 * Requires the special `PACKAGE_USAGE_STATS` permission which the user
 * grants from the "usage access" settings screen.
 */
class UsageCollector(private val context: Context) : Collector {

    override val id: String = CollectorIds.USAGE

    override suspend fun collect(): List<CollectedItem> = withContext(Dispatchers.IO) {
        try {
            if (!hasUsageAccess()) {
                Logger.d("Usage access is not granted, skipping the usage report")
                return@withContext emptyList()
            }
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return@withContext emptyList()

            val now = System.currentTimeMillis()
            val start = now - Config.USAGE_WINDOW_SEC * 1000L
            val events = manager.queryEvents(start, now)
                ?: return@withContext emptyList()

            val openSessions = HashMap<String, Long>()
            val collected = ArrayList<UsageEventPayload>()

            while (events.hasNextEvent()) {
                val event = UsageEvents.Event()
                if (!events.getNextEvent(event)) break
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        openSessions[event.packageName] = event.timeStamp
                    }

                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        val startedAt = openSessions.remove(event.packageName)
                        if (startedAt != null) {
                            collected += usageEvent(
                                event.packageName,
                                startedAt,
                                event.timeStamp - startedAt
                            )
                        }
                    }
                }
            }

            // Applications that are still in the foreground get an open session.
            openSessions.forEach { (packageName, startedAt) ->
                collected += usageEvent(packageName, startedAt, now - startedAt)
            }

            Logger.d("Collected ${collected.size} usage events")
            collected.map { CollectedItem(ItemKind.EVENT, payload = it.toJson()) }
        } catch (t: Throwable) {
            Logger.e("Usage collection failed", t)
            emptyList()
        }
    }

    /**
     * Returns the package/label of the application that is currently in the
     * foreground. Used to annotate the screenshots with the visible app.
     */
    fun foregroundAppNow(): Pair<String, String?>? {
        return try {
            if (!hasUsageAccess()) return null
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return null
            val now = System.currentTimeMillis()
            val events = manager.queryEvents(now - 5 * 60_000L, now) ?: return null
            var packageName: String? = null
            var lastSeen = 0L
            while (events.hasNextEvent()) {
                val event = UsageEvents.Event()
                if (!events.getNextEvent(event)) break
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                    event.timeStamp >= lastSeen
                ) {
                    lastSeen = event.timeStamp
                    packageName = event.packageName
                }
            }
            packageName?.let { it to labelFor(it) }
        } catch (t: Throwable) {
            Logger.w("Unable to resolve the foreground application", t)
            null
        }
    }

    private fun usageEvent(packageName: String, startedAt: Long, durationMs: Long): UsageEventPayload =
        UsageEventPayload(
            type = "app_usage",
            packageName = packageName,
            label = labelFor(packageName),
            durationMs = durationMs.coerceAtLeast(0L),
            openedAt = startedAt,
            foreground = true
        )

    private fun labelFor(packageName: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (t: Throwable) {
        null
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        return try {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            Logger.w("Unable to check the usage access", t)
            false
        }
    }

    /** Exposed for the UI so the status card can show the last usage event. */
    fun describeLastEvent(): String? {
        val app = foregroundAppNow() ?: return null
        return "${app.second ?: app.first} @ ${TimeFmt.human(System.currentTimeMillis())}"
    }
}
