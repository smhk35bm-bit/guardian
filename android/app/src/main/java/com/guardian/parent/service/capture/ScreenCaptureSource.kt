package com.guardian.parent.service.capture

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
import android.os.SystemClock
import com.guardian.parent.core.Config
import com.guardian.parent.core.Logger
import com.guardian.parent.service.MonitorService
import kotlinx.coroutines.CompletableDeferred
import java.io.ByteArrayOutputStream

/**
 * The single screen capture pipeline of the process.
 *
 * Android 14 allows **one** [VirtualDisplay] per [MediaProjection] instance, so
 * the periodic screenshot and the live view must share the same display. This
 * object owns that display and hands out JPEG frames to whoever asks:
 *
 *  - [capture] waits for the next frame and returns it (periodic screenshots).
 *  - [setFrameListener] receives every frame while a live stream is active.
 *
 * Nothing here is hidden from the child: the system keeps showing the screen
 * casting indicator and the foreground notification for the whole session.
 */
object ScreenCaptureSource {

    private const val TAG = "ScreenCapture"

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var boundProjection: MediaProjection? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** Set while a live stream is running; frames are throttled to its pace. */
    @Volatile
    private var frameListener: ((ByteArray) -> Boolean)? = null

    /**
     * Adaptive quality of the live view (0.55–1.0). It shrinks when the
     * uplink refuses a frame and recovers after a run of accepted frames.
     */
    @Volatile
    private var liveScale: Float = 1f

    private var acceptedStreak: Int = 0

    @Volatile
    private var frameIntervalMs: Long = Config.LIVE_FRAME_INTERVAL_MS

    @Volatile
    private var lastFrameAt: Long = 0L

    @Volatile
    private var captureWidth: Int = 0

    @Volatile
    private var captureHeight: Int = 0

    /** A single pending [capture] call (there is never more than one). */
    @Volatile
    private var waiter: CompletableDeferred<ByteArray?>? = null

    /**
     * Creates the virtual display for [projection] if it does not exist yet.
     *
     * @return `true` when frames can be produced.
     */
    @Synchronized
    fun ensureStarted(context: Context, projection: MediaProjection): Boolean {
        if (display != null && boundProjection === projection) return true

        releaseDisplay()

        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        if (screenWidth <= 0 || screenHeight <= 0) {
            Logger.w("$TAG: the display has no size")
            return false
        }

        val scale = if (screenWidth > Config.SCREENSHOT_MAX_WIDTH) {
            Config.SCREENSHOT_MAX_WIDTH.toFloat() / screenWidth
        } else {
            1f
        }
        val width = (screenWidth * scale).toInt().coerceAtLeast(2)
        val height = (screenHeight * scale).toInt().coerceAtLeast(2)
        val density = (metrics.densityDpi * scale).toInt().coerceAtLeast(72)

        return try {
            // Android 14 only allows the virtual display while the foreground
            // service runs with the `mediaProjection` type.
            MonitorService.ensureForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

            val worker = ensureThread()
            val created = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            created.setOnImageAvailableListener({ onImageAvailable(it) }, worker)
            val virtual = projection.createVirtualDisplay(
                "guardian-live",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                created.surface,
                null,
                worker
            )
            if (virtual == null) {
                Logger.w("$TAG: the system refused the virtual display")
                created.close()
                false
            } else {
                reader = created
                display = virtual
                boundProjection = projection
                captureWidth = width
                captureHeight = height
                Logger.i("$TAG: virtual display ready at ${width}x$height")
                true
            }
        } catch (t: Throwable) {
            Logger.e("$TAG: unable to create the virtual display", t)
            false
        }
    }

    /** Registers (or clears) the live frame consumer. */
    fun setFrameListener(listener: ((ByteArray) -> Boolean)?, intervalMs: Long) {
        frameIntervalMs = intervalMs.coerceAtLeast(50L)
        lastFrameAt = 0L
        liveScale = 1f
        acceptedStreak = 0
        frameListener = listener
        if (listener == null) {
            // Wake up anybody who is still waiting: the caller must time out
            // on its own, but completing early avoids a stale waiter.
            waiter?.let {
                waiter = null
                if (it.isActive) it.complete(null)
            }
        }
    }

    /** `true` while a live consumer is attached. */
    fun isStreaming(): Boolean = frameListener != null

