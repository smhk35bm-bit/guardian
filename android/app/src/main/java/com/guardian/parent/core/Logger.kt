package com.guardian.parent.core

import android.util.Log

/**
 * Thin wrapper around [android.util.Log] so that every line produced by the
 * app carries the same tag ("Guardian") and can be filtered with a single
 * `adb logcat -s Guardian` command.
 */
object Logger {

    const val TAG = Config.TAG

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, message, error)
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
    }
}
