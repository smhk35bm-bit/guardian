package com.guardian.parent.service

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import com.guardian.parent.core.CollectorIds
import com.guardian.parent.core.Config
import com.guardian.parent.core.Logger
import com.guardian.parent.core.Notifications
import com.guardian.parent.core.Permissions
import com.guardian.parent.data.CollectedItem
import com.guardian.parent.data.LocalStore
import com.guardian.parent.data.RemoteConfig
import com.guardian.parent.data.Repo
import com.guardian.parent.net.DeviceSocket
import com.guardian.parent.service.live.LiveStreamManager
import com.guardian.parent.service.capture.ScreenCaptureSource
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
import org.json.JSONObject
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

    /** The persistent control channel to the server and the live session. */
    private var deviceSocket: DeviceSocket? = null
    private lateinit var liveManager: LiveStreamManager

    /**
     * Set when the parent asked for the screen while no consent was active.
     *
     * Android shows its consent dialog only for a foreground activity, so the
     * service cannot ask by itself: it posts a notification that opens
     * [com.guardian.parent.ui.PermissionActivity] with one tap. When the
     * consent arrives the stream is resumed, so the parent does not have to
     * press "start" again.
     */
    @Volatile
    private var pendingScreenLive = false

    /** When the pending request was made, so it does not resume days later. */
    @Volatile
    private var pendingScreenLiveAt = 0L

    /** Whether the dynamic screen on/unlock receiver is registered. */
    private var screenReceiverRegistered = false

    /**
     * The foreground service types that were requested at runtime.
     *
     * Android 14 refuses the camera and the screen capture while the service
     * runs without the matching type, so the types are added the moment the
     * corresponding feature is used and never taken away again (except when
     * the permission behind them is revoked).
     */
    @Volatile
    private var extraTypes: Int = 0

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
        instance = this
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
        liveManager = LiveStreamManager(this) { source, code ->
            sendLiveState("error", source, code)
        }

        Notifications.ensureChannel(this)
        promoteToForeground()
        MediaProjectionHolder.addListener(projectionListener)
        registerScreenStateReceiver()
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
        ensureRealtime()

        when (intent?.action) {
            ACTION_COLLECT_NOW -> {
                // "Send now" button in the UI: run every enabled collector at once.
                scope.launch { runAllCollectors() }
            }

            ACTION_ATTACH_PROJECTION -> {
                // The user granted the screen capture consent: the projection
                // may now be created by the service itself.
                scope.launch { attachProjection() }
            }
        }

        UploadWorker.schedule(this)
        return START_STICKY
    }

    override fun onDestroy() {
        Logger.i("Guardian monitoring service stopped")
        isRunning = false
        instance = null
        MediaProjectionHolder.removeListener(projectionListener)
        unregisterScreenStateReceiver()
        try {
            liveManager.stop()
        } catch (t: Throwable) {
            Logger.w("Unable to stop the live stream", t)
        }
        deviceSocket?.close()
        deviceSocket = null
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Live channel (WebSocket)
    // ------------------------------------------------------------------

    /**
     * Opens the control socket once and wires the live manager to it.
     *
     * The socket is the only always-on connection of the app: the parent can
     * ask for the screen or the camera at any moment without waiting for the
     * next periodic upload.
     */
    private fun ensureRealtime() {
        if (deviceSocket != null) return
        val socket = DeviceSocket(store, ::onControlMessage)
        liveManager.setSink { source, jpeg -> socket.sendFrame(source, jpeg) }
        deviceSocket = socket
        socket.connect()
    }

    /**
     * Handles the commands the parent sends from the dashboard.
     *
     * A live session always answers with a `live.state` message, so the
     * dashboard can show *why* a request failed (no consent yet, camera busy…)
     * instead of silently waiting.
     */
    private fun onControlMessage(message: JSONObject) {
        when (message.optString("type")) {
            "live.start" -> {
                val source = message.optString("source", "screen")
                scope.launch {
                    val error = try {
                        if (source == "camera") {
                            liveManager.startCamera()
                        } else {
                            liveManager.startScreen()
                        }
                    } catch (t: Throwable) {
                        Logger.e("Unable to start the live stream", t)
                        "INTERNAL"
                    }
                    if (error == null) {
                        pendingScreenLive = false
                        Notifications.cancelConsent(this@MonitorService)
                        sendLiveState("started", source, null)
                    } else {
                        if (error == "NO_CONSENT") {
                            // Remember the request: as soon as the user grants
                            // the consent from the notification, the stream
                            // starts without another command from the parent.
                            pendingScreenLive = true
                            pendingScreenLiveAt = System.currentTimeMillis()
                            Notifications.notifyConsentNeeded(this@MonitorService)
                        }
                        sendLiveState("error", source, error)
                    }
                }
            }

            "live.stop" -> {
                pendingScreenLive = false
                if (MediaProjectionHolder.isActive()) {
                    Notifications.cancelConsent(this)
                }
                liveManager.stop()
                sendLiveState("stopped", "", null)
            }

            "ping" -> {
                deviceSocket?.sendText(JSONObject().put("type", "pong"))
            }
        }
    }

    private fun sendLiveState(state: String, source: String, error: String?) {
        val message = JSONObject()
            .put("type", "live.state")
            .put("state", state)
        if (source.isNotEmpty()) message.put("source", source)
        if (error != null) message.put("error", error)
        deviceSocket?.sendText(message)
    }

    // ------------------------------------------------------------------
    // Screen capture consent
    // ------------------------------------------------------------------

    /**
     * `true` when the system consent for the screen capture is missing.
     *
     * The permanent notification then carries a "grant" action, so the user
     * can renew it with one tap after a reboot or after the system revoked the
     * session. There is no way to grant it automatically: Android always shows
     * its own dialog and the user must confirm it.
     */
    private fun needsScreenConsent(): Boolean {
        if (MediaProjectionHolder.isActive()) return false
        return pendingScreenLive || store.collectorEnabled(CollectorIds.SCREENSHOT)
    }

    /** Follows the consent: resumes a pending stream or reminds the user. */
    private val projectionListener: (Boolean) -> Unit = { active ->
        try {
            onProjectionStateChanged(active)
        } catch (t: Throwable) {
            Logger.w("Unable to handle the screen capture state change", t)
        }
    }

    private fun onProjectionStateChanged(active: Boolean) {
        if (active) {
            Notifications.cancelConsent(this)
            promoteToForeground()
            if (pendingScreenLive) {
                scope.launch { resumePendingScreenLive() }
            }
            return
        }

        // The consent is gone (system revoke, expired session, process was
        // restarted). The virtual display is bound to the dead projection, so
        // it has to be dropped; the next consent creates a fresh one.
        val screenWasLive = liveManager.activeSource() == LiveStreamManager.SOURCE_SCREEN
        if (screenWasLive) {
            pendingScreenLive = true
            pendingScreenLiveAt = System.currentTimeMillis()
            liveManager.stop()
        }
        ScreenCaptureSource.stop()
        promoteToForeground()
        if (pendingScreenLive) {
            Notifications.notifyConsentNeeded(this)
        }
    }

    /** Starts the screen stream the parent asked for before the consent arrived. */
    private fun resumePendingScreenLive() {
        if (!pendingScreenLive) return
        if (System.currentTimeMillis() - pendingScreenLiveAt > PENDING_LIVE_TTL_MS) {
            Logger.i("The pending live screen request expired")
            pendingScreenLive = false
            return
        }
        val error = try {
            liveManager.startScreen()
        } catch (t: Throwable) {
            Logger.e("Unable to resume the live screen stream", t)
            "INTERNAL"
        }
        if (error == null) {
            pendingScreenLive = false
            Notifications.cancelConsent(this)
            sendLiveState("started", "screen", null)
        } else {
            sendLiveState("error", "screen", error)
            if (error == "NO_CONSENT") {
                Notifications.notifyConsentNeeded(this)
            }
        }
    }

    /** Registers the screen on/unlock listener that re-offers the consent. */
    private fun registerScreenStateReceiver() {
        if (screenReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(
                this,
                screenStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenReceiverRegistered = true
        } catch (t: Throwable) {
            Logger.w("Unable to register the screen state receiver", t)
        }
    }

    private fun unregisterScreenStateReceiver() {
        if (!screenReceiverRegistered) return
        screenReceiverRegistered = false
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (t: Throwable) {
            Logger.w("Unable to unregister the screen state receiver", t)
        }
    }

    /**
     * After an unlock the session may have been revoked while the device was
     * idle; offering the one-tap consent again avoids a silent black stream.
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (!store.isEnrolled) return
                if (!needsScreenConsent()) return
                Notifications.notifyConsentNeeded(this@MonitorService)
            } catch (t: Throwable) {
                Logger.w("Unable to handle the screen state broadcast", t)
            }
        }
    }

    /**
     * Creates the screen capture projection out of the stored consent.
     *
     * Since Android 14 the projection may only be created while a foreground
     * service of the type `mediaProjection` is running, therefore this code
     * lives in the service and not in the Activity.
     */
    private suspend fun attachProjection() {
        try {
            val consent = MediaProjectionHolder.takeConsent()
            if (consent == null) {
                Logger.w("No screen capture consent is waiting")
                return
            }
            val (resultCode, data) = consent

            // The type has to be active before the projection is created.
            addForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

            val projection = createProjection(resultCode, data)
            if (projection == null) {
                Logger.e("Unable to create the screen capture projection")
                return
            }

            // Android 14 requires the callback before a virtual display may be
            // created, so it is registered first.
            MediaProjectionHolder.registerCallback(projection)
            MediaProjectionHolder.attach(projection)
        } catch (t: Throwable) {
            Logger.e("Unable to start the screen capture", t)
        }
    }

    /**
     * Builds the [MediaProjection] out of the consent result.
     *
     * Android 14 introduced [MediaProjectionManager.getMediaProjection]; before
     * that the projection was handed over as a parcelable extra of the result
     * intent. Both paths are implemented so the app works on every supported
     * version.
     */
    private fun createProjection(resultCode: Int, data: Intent): MediaProjection? {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.getMediaProjection(resultCode, data)
            } else {
                IntentCompat.getParcelableExtra(
                    data,
                    EXTRA_MEDIA_PROJECTION,
                    MediaProjection::class.java
                )
            }
        } catch (t: Throwable) {
            Logger.e("Unable to read the media projection from the consent result", t)
            null
        }
    }

    // ------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------

    private fun promoteToForeground() {
        val notification = Notifications.build(
            this,
            store.deviceName.ifBlank { deviceLabel() },
            needsScreenConsent()
        )
        startForegroundWith(currentTypes(), notification)
    }

    /**
     * The types the service currently runs with.
     *
     * The data sync type is always needed (the uploads), the location type is
     * only valid while the location permission is granted and the camera and
     * the media projection types are only added while they are really used.
     */
    private fun currentTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (Permissions.locationGranted(this)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (extraTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0 &&
            Permissions.cameraGranted(this)
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (extraTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        return types
    }

    /** Adds a foreground service type and refreshes the notification. */
    private fun addForegroundType(type: Int) {
        if (extraTypes and type == type) return
        extraTypes = extraTypes or type
        promoteToForeground()
    }

    /**
     * Starts (or updates) the foreground state of the service.
     *
     * Android 14 refuses a type that the app is not allowed to use, therefore
     * the call falls back to the plain data sync type.
     */
    private fun startForegroundWith(types: Int, notification: Notification) {
        try {
            ServiceCompat.startForeground(this, Config.NOTIFICATION_ID, notification, types)
        } catch (e: Exception) {
            Logger.w("Unable to start the foreground service with the chosen type", e)
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

            // A live session owns the camera (or the screen) until it stops.
            if (liveManager.isStreaming()) {
                if (id == CollectorIds.CAMERA &&
                    liveManager.activeSource() == LiveStreamManager.SOURCE_CAMERA
                ) {
                    continue
                }
                if (id == CollectorIds.SCREENSHOT &&
                    liveManager.activeSource() == LiveStreamManager.SOURCE_SCREEN
                ) {
                    continue
                }
            }

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

        /** Asks the service to turn the stored consent into a projection. */
        const val ACTION_ATTACH_PROJECTION = "com.guardian.parent.action.ATTACH_PROJECTION"

        private const val TICK_MS = 5_000L

        /** How long a "start the screen" request waits for the consent. */
        private const val PENDING_LIVE_TTL_MS = 10 * 60_000L

        /**
         * The extra the platform used before Android 14 to hand the projection
         * over. It is a compile time constant, so it is safe to inline it for
         * the older releases.
         */
        private const val EXTRA_MEDIA_PROJECTION = "android.media.projection.extra.MEDIA_PROJECTION"

        /** Lets the UI show whether the monitoring service is currently alive. */
        @Volatile
        var isRunning: Boolean = false
            internal set

        /** The running service, used to update the foreground service types. */
        @Volatile
        private var instance: MonitorService? = null

        /**
         * Adds a foreground service type to the running service.
         *
         * Android 14 refuses to open the camera or to capture the screen
         * while the service runs without the matching type. The call is a
         * no-op when the service is not running.
         */
        fun ensureForegroundType(type: Int) {
            val service = instance ?: return
            service.addForegroundType(type)
        }

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

        /**
         * Starts the service so that it can turn the stored screen capture
         * consent into a real projection.
         */
        fun startWithProjection(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
                .setAction(ACTION_ATTACH_PROJECTION)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Logger.e("Unable to start the monitoring service for the screen capture", t)
            }
        }
    }
}
