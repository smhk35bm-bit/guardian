package com.guardian.parent.service.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.guardian.parent.core.Config
import com.guardian.parent.core.Logger
import com.guardian.parent.service.MediaProjectionHolder
import com.guardian.parent.service.MonitorService
import com.guardian.parent.service.capture.ScreenCaptureSource
import java.io.ByteArrayOutputStream

/**
 * Owns the one live session a device can run at a time.
 *
 * A session is either the screen or the camera — never both, because the
 * platform allows a single camera client per process and the screen pipeline
 * is expensive. `source` in the wire protocol is `1` for the screen and `2`
 * for the camera.
 */
class LiveStreamManager(
    private val context: Context,
    private val onSessionError: (source: String, code: String) -> Unit = { _, _ -> },
) {

    /** Called for every frame that must be pushed to the server. */
    private var sink: ((source: Int, jpeg: ByteArray) -> Unit)? = null

    @Volatile
    private var activeSource: Int = SOURCE_NONE

    private var cameraSource: CameraLiveSource? = null

    fun setSink(sink: ((source: Int, jpeg: ByteArray) -> Unit)?) {
        this.sink = sink
    }

    fun isStreaming(): Boolean = activeSource != SOURCE_NONE

    fun activeSource(): Int = activeSource

    /** Starts mirroring the screen; returns `null` on success or an error code. */
    @Synchronized
    fun startScreen(): String? {
        stop()
        val projection = MediaProjectionHolder.current()
            ?: return "NO_CONSENT"
        if (!ScreenCaptureSource.ensureStarted(context, projection)) {
            return "SCREEN_UNAVAILABLE"
        }

        val consumer: (ByteArray) -> Unit = { jpeg ->
            sink?.invoke(SOURCE_SCREEN, jpeg)
        }
        ScreenCaptureSource.setFrameListener(consumer, Config.LIVE_FRAME_INTERVAL_MS)
        activeSource = SOURCE_SCREEN
        Logger.i("Live screen stream started")
        return null
    }

    /** Starts mirroring the camera; returns `null` on success or an error code. */
    @Synchronized
    fun startCamera(): String? {
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "NO_CAMERA_PERMISSION"
        }
        MonitorService.ensureForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)

        val source = CameraLiveSource(context) { code ->
            // An asynchronous failure after the start acknowledgement still has
            // to reach the parent, otherwise the dashboard waits forever.
            if (activeSource == SOURCE_CAMERA) {
                activeSource = SOURCE_NONE
            }
            onSessionError("camera", code)
        }
        val error = source.start { jpeg -> sink?.invoke(SOURCE_CAMERA, jpeg) }
        if (error != null) {
            source.stop()
            return error
        }
        cameraSource = source
        activeSource = SOURCE_CAMERA
        Logger.i("Live camera stream started")
        return null
    }

    @Synchronized
    fun stop() {
        if (activeSource == SOURCE_SCREEN) {
            ScreenCaptureSource.setFrameListener(null, Config.LIVE_FRAME_INTERVAL_MS)
        }
        cameraSource?.stop()
        cameraSource = null
        if (activeSource != SOURCE_NONE) {
            Logger.i("Live stream stopped")
        }
        activeSource = SOURCE_NONE
    }

    companion object {
        const val SOURCE_NONE = 0
        const val SOURCE_SCREEN = 1
        const val SOURCE_CAMERA = 2
    }
}

/**
 * A repeating camera capture that produces small JPEG frames.
 *
 * The camera is opened once and kept open until the stream stops. Frames are
 * read as YUV and compressed with [YuvImage] because that path is supported by
 * every device, while a repeating JPEG request is not. Frames are throttled to
 * [Config.LIVE_FRAME_INTERVAL_MS] so a 4G link is not saturated.
 */
