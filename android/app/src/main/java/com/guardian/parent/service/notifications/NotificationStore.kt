package com.guardian.parent.service.notifications

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.guardian.parent.core.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small bounded buffer between the notification listener and the uploader.
 *
 * A `NotificationListenerService` callback runs on the main thread and must
 * return quickly, while the upload happens on a coroutine — a persisted queue
 * keeps the two decoupled and survives a process restart. The queue is capped
 * so a phone that stays offline for days cannot grow it without limit.
 */
object NotificationStore {

    private const val PREFS = "guardian_notifications"
    private const val KEY_PENDING = "pending"
    private const val MAX_PENDING = 300
    private val lock = Any()

    /** `true` when the user enabled our listener in the system settings. */
    fun hasAccess(context: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (t: Throwable) {
        Logger.w("Unable to read the notification listener state", t)
        false
    }

    /** Records one notification; identical consecutive entries are folded. */
    fun append(
        context: Context,
        packageName: String,
        title: String?,
        text: String?,
        at: Long
    ) {
        if (title.isNullOrBlank() && text.isNullOrBlank()) return
        synchronized(lock) {
            try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val array = readArray(prefs.getString(KEY_PENDING, null))

                val last = if (array.length() > 0) array.optJSONObject(array.length() - 1) else null
                if (last != null &&
                    last.optString("package") == packageName &&
                    last.optString("title") == (title ?: "") &&
                    last.optString("text") == (text ?: "")
                ) {
                    return
                }

                array.put(
                    JSONObject()
                        .put("package", packageName)
                        .put("title", title ?: "")
                        .put("text", text ?: "")
                        .put("at", at)
                )

                val trimmed = if (array.length() > MAX_PENDING) {
                    JSONArray().also { out ->
                        for (index in array.length() - MAX_PENDING until array.length()) {
                            out.put(array.get(index))
                        }
                    }
                } else {
                    array
                }
                prefs.edit().putString(KEY_PENDING, trimmed.toString()).apply()
            } catch (t: Throwable) {
                Logger.w("Unable to buffer a notification", t)
            }
        }
    }

    /** Returns every buffered notification and empties the queue. */
    fun drain(context: Context): List<JSONObject> = synchronized(lock) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val array = readArray(prefs.getString(KEY_PENDING, null))
            if (array.length() == 0) return emptyList()
            prefs.edit().remove(KEY_PENDING).apply()
            val items = ArrayList<JSONObject>(array.length())
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { items += it }
            }
            items
        } catch (t: Throwable) {
            Logger.w("Unable to read the buffered notifications", t)
            emptyList()
        }
    }

    private fun readArray(raw: String?): JSONArray = try {
        if (raw.isNullOrBlank()) JSONArray() else JSONArray(raw)
    } catch (t: Throwable) {
        JSONArray()
    }
}
