package com.guardian.parent.service.collectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.core.content.ContextCompat
import com.guardian.parent.core.CollectorIds
import com.guardian.parent.core.Logger
import com.guardian.parent.core.TimeFmt
import com.guardian.parent.data.CollectedItem
import com.guardian.parent.data.ItemKind
import com.guardian.parent.service.MonitorService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Takes a picture with the device camera.
 */
class CameraCollector(private val context: Context) : Collector {

    override val id: String = CollectorIds.CAMERA

    override suspend fun collect(): List<CollectedItem> = withContext(Dispatchers.IO) {
        try {
            if (!hasCameraPermission()) {
                Logger.d("Camera permission is not granted, skipping the photo")
                return@withContext emptyList()
            }
            val bytes = capturePhoto() ?: return@withContext emptyList()
            val meta = JSONObject().apply {
                put("source", "camera")
                put("capturedAt", TimeFmt.iso(System.currentTimeMillis()))
            }
            listOf(
                CollectedItem(
                    kind = ItemKind.PHOTO,
                    bytes = bytes,
                    mimeType = MIME_JPEG,
                    meta = meta
                )
            )
        } catch (t: Throwable) {
            Logger.e("Camera collection failed", t)
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun capturePhoto(): ByteArray? {
        MonitorService.ensureForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val cameraId = selectCamera(manager) ?: run {
            Logger.w("No usable camera found")
            return null
        }

        val characteristics = manager.getCameraCharacteristics(cameraId)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = streamMap.getOutputSizes(ImageFormat.JPEG) ?: return null
        val size = pickSize(sizes) ?: return null

        val thread = HandlerThread("guardian-camera").apply { start() }
        val handler = Handler(thread.looper)
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null

        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)

        try {
            val openedCamera = openCamera(manager, cameraId, handler)
            camera = openedCamera

            val frame = CompletableDeferred<ByteArray?>()
            reader.setOnImageAvailableListener({
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()
                        frame.complete(bytes)
                    }
                } catch (t: Throwable) {
                    Logger.e("Unable to read the captured photo", t)
                    frame.complete(null)
                }
            }, handler)

            val openedSession = openSession(openedCamera, reader.surface, handler)
            session = openedSession
            takePicture(openedSession, openedCamera, reader.surface, characteristics, handler)

            return withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { frame.await() }
        } finally {
            try { session?.close() } catch (t: Throwable) {}
            try { reader.close() } catch (t: Throwable) {}
            try { camera?.close() } catch (t: Throwable) {}
            thread.quitSafely()
        }
    }

    private fun selectCamera(manager: CameraManager): String? {
        val ids = try { manager.cameraIdList } catch (t: Throwable) { return null }
        for (id in ids) {
            try {
                val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
            } catch (t: Throwable) {}
        }
        return ids.firstOrNull()
    }

    private fun pickSize(sizes: Array<android.util.Size>): android.util.Size? {
        val affordable = sizes.filter { it.width <= 1600 }
        return affordable.maxByOrNull { it.width.toLong() * it.height } ?: sizes.minByOrNull { it.width.toLong() * it.height }
    }

    @Suppress("DEPRECATION")
    private suspend fun openCamera(manager: CameraManager, cameraId: String, handler: Handler): CameraDevice =
        suspendCancellableCoroutine { continuation ->
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    if (continuation.isActive) continuation.resumeWithException(SecurityException("Camera permission missing"))
                    return@suspendCancellableCoroutine
                }
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (continuation.isActive) continuation.resume(camera)
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Camera disconnected"))
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Camera error $error"))
                    }
                }, handler)
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(t)
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun openSession(camera: CameraDevice, surface: Surface, handler: Handler): CameraCaptureSession =
        suspendCancellableCoroutine { continuation ->
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (continuation.isActive) continuation.resume(session)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Unable to configure the camera"))
                }
            }
            try {
                camera.createCaptureSession(listOf(surface), callback, handler)
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(t)
            }
        }

    private fun takePicture(session: CameraCaptureSession, camera: CameraDevice, surface: Surface, characteristics: CameraCharacteristics, handler: Handler) {
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(surface)
            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(characteristics))
        }.build()
        session.capture(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                Logger.e("The camera capture failed (reason=${failure.reason})")
            }
        }, handler)
    }

    @Suppress("DEPRECATION")
    private fun jpegOrientation(characteristics: CameraCharacteristics): Int {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
        val display = displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: android.view.Surface.ROTATION_0
        val degrees = when (rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensor + degrees) % 360
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val MIME_JPEG = "image/jpeg"
        private const val CAPTURE_TIMEOUT_MS = 15_000L
    }
}
