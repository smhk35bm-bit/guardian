package com.guardian.parent.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.guardian.parent.core.Logger

/**
 * The loud ring the parent can trigger from the dashboard to find a misplaced
 * phone.
 *
 * It plays the system alarm at full volume, loops for at most one minute and
 * can be stopped from the dashboard at any moment. This is intentionally
 * audible: the child hears it, so the feature stays transparent.
 */
object RemoteAlarm {

    private const val MAX_DURATION_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null

    private val stopTask = Runnable { stop() }

    @Synchronized
    fun start(context: Context) {
        stop()
        val appContext = context.applicationContext
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: run {
                    Logger.w("No alarm ringtone is available on this device")
                    return
                }

            raiseAlarmVolume(appContext)

            val tone = RingtoneManager.getRingtone(appContext, uri) ?: return
            tone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tone.isLooping = true
            }
            tone.play()
            ringtone = tone
            handler.postDelayed(stopTask, MAX_DURATION_MS)
            Logger.i("Remote alarm started")
        } catch (t: Throwable) {
            Logger.w("Unable to start the remote alarm", t)
            stop()
        }
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacks(stopTask)
        try {
            ringtone?.stop()
        } catch (t: Throwable) {
            /* already stopped */
        }
        ringtone = null
    }

    fun isPlaying(): Boolean = ringtone?.isPlaying == true

    private fun raiseAlarmVolume(context: Context) {
        try {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            manager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        } catch (t: Throwable) {
            // Some OEMs refuse this without a user gesture; the ring still
            // plays at the current alarm volume.
            Logger.w("Unable to raise the alarm volume", t)
        }
    }
}
