package com.guardian.parent.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.guardian.parent.core.CollectorIds
import com.guardian.parent.core.Config
import com.guardian.parent.core.Logger
import com.guardian.parent.core.Notifications
import com.guardian.parent.data.CollectedItem
import com.guardian.parent.data.LocalStore
import com.guardian.parent.data.RemoteConfig
import com.guardian.parent.data.Repo
import com.guardian.parent.service.collectors.CallLogCollector
import com.guardian.parent.service.collectors.CameraCollector
import com.guardian.parent.service.collectors.HeartbeatCollector
import com.guardian.parent.service.collectors.InstalledAppsCollector
import com.guardian.parent.service.collectors.LocationCollector
import com.guardian.parent.service.collectors.ScreenshotCollector
import com.guardian.parent.service.collectors.SmsCollector
import com.guardian.parent.service.collectors.UsageCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The always running foreground service that performs the monitoring.
 *
 * Design notes:
 *  - The service owns a permanent, non dismissable notification. It is the
 *    product's transparency guarantee: as long as the service runs, the shield
 *    icon and the "monitoring is active" text are visible in the status bar.
 *  - The service restarts itself (`START_STICKY`) and is restarted on boot by
 *    [BootReceiver].
 *  - Every collector runs in its own coroutine; a failing collector can never
 *    stop the others or the service.
 */
class MonitorService : LifecycleService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val uploadLock = Mutex()

    private lateinit var store: LocalStore
    private lateinit var repo: Repo

    private val lastRunAt: MutableMap<String, Long> = ConcurrentHashMap()
    private val running: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var schedulerJob: Job? = null

    private lateinit var locationCollector: LocationCollector
    private lateinit var usageCollector: UsageCollector
    private lateinit var appsCollector: InstalledAppsCollector
    private lateinit var callsCollector: CallLogCollector
    private lateinit var smsCollector: SmsCollector
    private lateinit var screenshotCollector: ScreenshotCollector
    private lateinit var cameraCollector: CameraCollector
    private lateinit var heartbeatCollector: HeartbeatCollector

    override fun onCreate() {
        super.onCreate()
        store = LocalStore(this)
        repo = Repo(this)

        locationCollector = LocationCollector(this, highAccuracy = true)
        usageCollector = UsageCollector(this)
        appsCollector = InstalledAppsCollector(this)
        callsCollector = CallLogCollector(this)
        smsCollector = SmsCollector(this)
        screenshotCollector = ScreenshotCollector(this)
        cameraCollector = CameraCollector(this)
        heartbeatCollector = HeartbeatCollector(this)

        Notifications.ensureChannel(this)
        promoteToForeground()
        isRunning = true
        Logger.i("Guardian monitoring service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (!store.isEnrolled) {
            Logger.w("Service started but the device is not paired yet, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        promoteToForeground()
        startScheduler()

        if (intent?.action == ACTION_COLLECT_NOW) {
            // "Send now" button in the UI: run every enabled collector at once.
            scope.launch { runAllCollectors() }
        }

        UploadWorker.schedule(this)
        return START_STICKY
    }

    override fun onDestroy() {
        Logger.i("Guardian monitoring service stopped")
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------

    private fun promoteToForeground() {
        val notification = Notifications.build(this, store.deviceName.ifBlank { deviceLabel() })
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try {
            ServiceCompat.startForeground(this, Config.NOTIFICATION_ID, notification, types)
        } catch (e: Exception) {
            // On Android 14 the location type requires the location permission
            // to be granted; fall back to the data sync type which always works.
            Logger.w("Unable to start the foreground service with the location type", e)
            try {
                ServiceCompat.startForeground(
                    this,
                    Config.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } catch (e2: Exception) {
                Logger.e("Unable to start the foreground service", e2)
                stopSelf()
            }
        }
    }

    private fun deviceLabel(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    // ------------------------------------------------------------------
    // Scheduler
    // ------------------------------------------------------------------

    private fun startScheduler() {
        if (schedulerJob?.isActive == true) return
        schedulerJob = scope.launch {
            while (isActive) {
                try {
                    runDueCollectors()
                } catch (t: Throwable) {
                    Logger.e("Scheduler tick failed", t)
                }
                delay(TICK_MS)
            }
        }
    }

    /** All collectors together with the id used for their settings. */
    private fun collectorPlan(): List<Pair<String, suspend () -> List<CollectedItem>>> = listOf(
        CollectorIds.HEARTBEAT to { heartbeatCollector.collect() },
        CollectorIds.LOCATION to { locationCollector.collect() },
        CollectorIds.USAGE to { usageCollector.collect() },
        CollectorIds.APPS to { appsCollector.collect() },
        CollectorIds.CALLS to { callsCollector.collect() },
        CollectorIds.SMS to { smsCollector.collect() },
        CollectorIds.SCREENSHOT to { screenshotCollector.collect() },
        CollectorIds.CAMERA to { cameraCollector.collect() }
    )

    /**
     * Runs every collector whose interval elapsed.
     *
     * The intervals are read from the store on every tick so that a change in
     * the settings is applied within a few seconds.
     */
    private suspend fun runDueCollectors() {
        val now = System.currentTimeMillis()
        for ((id, collect) in collectorPlan()) {
            if (!store.collectorEnabled(id)) continue
            val intervalMs = store.collectorInterval(id) * 1000L
            val last = lastRunAt[id] ?: 0L
            if (now - last < intervalMs) continue
            if (!running.add(id)) continue
            lastRunAt[id] = now
            scope.launch {
                try {
                    deliver(collect())
                } catch (t: Throwable) {
                    Logger.e("Collector $id failed", t)
                } finally {
                    running.remove(id)
                }
            }
        }
    }

    /** Runs every enabled collector right now, ignoring the intervals. */
    private suspend fun runAllCollectors() {
        for ((id, collect) in collectorPlan()) {
            if (!store.collectorEnabled(id)) continue
            val items = try {
                collect()
            } catch (t: Throwable) {
                Logger.e("Collector $id failed", t)
                emptyList()
            }
            deliver(items)
        }
        Logger.i("Manual collection finished")
    }

    /**
     * Hands the collected items over to the repository.
     *
     * The heartbeat answer may carry a configuration pushed by the parent
     * (intervals), which is applied immediately.
     */
    private suspend fun deliver(items: List<CollectedItem>) {
        if (items.isEmpty()) return
        uploadLock.withLock {
            try {
                val result = repo.submit(items)
                result.remoteConfig?.let { applyRemoteConfig(it) }
                if (result.authFailed) {
                    Logger.w("The server rejected the device token, the device has to be paired again")
                }
            } catch (t: Throwable) {
                Logger.e("Unable to deliver the collected data", t)
                store.lastError = t.message
            }
        }
    }

    /** Applies the intervals the parent configured in the dashboard. */
    private fun applyRemoteConfig(config: RemoteConfig) {
        config.locationIntervalSec?.let {
            store.setCollectorInterval(CollectorIds.LOCATION, it)
            Logger.i("Remote location interval: $it s")
        }
        config.usageIntervalSec?.let {
            store.setCollectorInterval(CollectorIds.USAGE, it)
            Logger.i("Remote usage interval: $it s")
        }
    }

    companion object {
        const val ACTION_COLLECT_NOW = "com.guardian.parent.action.COLLECT_NOW"

        private const val TICK_MS = 5_000L

        /** Lets the UI show whether the monitoring service is currently alive. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Starts (or wakes up) the monitoring service. */
        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Logger.e("Unable to start the monitoring service", t)
            }
        }
    }
}
