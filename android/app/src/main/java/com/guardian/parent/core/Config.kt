package com.guardian.parent.core

/**
 * Central place for the constants that are shared by the service, the
 * collectors and the UI.
 *
 * The defaults are intentionally conservative: a misconfigured device must
 * never flood the parent's VPS with requests.
 */
object Config {

    const val TAG = "Guardian"

    // ---- Notification -------------------------------------------------
    const val NOTIFICATION_CHANNEL_ID = "guardian_monitoring"
    const val NOTIFICATION_ID = 0x4755 // "GU", stable across service restarts

    // ---- WorkManager --------------------------------------------------
    const val UPLOAD_WORK_NAME = "guardian_periodic_upload"

    // ---- Collector cadence (seconds) -----------------------------------
    const val DEFAULT_LOCATION_INTERVAL_SEC = 60L
    const val MIN_LOCATION_INTERVAL_SEC = 15L
    const val DEFAULT_USAGE_INTERVAL_SEC = 300L
    const val DEFAULT_CALL_LOG_INTERVAL_SEC = 300L
    const val DEFAULT_SMS_INTERVAL_SEC = 300L
    const val DEFAULT_APPS_INTERVAL_SEC = 3600L
    const val DEFAULT_SCREENSHOT_INTERVAL_SEC = 300L
    const val DEFAULT_CAMERA_INTERVAL_SEC = 600L
    const val DEFAULT_HEARTBEAT_INTERVAL_SEC = 120L
    const val DEFAULT_CONTACTS_INTERVAL_SEC = 21600L
    const val DEFAULT_NOTIFICATIONS_INTERVAL_SEC = 60L

    // ---- Windows used when reading the system databases -----------------
    const val USAGE_WINDOW_SEC = 300L
    const val CALL_LOG_WINDOW_SEC = 3600L
    const val SMS_WINDOW_SEC = 3600L

    // ---- Local retry queue ---------------------------------------------
    const val MAX_QUEUE_ITEMS = 200
    const val MAX_MEDIA_BYTES = 4 * 1024 * 1024

    // ---- Networking ----------------------------------------------------
    const val CONNECT_TIMEOUT_SEC = 15L
    const val READ_TIMEOUT_SEC = 30L
    const val WRITE_TIMEOUT_SEC = 60L

    // ---- Live stream over the mobile network ---------------------------
    /**
     * The live view is a Motion-JPEG stream sent over the WebSocket. On a 4G
     * link the useful compromise is a small frame (at most [LIVE_MAX_WIDTH]
     * pixels wide) at [LIVE_FRAME_INTERVAL_MS]. The device content decides the
     * real size: a still screen produces no frame at all, a scrolling list
     * costs roughly 25–40 kB per frame (≈ 0.2–0.4 MB/s at 6 fps).
     */
    const val LIVE_MAX_WIDTH = 640
    const val LIVE_FRAME_INTERVAL_MS = 200L
    const val LIVE_JPEG_QUALITY = 45
    const val LIVE_SCREENSHOT_QUALITY = 82

    // ---- Live audio ----------------------------------------------------
    const val LIVE_AUDIO_SAMPLE_RATE = 16000
    const val LIVE_AUDIO_CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO
    const val LIVE_AUDIO_ENCODING = android.media.AudioFormat.ENCODING_PCM_16BIT
    const val LIVE_AUDIO_BUFFER_MS = 40L
    const val LIVE_AUDIO_SOURCE = android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION

    /**
     * A mobile uplink is the real limit of the live view: a 4G cell often
     * uploads less than 1 Mbit/s. When the socket still has this many bytes
     * waiting the next frame is dropped instead of queued, so the parent sees
     * a slightly choppy but *current* picture instead of a growing delay.
     */
    const val LIVE_MAX_SEND_QUEUE_BYTES = 96L * 1024L

    /**
     * The shared virtual display is created at this width so a screenshot keeps
     * its detail; live frames are downscaled to [LIVE_MAX_WIDTH] right before
     * they are encoded.
     */
    const val SCREENSHOT_MAX_WIDTH = 1080

    /** Reconnect backoff of the device socket. */
    const val LIVE_RECONNECT_MIN_MS = 2_000L
    const val LIVE_RECONNECT_MAX_MS = 60_000L
}

/** Stable identifiers of every collector; they double as preference keys. */
object CollectorIds {
    const val LOCATION = "location"
    const val USAGE = "usage"
    const val APPS = "apps"
    const val CALLS = "calls"
    const val SMS = "sms"
    const val CONTACTS = "contacts"
    const val NOTIFICATIONS = "notifications"
    const val SCREENSHOT = "screenshot"
    const val CAMERA = "camera"
    const val HEARTBEAT = "heartbeat"
}
