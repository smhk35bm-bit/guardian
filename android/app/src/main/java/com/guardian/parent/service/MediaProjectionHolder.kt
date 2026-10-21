package com.guardian.parent.service

import android.media.projection.MediaProjection
import com.guardian.parent.core.Logger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds the single [MediaProjection] the user granted for this session.
 *
 * Android only allows a screen capture projection after the user explicitly
 * confirmed the system dialog, and the grant is bound to the session. The
 * object is the meeting point between the Activity that asks for the consent
 * and the collector that performs the capture.
 *
 * Nothing here hides anything from the user: the projection is always visible
 * in the status bar and the consent dialog is shown by the system itself.
 */
object MediaProjectionHolder {

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    private var projection: MediaProjection? = null

    /** Timestamp of the moment the user granted the capture consent. */
    @Volatile
    var consentAt: Long = 0L
        private set

    /** The active projection, or `null` when the user did not grant one. */
    fun current(): MediaProjection? = projection?.takeIf { it.isActive }

    fun isActive(): Boolean = current() != null

    /** Stores a freshly granted projection and notifies the collectors. */
    fun attach(mediaProjection: MediaProjection) {
        release()
        projection = mediaProjection
        consentAt = System.currentTimeMillis()
        Logger.i("Screen capture consent granted")
        notifyListeners(true)
    }

    /** Stops the projection; the user can always revoke it from the UI. */
    fun release() {
        val current = projection ?: return
        projection = null
        try {
            current.unregisterCallback(callback)
        } catch (t: Throwable) {
            Logger.w("Unable to unregister the media projection callback", t)
        }
        try {
            current.stop()
        } catch (t: Throwable) {
            Logger.w("Unable to stop the media projection", t)
        }
        Logger.i("Screen capture consent released")
        notifyListeners(false)
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        listener(isActive())
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(active: Boolean) {
        listeners.forEach { listener ->
            try {
                listener(active)
            } catch (t: Throwable) {
                Logger.w("Media projection listener failed", t)
            }
        }
    }

    /**
     * Android 14 requires a registered callback before a virtual display can
     * be created; it also tells us when the user revoked the projection.
     */
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            Logger.i("The user stopped the screen capture")
            projection = null
            consentAt = 0L
            notifyListeners(false)
        }
    }

    /** Registers the mandatory callback on the given projection. */
    fun registerCallback(mediaProjection: MediaProjection) {
        try {
            mediaProjection.registerCallback(callback, null)
        } catch (t: Throwable) {
            Logger.w("Unable to register the media projection callback", t)
        }
    }
}
