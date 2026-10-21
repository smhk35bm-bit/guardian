package com.guardian.parent.service

import android.content.Intent
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

    /**
     * The consent of the user, waiting to be turned into a projection.
     *
     * Since Android 14 the projection may only be created while a foreground
     * service of the type `mediaProjection` is running, therefore the Activity
     * only stores the result of the system dialog here and the service picks
     * it up.
     */
    @Volatile
    private var pendingCode: Int = 0

    @Volatile
    private var pendingConsent: Intent? = null

    /** Timestamp of the moment the user granted the capture consent. */
    @Volatile
    var consentAt: Long = 0L
        private set

    /**
     * The active projection, or `null` when the user did not grant one.
     *
     * `MediaProjection` has no public "isActive" method, therefore the state is
     * kept here: [callback] clears the reference as soon as the system tells
     * us that the user stopped the sharing.
     */
    fun current(): MediaProjection? = projection

    fun isActive(): Boolean = projection != null

    /**
     * Stores the result of the system consent dialog.
     *
     * The projection itself is created later on by the service, because
     * Android 14 only allows it while a `mediaProjection` foreground service
     * is running.
     */
    fun storeConsent(resultCode: Int, data: Intent) {
        pendingCode = resultCode
        pendingConsent = data
        Logger.i("The screen capture consent was granted and waits for the service")
    }

    /** Takes the stored consent, if there is one. */
    fun takeConsent(): Pair<Int, Intent>? {
        val data = pendingConsent ?: return null
        val code = pendingCode
        pendingConsent = null
        pendingCode = 0
        return code to data
    }

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
        consentAt = 0L
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
     * The platform callback: it is invoked when the user revokes the consent
     * (the "stop sharing" button of the system, for example).
     *
     * Since Android 14 the callback has to be registered before a virtual
     * display may be created, which is why [registerCallback] is called right
     * after the consent was granted.
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
