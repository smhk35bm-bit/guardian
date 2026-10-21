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
}

/** Stable identifiers of every collector; they double as preference keys. */
object CollectorIds {
    const val LOCATION = "location"
    const val USAGE = "usage"
    const val APPS = "apps"
    const val CALLS = "calls"
    const val SMS = "sms"
    const val SCREENSHOT = "screenshot"
    const val CAMERA = "camera"
    const val HEARTBEAT = "heartbeat"
}
