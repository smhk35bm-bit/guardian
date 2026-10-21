package com.guardian.parent.service.collectors

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import com.guardian.parent.core.CollectorIds
import com.guardian.parent.core.Logger
import com.guardian.parent.data.CollectedItem
import com.guardian.parent.data.ItemKind
import com.guardian.parent.service.MediaProjectionHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Captures the screen through the [MediaProjection] API.
 *
 * The projection only exists while the user explicitly approved the system
 * consent dialog during the current session - there is no way to capture a
 * screen silently. When no consent is active the collector returns nothing.
 */
class ScreenshotCollector(private val context: Context) : Collector {

    override val id: String = CollectorIds.SCREENSHOT

    override suspend fun collect(): List<CollectedItem> = withContext(Dispatchers.IO) {
        try {
            val projection = MediaProjectionHolder.current()
            if (projection == null) {
                Logger.d("No screen capture consent, skipping the screenshot")
                return@withContext emptyList()
            }

            val bytes = capture(projection) ?: return@withContext emptyList()
            val meta = buildMeta()

            listOf(
                CollectedItem(
                    kind = ItemKind.SCREENSHOT,
                    bytes = bytes,
                    mimeType = MIME_PNG,
                    meta = meta
                )
            )
        } catch (t: Throwable) {
            Logger.e("Screenshot collection failed", t)
            emptyList()
        }
    }

    /**
     * Grabs one frame and encodes it as PNG.
     */
    private suspend fun capture(projection: MediaProjection): ByteArray? {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) return null

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var display: VirtualDisplay? = null
        val frame = CompletableDeferred<Bitmap?>()

        try {
            reader.setOnImageAvailableListener({
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val bitmap = try {
                            image.toBitmap()
                        } finally {
                            image.close()
                        }
                        frame.complete(bitmap)
                    }
                } catch (t: Throwable) {
                    Logger.e("Unable to read the captured frame", t)
                    frame.complete(null)
                }
            }, Handler(Looper.getMainLooper()))

            display = projection.createVirtualDisplay(
                "guardian-screenshot",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )

            if (display == null) {
                Logger.w("The system refused to create the virtual display")
                return null
            }

            val bitmap = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { frame.await() }
            if (bitmap == null) {
                Logger.w("No screen frame received in time")
                return null
            }

            return ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                bitmap.recycle()
                stream.toByteArray()
            }
        } finally {
            try {
                display?.release()
            } catch (t: Throwable) {
                Logger.w("Unable to release the virtual display", t)
            }
            try {
                reader.close()
            } catch (t: Throwable) {
                Logger.w("Unable to close the image reader", t)
            }
        }
    }

    /** Adds the foreground application to the optional `meta` field. */
    private fun buildMeta(): JSONObject? = try {
        val usage = UsageCollector(context)
        val foreground = usage.foregroundAppNow()
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

    /**
     * Copies an `RGBA_8888` [Image] into an ARGB bitmap.
     */
    private fun Image.toBitmap(): Bitmap {
        val imageWidth = width
        val imageHeight = height
        val plane = planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        buffer.rewind()

        val row = ByteArray(rowStride)
        val pixels = IntArray(imageWidth * imageHeight)
        var offset = 0

        for (y in 0 until imageHeight) {
            java.util.Arrays.fill(row, 0.toByte())
            val toRead = minOf(rowStride, buffer.remaining())
            if (toRead > 0) buffer.get(row, 0, toRead)
            for (x in 0 until imageWidth) {
                val base = x * pixelStride
                val alpha = if (base + 3 < row.size) row[base + 3].toInt() and 0xFF else 0
                val red = if (base < row.size) row[base].toInt() and 0xFF else 0
                val green = if (base + 1 < row.size) row[base + 1].toInt() and 0xFF else 0
                val blue = if (base + 2 < row.size) row[base + 2].toInt() and 0xFF else 0
                pixels[offset++] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }

        val bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, imageWidth, 0, 0, imageWidth, imageHeight)
        return bitmap
    }

    companion object {
        private const val MIME_PNG = "image/png"
        private const val CAPTURE_TIMEOUT_MS = 8_000L
    }
}
