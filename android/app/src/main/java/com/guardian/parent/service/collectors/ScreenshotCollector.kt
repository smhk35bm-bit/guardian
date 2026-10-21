package com.guardian.parent.service.collectors

import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import com.guardian.parent.core.CollectorIds
import com.guardian.parent.core.Logger
import com.guardian.parent.data.CollectedItem
import com.guardian.parent.data.ItemKind
import com.guardian.parent.service.MediaProjectionHolder
import com.guardian.parent.service.MonitorService
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
 *
 * The virtual display is kept alive between the captures on purpose: since
 * Android 14 a [MediaProjection] instance may only create **one** virtual
 * display, so it has to be reused for every frame of the session.
 */
class ScreenshotCollector(private val context: Context) : Collector {

    override val id: String = CollectorIds.SCREENSHOT

    /** The reader and the display of the current consent session. */
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var boundProjection: MediaProjection? = null

    /** The capture that is currently waiting for a frame. */
    @Volatile
    private var pendingFrame: CompletableDeferred<Bitmap?>? = null

    /** A dedicated thread so that the image conversion never blocks the UI. */
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null

    override suspend fun collect(): List<CollectedItem> = withContext(Dispatchers.IO) {
        try {
            val projection = MediaProjectionHolder.current()
            if (projection == null) {
                Logger.d("No screen capture consent, skipping the screenshot")
                releaseResources()
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

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Grabs one frame of the screen and encodes it as PNG.
     */
    private suspend fun capture(projection: MediaProjection): ByteArray? {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) return null

        val activeReader = ensureReader(width, height)
        if (!ensureDisplay(projection, activeReader, width, height, metrics.densityDpi)) {
            return null
        }

        val frame = CompletableDeferred<Bitmap?>()
        pendingFrame = frame
        val bitmap = try {
            withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { frame.await() }
        } finally {
            pendingFrame = null
        }
        if (bitmap == null) {
            Logger.w("No screen frame received in time")
            return null
        }

        return ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
            stream.toByteArray()
        }
    }

    /**
     * Creates the image reader once per consent session.
     *
     * The listener always drains the buffer: when nobody is waiting for a
     * frame the image is simply discarded, otherwise the virtual display would
     * block after the first frames.
     */
    private fun ensureReader(width: Int, height: Int): ImageReader {
        val existing = reader
        if (existing != null && existing.width == width && existing.height == height) {
            return existing
        }
        releaseResources()

        val handler = ensureWorkerHandler()
        val created = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        created.setOnImageAvailableListener({
            val image = try {
                created.acquireLatestImage()
            } catch (t: Throwable) {
                Logger.w("Unable to read the captured frame", t)
                null
            } ?: return@setOnImageAvailableListener

            val target = pendingFrame
            if (target == null) {
                // Nobody is waiting: drop the frame but keep the buffer empty.
                image.close()
                return@setOnImageAvailableListener
            }
            try {
                val bitmap = image.toBitmap()
                image.close()
                target.complete(bitmap)
            } catch (t: Throwable) {
                Logger.e("Unable to convert the captured frame", t)
                try {
                    image.close()
                } catch (ignored: Throwable) {
                    // The image is already closed.
                }
                target.complete(null)
            }
        }, handler)

        reader = created
        return created
    }

    /**
     * Creates the virtual display of the current consent.
     *
     * Android 14 only allows a single virtual display per [MediaProjection]
     * instance, therefore the display is created once and then reused.
     */
    private fun ensureDisplay(
        projection: MediaProjection,
        activeReader: ImageReader,
        width: Int,
        height: Int,
        densityDpi: Int
    ): Boolean {
        val current = display
        if (current != null && boundProjection === projection) return true

        // A new consent was granted: the old display belongs to the old one.
        releaseResources()

        // Android 14 only allows the virtual display to be created while the
        // foreground service runs with the `mediaProjection` type.
        MonitorService.ensureForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        return try {
            val created = projection.createVirtualDisplay(
                "guardian-screenshot",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                activeReader.surface,
                null,
                workerHandler
            )
            if (created == null) {
                Logger.w("The system refused to create the virtual display")
                false
            } else {
                display = created
                boundProjection = projection
                Logger.i("The screen capture display is ready")
                true
            }
        } catch (t: Throwable) {
            // Android 14 refuses a second virtual display for the same consent.
            Logger.e("Unable to create the virtual display", t)
            display = null
            boundProjection = null
            false
        }
    }

    /** Releases the reader and the display of the current session. */
    private fun releaseResources() {
        try {
            display?.release()
        } catch (t: Throwable) {
            Logger.w("Unable to release the virtual display", t)
        }
        display = null
        boundProjection = null

        try {
            reader?.close()
        } catch (t: Throwable) {
            Logger.w("Unable to close the image reader", t)
        }
        reader = null
    }

    private fun ensureWorkerHandler(): Handler {
        val existing = workerHandler
        if (existing != null) return existing
        val thread = HandlerThread("guardian-screenshot").apply { start() }
        val handler = Handler(thread.looper)
        worker = thread
        workerHandler = handler
        return handler
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
