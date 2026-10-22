package com.guardian.parent.service.collectors

import android.content.Context
import com.guardian.parent.core.CollectorIds
import com.guardian.parent.core.Logger
import com.guardian.parent.data.CollectedItem
import com.guardian.parent.data.ItemKind
import com.guardian.parent.service.MediaProjectionHolder
import com.guardian.parent.service.capture.ScreenCaptureSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Captures the screen through the [android.media.projection.MediaProjection]
 * API.
 *
 * The projection only exists while the user explicitly approved the system
 * consent dialog during the current session — there is no way to capture a
 * screen silently. When no consent is active the collector returns nothing.
 *
 * The virtual display is owned by [ScreenCaptureSource] because Android 14
 * allows a single virtual display per projection and the live view needs the
 * very same one.
 */
class ScreenshotCollector(private val context: Context) : Collector {

    override val id: String = CollectorIds.SCREENSHOT

    override suspend fun collect(): List<CollectedItem> = withContext(Dispatchers.IO) {
        try {
            val projection = MediaProjectionHolder.current()
            if (projection == null) {
                Logger.d("No screen capture consent, skipping the screenshot")
                ScreenCaptureSource.stop()
                return@withContext emptyList()
            }

            if (!ScreenCaptureSource.ensureStarted(context, projection)) {
                return@withContext emptyList()
            }

            // While a live stream runs its frames are small; a screenshot is
            // taken from the same pipeline and is simply the next frame.
            val bytes = ScreenCaptureSource.capture() ?: return@withContext emptyList()

            listOf(
                CollectedItem(
                    kind = ItemKind.SCREENSHOT,
                    bytes = bytes,
                    mimeType = MIME_JPEG,
                    meta = buildMeta()
                )
            )
        } catch (t: Throwable) {
            Logger.e("Screenshot collection failed", t)
            emptyList()
        }
    }

    /** Adds the foreground application to the optional `meta` field. */
    private fun buildMeta(): JSONObject? = try {
        val foreground = UsageCollector(context).foregroundAppNow()
        if (foreground == null) {
            null
        } else {
            JSONObject().apply {
                put("app", foreground.first)
                put("label", foreground.second ?: foreground.first)
            }
        }
    } catch (t: Throwable) {
        null
    }

    companion object {
        private const val MIME_JPEG = "image/jpeg"
    }
}