private class CameraLiveSource(
    private val context: Context,
    private val onFatal: (String) -> Unit = {},
) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    @Volatile
    private var listener: ((ByteArray) -> Unit)? = null

    @Volatile
    private var lastFrameAt: Long = 0L

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var frameCount: Int = 0

    private val manager: CameraManager?
        get() = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    /** Returns `null` when the stream is running, an error code otherwise. */
    fun start(onFrame: (ByteArray) -> Unit): String? {
        val cameraManager = manager ?: return "NO_CAMERA"
        val cameraId = selectCamera(cameraManager) ?: return "NO_CAMERA"
        listener = onFrame
        closed = false
        frameCount = 0

        val worker = HandlerThread("guardian-camera-live").apply { start() }
        val workerHandler = Handler(worker.looper)
        thread = worker
        handler = workerHandler

        return try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (closed) {
                        device.close()
                        return
                    }
                    camera = device
                    configure(device, cameraManager, cameraId, workerHandler)
                }

                override fun onDisconnected(device: CameraDevice) {
                    Logger.w("Live camera disconnected")
                    device.close()
                    fail("CAMERA_DISCONNECTED")
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Logger.e("Live camera error $error")
                    device.close()
                    fail("CAMERA_ERROR")
                }
            }, workerHandler)
            null
        } catch (t: Throwable) {
            Logger.e("Unable to open the camera for the live view", t)
            stop()
            "CAMERA_OPEN_FAILED"
        }
    }

    fun stop() {
        closed = true
        listener = null
        try {
            session?.close()
        } catch (t: Throwable) {
            // Already closed.
        }
        session = null
        try {
            reader?.close()
        } catch (t: Throwable) {
            // Already closed.
        }
        reader = null
        try {
            camera?.close()
        } catch (t: Throwable) {
            // Already closed.
        }
        camera = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun fail(code: String) {
        if (closed) return
        Logger.w("Live camera stopped: $code")
        stop()
        onFatal(code)
    }

    @Suppress("DEPRECATION")
    private fun configure(
        device: CameraDevice,
        cameraManager: CameraManager,
        cameraId: String,
        worker: Handler
    ) {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return fail("NO_STREAM_MAP")
            val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)
                ?: map.getOutputSizes(ImageFormat.JPEG)
                ?: return fail("NO_SIZES")
            val size = pickSize(sizes) ?: return fail("NO_SIZE")

            val created = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            created.setOnImageAvailableListener({ source -> onFrameAvailable(source) }, worker)
            reader = created

            device.createCaptureSession(
                listOf(created.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configuredSession: CameraCaptureSession) {
                        if (closed) {
                            configuredSession.close()
                            return
                        }
                        session = configuredSession
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(created.surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            }.build()
                            configuredSession.setRepeatingRequest(request, null, worker)
                            Logger.i("Live camera pipeline running at ${size.width}x${size.height}")
                        } catch (t: Throwable) {
                            Logger.e("Unable to start the live camera request", t)
                            fail("CAPTURE_FAILED")
                        }
                    }

                    override fun onConfigureFailed(configuredSession: CameraCaptureSession) {
                        Logger.e("Unable to configure the live camera session")
                        fail("CONFIGURE_FAILED")
                    }
                },
                worker
            )
        } catch (t: Throwable) {
            Logger.e("Unable to configure the live camera", t)
            fail("CONFIGURE_FAILED")
        }
    }

    private fun onFrameAvailable(source: ImageReader) {
        val image = try {
            source.acquireLatestImage()
        } catch (t: Throwable) {
            Logger.w("Unable to read a live camera frame", t)
            null
        } ?: return

        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < Config.LIVE_FRAME_INTERVAL_MS) return
            lastFrameAt = now

            val jpeg = image.toJpeg(Config.LIVE_JPEG_QUALITY) ?: return
            frameCount += 1
            if (frameCount == 1) {
                Logger.i("First live camera frame: ${jpeg.size} bytes")
            }
            listener?.invoke(jpeg)
        } catch (t: Throwable) {
            Logger.w("Unable to convert a live camera frame", t)
        } finally {
            try {
                image.close()
            } catch (ignored: Throwable) {
                // Already closed.
            }
        }
    }

    /** YUV_420_888 → NV21 → JPEG. */
    private fun Image.toJpeg(quality: Int): ByteArray? {
        val imageWidth = width
        val imageHeight = height
        if (imageWidth <= 0 || imageHeight <= 0) return null

        val nv21 = yuv420ToNv21(imageWidth, imageHeight) ?: return null
        val out = ByteArrayOutputStream(nv21.size / 4 + 1024)
        val yuv = YuvImage(nv21, ImageFormat.NV21, imageWidth, imageHeight, null)
        val ok = yuv.compressToJpeg(Rect(0, 0, imageWidth, imageHeight), quality, out)
        if (!ok) return null
        return out.toByteArray()
    }

    private fun Image.yuv420ToNv21(imageWidth: Int, imageHeight: Int): ByteArray? {
        if (planes.size < 3) return null
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val nv21 = ByteArray(imageWidth * imageHeight * 3 / 2)

        // Luma, row by row, skipping the padding at the end of each row.
        val yBuffer = yPlane.buffer
        var position = 0
        for (row in 0 until imageHeight) {
            val start = row * yPlane.rowStride
            if (start + imageWidth > yBuffer.limit()) break
            yBuffer.position(start)
            yBuffer.get(nv21, position, imageWidth)
            position += imageWidth
        }

        // Chroma: NV21 expects V then U, interleaved.
        val chromaHeight = imageHeight / 2
        val chromaWidth = imageWidth / 2
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        var index = imageWidth * imageHeight
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
                if (uIndex >= uBuffer.limit() || vIndex >= vBuffer.limit()) continue
                nv21[index++] = vBuffer.get(vIndex)
                nv21[index++] = uBuffer.get(uIndex)
            }
        }
        return nv21
    }

    private fun selectCamera(cameraManager: CameraManager): String? {
        val ids = try {
            cameraManager.cameraIdList
        } catch (t: Throwable) {
            return null
        }
        for (id in ids) {
            try {
                val facing = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
            } catch (t: Throwable) {
                // Try the next one.
            }
        }
        return ids.firstOrNull()
    }

    private fun pickSize(sizes: Array<android.util.Size>): android.util.Size? {
        val affordable = sizes.filter { it.width <= 720 && it.height <= 720 }
        return affordable.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.filter { it.width <= 1280 }.minByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
    }
}