    /**
     * Waits for the next frame and returns it as a JPEG.
     *
     * Returns `null` when the display is not ready or no frame arrives in time.
     */
    suspend fun capture(timeoutMs: Long = 8_000L): ByteArray? {
        if (display == null) return null
        val pending = CompletableDeferred<ByteArray?>()
        waiter = pending
        return try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { pending.await() }
        } finally {
            if (waiter === pending) waiter = null
        }
    }

    /** Releases the display; the projection itself is owned by its holder. */
    @Synchronized
    fun stop() {
        releaseDisplay()
    }

    fun isReady(): Boolean = display != null

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun ensureThread(): Handler {
        handler?.let { return it }
        val worker = HandlerThread("guardian-screen-live").apply { start() }
        val created = Handler(worker.looper)
        thread = worker
        handler = created
        return created
    }

    private fun onImageAvailable(source: ImageReader) {
        val image = try {
            source.acquireLatestImage()
        } catch (t: Throwable) {
            Logger.w("$TAG: unable to read the frame", t)
            null
        } ?: return

        try {
            val streaming = frameListener
            val now = SystemClock.elapsedRealtime()
            val throttle = if (streaming != null) frameIntervalMs else 0L
            val due = streaming != null && now - lastFrameAt >= throttle

            // While nobody consumes the frames they are dropped, otherwise the
            // reader would stall after a few buffers.
            if (!due && waiter == null) return

            val quality = if (streaming != null) {
                (Config.LIVE_JPEG_QUALITY * (0.7f + 0.3f * liveScale)).toInt().coerceIn(28, 60)
            } else {
                Config.LIVE_SCREENSHOT_QUALITY
            }
            val maxWidth = if (streaming != null) {
                (Config.LIVE_MAX_WIDTH * liveScale).toInt().coerceAtLeast(360)
            } else {
                0
            }
            val jpeg = image.toJpeg(quality, maxWidth) ?: return

            if (due) {
                lastFrameAt = now
                val accepted = try {
                    streaming?.invoke(jpeg) ?: false
                } catch (t: Throwable) {
                    Logger.w("$TAG: the live consumer failed", t)
                    false
                }
                if (streaming != null) adapt(accepted)
            }

            waiter?.let { pending ->
                waiter = null
                if (pending.isActive) pending.complete(jpeg)
            }
        } catch (t: Throwable) {
            Logger.w("$TAG: unable to convert the frame", t)
        } finally {
            try {
                image.close()
            } catch (ignored: Throwable) {
                // Already closed.
            }
        }
    }

    /**
     * Moves the live quality towards what the uplink can carry.
     *
     * The socket reports a refused frame when its queue is full; shrinking the
     * next frames keeps the delay low. A run of 20 accepted frames (a few
     * seconds of real movement) allows the quality to climb back.
     */
    private fun adapt(accepted: Boolean) {
        if (accepted) {
            acceptedStreak += 1
            if (acceptedStreak >= 20) {
                acceptedStreak = 0
                liveScale = (liveScale * 1.1f).coerceAtMost(1f)
            }
            return
        }
        acceptedStreak = 0
        val next = (liveScale * 0.85f).coerceAtLeast(0.55f)
        if (next < liveScale - 0.01f) {
            liveScale = next
            Logger.d("$TAG: live quality reduced to ${(next * 100).toInt()}%")
        }
    }

    /**
     * Converts an `RGBA_8888` [Image] to JPEG.
     *
     * The fast path copies the whole buffer into a bitmap whose width includes
     * the row padding, then crops the padding away. When the buffer layout is
     * unusual the slow, per row fallback is used.
     */
    private fun Image.toJpeg(quality: Int, maxWidth: Int): ByteArray? {
        val plane = planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val imageWidth = width
        val imageHeight = height
        if (imageWidth <= 0 || imageHeight <= 0) return null

        val buffer = plane.buffer
        buffer.rewind()

        val bitmap = try {
            fastBitmap(buffer, pixelStride, rowStride, imageWidth, imageHeight)
        } catch (t: Throwable) {
            Logger.w("$TAG: fast frame copy failed, falling back", t)
            null
        } ?: slowBitmap(buffer, pixelStride, rowStride, imageWidth, imageHeight)

        var target = bitmap
        if (maxWidth > 0 && bitmap.width > maxWidth) {
            val height = (bitmap.height.toLong() * maxWidth / bitmap.width).toInt().coerceAtLeast(2)
            target = Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
            bitmap.recycle()
        }

        return try {
            ByteArrayOutputStream(64 * 1024).use { stream ->
                target.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                stream.toByteArray()
            }
        } finally {
            target.recycle()
        }
    }

    private fun fastBitmap(
        buffer: java.nio.ByteBuffer,
        pixelStride: Int,
        rowStride: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Bitmap? {
        if (pixelStride != 4) return null
        val paddedWidth = rowStride / pixelStride
        if (paddedWidth < imageWidth) return null
        val needed = paddedWidth.toLong() * imageHeight * 4L
        if (buffer.remaining().toLong() < needed) return null

        val full = Bitmap.createBitmap(paddedWidth, imageHeight, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(buffer)
        if (paddedWidth == imageWidth) return full

        val cropped = Bitmap.createBitmap(full, 0, 0, imageWidth, imageHeight)
        full.recycle()
        return cropped
    }

    private fun slowBitmap(
        buffer: java.nio.ByteBuffer,
        pixelStride: Int,
        rowStride: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Bitmap {
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
                val alpha = if (base + 3 < row.size) row[base + 3].toInt() and 0xFF else 255
                val red = if (base < row.size) row[base].toInt() and 0xFF else 0
                val green = if (base + 1 < row.size) row[base + 1].toInt() and 0xFF else 0
                val blue = if (base + 2 < row.size) row[base + 2].toInt() and 0xFF else 0
                pixels[offset++] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, imageWidth, 0, 0, imageWidth, imageHeight)
        }
    }

    @Synchronized
    private fun releaseDisplay() {
        frameListener = null
        waiter?.let {
            waiter = null
            if (it.isActive) it.complete(null)
        }
        try {
            display?.release()
        } catch (t: Throwable) {
            Logger.w("$TAG: unable to release the display", t)
        }
        display = null
        boundProjection = null
        try {
            reader?.close()
        } catch (t: Throwable) {
            Logger.w("$TAG: unable to close the reader", t)
        }
        reader = null
        captureWidth = 0
        captureHeight = 0
    }
}
