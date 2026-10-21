package com.guardian.parent.data

import com.guardian.parent.core.jsonObject
import org.json.JSONObject

/** Static description of this phone, sent during registration. */
data class DeviceIdentity(
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val appVersion: String
)

/** Successful response of `POST /api/devices/register`. */
data class EnrollmentResult(
    val deviceId: Long,
    val deviceToken: String,
    val serverUrl: String?
)

/** Configuration the parent may push from the dashboard. */
data class RemoteConfig(
    val locationIntervalSec: Long? = null,
    val usageIntervalSec: Long? = null
)

data class HeartbeatPayload(
    val battery: Int,
    val charging: Boolean,
    val wifi: String?,
    val networkType: String,
    val storageFreeMb: Long,
    val uptimeSeconds: Long,
    val screenOn: Boolean,
    val appVersion: String
) {
    fun toJson(): JSONObject = jsonObject {
        put("battery", battery)
        put("charging", charging)
        put("wifi", wifi ?: JSONObject.NULL)
        put("networkType", networkType)
        put("storageFreeMb", storageFreeMb)
        put("uptimeSeconds", uptimeSeconds)
        put("screenOn", screenOn)
        put("appVersion", appVersion)
    }
}

data class LocationPayload(
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val speed: Float,
    val altitude: Double,
    val bearing: Float,
    val provider: String,
    val battery: Int,
    val at: Long
) {
    fun toJson(): JSONObject = jsonObject {
        put("lat", lat)
        put("lng", lng)
        put("accuracy", accuracy)
        put("speed", speed)
        put("altitude", altitude)
        put("bearing", bearing)
        put("provider", provider)
        put("battery", battery)
        put("at", at)
    }
}

data class UsageEventPayload(
    val type: String,
    val packageName: String,
    val label: String?,
    val durationMs: Long,
    val openedAt: Long,
    val foreground: Boolean
) {
    fun toJson(): JSONObject = jsonObject {
        put("type", type)
        put("package", packageName)
        put("label", label ?: JSONObject.NULL)
        put("durationMs", durationMs)
        put("openedAt", openedAt)
        put("foreground", foreground)
    }
}

data class CallPayload(
    val number: String?,
    val contactName: String?,
    val type: String,
    val durationSec: Long,
    val at: Long
) {
    fun toJson(): JSONObject = jsonObject {
        put("number", number ?: JSONObject.NULL)
        put("contactName", contactName ?: JSONObject.NULL)
        put("type", type)
        put("durationSec", durationSec)
        put("at", at)
    }
}

data class SmsPayload(
    val address: String?,
    val body: String?,
    val type: String,
    val at: Long
) {
    fun toJson(): JSONObject = jsonObject {
        put("address", address ?: JSONObject.NULL)
        put("body", body ?: JSONObject.NULL)
        put("type", type)
        put("at", at)
    }
}

data class AppInfoPayload(
    val packageName: String,
    val label: String?,
    val version: String?,
    val system: Boolean,
    val installedAt: Long
) {
    fun toJson(): JSONObject = jsonObject {
        put("package", packageName)
        put("label", label ?: JSONObject.NULL)
        put("version", version ?: JSONObject.NULL)
        put("system", system)
        put("installedAt", installedAt)
    }
}

/** The device endpoints of the API contract, one per data kind. */
enum class ItemKind(val path: String) {
    HEARTBEAT("heartbeat"),
    LOCATION("location"),
    EVENT("events"),
    CALL("calls"),
    SMS("sms"),
    APPS("apps"),
    SCREENSHOT("screenshots"),
    PHOTO("photos")
}

/**
 * The result of a single collector run: everything that is ready to be sent
 * to the server, already shaped like the contract expects it.
 */
class CollectedItem(
    val kind: ItemKind,
    val payload: JSONObject? = null,
    val bytes: ByteArray? = null,
    val mimeType: String? = null,
    val meta: JSONObject? = null
)

/**
 * A unit of work waiting to be uploaded.
 *
 * `payload` holds the JSON body of the request; `mediaPath` points at a file
 * on disk for the two multipart endpoints (screenshots / camera photos).
 */
data class PendingItem(
    val kind: String,
    val payload: JSONObject? = null,
    val mediaPath: String? = null,
    val mediaMime: String? = null,
    val meta: JSONObject? = null
)

/** Outcome of an upload round. */
data class PushResult(
    val sent: Int = 0,
    val failed: Int = 0,
    val dropped: Int = 0,
    val authFailed: Boolean = false,
    val lastError: String? = null,
    val remoteConfig: RemoteConfig? = null
) {
    val hasWork: Boolean get() = failed > 0 || authFailed
}
