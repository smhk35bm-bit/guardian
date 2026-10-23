package com.guardian.parent.data

import android.content.Context
import android.content.SharedPreferences
import com.guardian.parent.core.CollectorIds
import com.guardian.parent.core.Config
import com.guardian.parent.core.PermissionState
import com.guardian.parent.core.jsonObject
import com.guardian.parent.core.optStringOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin, typed wrapper around the app's [SharedPreferences].
 *
 * Everything the device needs to survive a reboot lives here: the server URL,
 * the device token, the collector switches and the local retry queue.
 */
class LocalStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // Enrollment
    // ------------------------------------------------------------------

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var deviceId: Long
        get() = prefs.getLong(KEY_DEVICE_ID, 0L)
        set(value) = prefs.edit().putLong(KEY_DEVICE_ID, value).apply()

    var deviceToken: String?
        get() = prefs.getString(KEY_DEVICE_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    val isEnrolled: Boolean
        get() = deviceId > 0L && !deviceToken.isNullOrBlank()

    /** Persists the result of a successful registration. */
    fun saveEnrollment(result: EnrollmentResult, fallbackUrl: String) {
        prefs.edit()
            .putLong(KEY_DEVICE_ID, result.deviceId)
            .putString(KEY_DEVICE_TOKEN, result.deviceToken)
            .putString(KEY_SERVER_URL, result.serverUrl?.takeIf { it.isNotBlank() } ?: fallbackUrl)
            .apply()
    }

    /** Wipes the enrollment so the device has to be paired again. */
    fun clearEnrollment() {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_SERVER_URL)
            .apply()
    }

    // ------------------------------------------------------------------
    // Collector switches and intervals
    // ------------------------------------------------------------------

    fun collectorEnabled(id: String): Boolean =
        prefs.getBoolean(KEY_ENABLED_PREFIX + id, defaultEnabled(id))

    fun setCollectorEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED_PREFIX + id, enabled).apply()
    }

    fun collectorInterval(id: String): Long =
        prefs.getLong(KEY_INTERVAL_PREFIX + id, defaultInterval(id)).coerceAtLeast(minInterval(id))

    fun setCollectorInterval(id: String, seconds: Long) {
        prefs.edit()
            .putLong(KEY_INTERVAL_PREFIX + id, seconds.coerceAtLeast(minInterval(id)))
            .apply()
    }

    // ------------------------------------------------------------------
    // Permission snapshot
    // ------------------------------------------------------------------

    var permissionState: PermissionState
        get() {
            val raw = prefs.getString(KEY_PERMISSIONS, null) ?: return PermissionState()
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return PermissionState()
            return PermissionState(
                notifications = obj.optBoolean("notifications", false),
                location = obj.optBoolean("location", false),
                backgroundLocation = obj.optBoolean("backgroundLocation", false),
                usageStats = obj.optBoolean("usageStats", false),
                callLog = obj.optBoolean("callLog", false),
                sms = obj.optBoolean("sms", false),
                contacts = obj.optBoolean("contacts", false),
                camera = obj.optBoolean("camera", false),
                microphone = obj.optBoolean("microphone", false),
                notificationAccess = obj.optBoolean("notificationAccess", false),
                screenCapture = obj.optBoolean("screenCapture", false)
            )
        }
        set(value) {
            prefs.edit().putString(KEY_PERMISSIONS, value.toJson().toString()).apply()
        }

    // ------------------------------------------------------------------
    // Runtime status
    // ------------------------------------------------------------------

    var lastUploadAt: Long
        get() = prefs.getLong(KEY_LAST_UPLOAD, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPLOAD, value).apply()

    var lastError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(value) = prefs.edit().putString(KEY_LAST_ERROR, value).apply()

    var lastAuthError: Boolean
        get() = prefs.getBoolean(KEY_LAST_AUTH_ERROR, false)
        set(value) = prefs.edit().putBoolean(KEY_LAST_AUTH_ERROR, value).apply()

    // ------------------------------------------------------------------
    // Local retry queue
    // ------------------------------------------------------------------

    fun loadQueue(): List<PendingItem> {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val items = ArrayList<PendingItem>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val kind = obj.optStringOrNull("kind") ?: continue
            items += PendingItem(
                kind = kind,
                payload = obj.optJSONObject("payload"),
                mediaPath = obj.optStringOrNull("mediaPath"),
                mediaMime = obj.optStringOrNull("mediaMime"),
                meta = obj.optJSONObject("meta")
            )
        }
        return items
    }

    fun saveQueue(items: List<PendingItem>) {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_QUEUE, array.toString()).apply()
    }

    fun clearQueue() {
        prefs.edit().remove(KEY_QUEUE).apply()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun defaultEnabled(id: String): Boolean = when (id) {
        // Camera and screen capture need an explicit, per-session consent
        // from the user, therefore they stay off until the parent turns them on.
        CollectorIds.CAMERA, CollectorIds.SCREENSHOT -> false
        else -> true
    }

    private fun defaultInterval(id: String): Long = when (id) {
        CollectorIds.LOCATION -> Config.DEFAULT_LOCATION_INTERVAL_SEC
        CollectorIds.USAGE -> Config.DEFAULT_USAGE_INTERVAL_SEC
        CollectorIds.APPS -> Config.DEFAULT_APPS_INTERVAL_SEC
        CollectorIds.CALLS -> Config.DEFAULT_CALL_LOG_INTERVAL_SEC
        CollectorIds.SMS -> Config.DEFAULT_SMS_INTERVAL_SEC
        CollectorIds.CONTACTS -> Config.DEFAULT_CONTACTS_INTERVAL_SEC
        CollectorIds.NOTIFICATIONS -> Config.DEFAULT_NOTIFICATIONS_INTERVAL_SEC
        CollectorIds.SCREENSHOT -> Config.DEFAULT_SCREENSHOT_INTERVAL_SEC
        CollectorIds.CAMERA -> Config.DEFAULT_CAMERA_INTERVAL_SEC
        CollectorIds.HEARTBEAT -> Config.DEFAULT_HEARTBEAT_INTERVAL_SEC
        else -> Config.DEFAULT_HEARTBEAT_INTERVAL_SEC
    }

    private fun minInterval(id: String): Long = when (id) {
        // The API contract allows 600 requests per minute, but a 15 second
        // floor keeps the battery drain sane and stays well below the limit.
        CollectorIds.LOCATION -> Config.MIN_LOCATION_INTERVAL_SEC
        else -> 60L
    }

    private fun PermissionState.toJson(): JSONObject = jsonObject {
        put("notifications", notifications)
        put("location", location)
        put("backgroundLocation", backgroundLocation)
        put("usageStats", usageStats)
        put("callLog", callLog)
        put("sms", sms)
        put("contacts", contacts)
        put("camera", camera)
        put("microphone", microphone)
        put("notificationAccess", notificationAccess)
        put("screenCapture", screenCapture)
    }

    private fun PendingItem.toJson(): JSONObject = jsonObject {
        put("kind", kind)
        put("payload", payload ?: JSONObject.NULL)
        put("mediaPath", mediaPath ?: JSONObject.NULL)
        put("mediaMime", mediaMime ?: JSONObject.NULL)
        put("meta", meta ?: JSONObject.NULL)
    }

    companion object {
        const val PREFS_NAME = "guardian_prefs"

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_PERMISSIONS = "permission_state"
        private const val KEY_LAST_UPLOAD = "last_upload_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_LAST_AUTH_ERROR = "last_auth_error"
        private const val KEY_QUEUE = "pending_queue"
        private const val KEY_ENABLED_PREFIX = "enabled_"
        private const val KEY_INTERVAL_PREFIX = "interval_"
    }
}
