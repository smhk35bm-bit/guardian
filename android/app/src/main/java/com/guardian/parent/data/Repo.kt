package com.guardian.parent.data

import android.content.Context
import com.guardian.parent.core.Config
import com.guardian.parent.core.Logger
import com.guardian.parent.core.optLongOrNull
import com.guardian.parent.core.optObjectOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Bridges the collectors and the API.
 *
 * Every collected item is first written to a small on-disk queue and only
 * removed once the server confirmed it. That way a phone without network
 * access does not lose any evidence: the next upload round simply retries.
 */
class Repo(context: Context) {

    private val appContext = context.applicationContext
    private val store = LocalStore(appContext)
    private val api = ApiClient(store)

    private val mediaDir: File by lazy {
        File(appContext.filesDir, "pending_media").apply { if (!exists()) mkdirs() }
    }

    // ------------------------------------------------------------------
    // Enrollment
    // ------------------------------------------------------------------

    suspend fun enroll(baseUrl: String, code: String, identity: DeviceIdentity): EnrollmentResult =
        api.register(baseUrl, code, identity)

    // ------------------------------------------------------------------
    // Uploading
    // ------------------------------------------------------------------

    /**
     * Queues [items] and immediately tries to deliver everything that is
     * still pending.
     */
    suspend fun submit(items: List<CollectedItem>): PushResult {
        if (items.isEmpty()) return flushQueue()
        val pending = store.loadQueue()
        val fresh = items.mapNotNull { persist(it) }
        val merged = (pending + fresh).takeLast(Config.MAX_QUEUE_ITEMS)
        // The queue is bounded: the oldest entries fall out of it. Their
        // media files are deleted, otherwise the storage would grow forever.
        val dropped = (pending + fresh).dropLast(Config.MAX_QUEUE_ITEMS)
        dropped.forEach { item -> item.mediaPath?.let { deleteQuietly(it) } }
        store.saveQueue(merged)
        return flushQueue()
    }

    /** Retries every queued item. Safe to call from the periodic worker. */
    suspend fun flushQueue(): PushResult {
        val queue = store.loadQueue()
        if (queue.isEmpty()) return PushResult()

        var sent = 0
        var failed = 0
        var dropped = 0
        var authFailed = false
        var lastError: String? = null
        var remoteConfig: RemoteConfig? = null
        val remaining = ArrayList<PendingItem>(queue.size)

        for (item in queue) {
            try {
                val response = send(item)
                sent++
                if (item.kind == ItemKind.HEARTBEAT.path) {
                    remoteConfig = parseRemoteConfig(response)
                }
            } catch (e: ApiException) {
                lastError = e.message
                if (e.isAuthError) {
                    // Keep the payload: after the device is paired again the
                    // very same data can still be delivered.
                    authFailed = true
                    failed++
                    remaining += item
                } else if (e.httpCode in 400..499) {
                    // The server will never accept this payload; retrying it
                    // would block the queue forever.
                    Logger.w("Dropping payload rejected by server (${e.code})")
                    dropped++
                } else {
                    failed++
                    remaining += item
                }
            } catch (e: NetworkException) {
                lastError = e.message
                failed++
                remaining += item
            } catch (e: Exception) {
                Logger.e("Unexpected upload failure", e)
                lastError = e.message
                failed++
                remaining += item
            }
        }

        store.saveQueue(remaining)
        if (sent > 0) store.lastUploadAt = System.currentTimeMillis()
        store.lastError = lastError
        store.lastAuthError = authFailed

        Logger.d("Upload round: sent=$sent failed=$failed dropped=$dropped authFailed=$authFailed")
        return PushResult(sent, failed, dropped, authFailed, lastError, remoteConfig)
    }

    /** Removes every queued payload (and its media file). */
    fun clearQueue() {
        store.loadQueue().forEach { item ->
            item.mediaPath?.let { path -> deleteQuietly(path) }
        }
        store.clearQueue()
        Logger.i("Local queue cleared")
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun send(item: PendingItem): JSONObject {
        val mediaPath = item.mediaPath
        if (mediaPath != null) {
            val file = File(mediaPath)
            if (!file.exists()) {
                Logger.w("Queued media file is gone, dropping it")
                return JSONObject()
            }
            val bytes = file.readBytes()
            val response = api.postDeviceMedia(
                path = item.kind,
                bytes = bytes,
                mimeType = item.mediaMime ?: "image/png",
                fileName = file.name,
                meta = item.meta
            )
            file.delete()
            return response
        }
        return api.postDeviceJson(item.kind, item.payload ?: JSONObject())
    }

    /**
     * Reads the optional configuration the parent pushed from the dashboard.
     *
     * The contract answers the heartbeat with
     * `{ "config": { "locationIntervalSec": 60, "usageIntervalSec": 300 } }`.
     */
    private fun parseRemoteConfig(response: JSONObject): RemoteConfig? {
        val config = response.optObjectOrNull("config") ?: return null
        return RemoteConfig(
            locationIntervalSec = config.optLongOrNull("locationIntervalSec"),
            usageIntervalSec = config.optLongOrNull("usageIntervalSec")
        )
    }

    /**
     * Converts a collected item into a durable queue entry.
     *
     * Returns `null` when the item can not be queued at all (an oversized
     * picture, for example): such an item is dropped instead of sending an
     * empty body to the server.
     */
    private suspend fun persist(item: CollectedItem): PendingItem? {
        val bytes = item.bytes
        if (bytes == null) {
            return PendingItem(
                kind = item.kind.path,
                payload = item.payload,
                meta = item.meta
            )
        }
        if (bytes.size > Config.MAX_MEDIA_BYTES) {
            Logger.w("Skipping oversized media (${bytes.size} bytes)")
            return null
        }
        val file = File(mediaDir, "${UUID.randomUUID()}.${extensionFor(item.mimeType)}")
        return try {
            withContext(Dispatchers.IO) { file.writeBytes(bytes) }
            PendingItem(
                kind = item.kind.path,
                payload = item.payload,
                mediaPath = file.absolutePath,
                mediaMime = item.mimeType ?: "image/png",
                meta = item.meta
            )
        } catch (e: Exception) {
            Logger.e("Unable to persist media for later upload", e)
            null
        }
    }

    private fun extensionFor(mimeType: String?): String = when (mimeType) {
        "image/jpeg", "image/jpg" -> "jpg"
        else -> "png"
    }

    /** Deletes a media file without ever throwing. */
    private fun deleteQuietly(path: String) {
        try {
            val file = File(path)
            if (file.exists() && !file.delete()) {
                Logger.w("Unable to delete the queued media file $path")
            }
        } catch (t: Throwable) {
            Logger.w("Unable to delete the queued media file $path", t)
        }
    }
}
