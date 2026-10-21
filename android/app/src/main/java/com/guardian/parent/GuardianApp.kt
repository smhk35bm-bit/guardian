package com.guardian.parent

import android.app.Application
import com.guardian.parent.core.Logger
import com.guardian.parent.core.Notifications
import com.guardian.parent.data.LocalStore
import com.guardian.parent.service.UploadWorker

/**
 * Application entry point.
 *
 * It only prepares the notification channel and the periodic upload job; no
 * monitoring happens before the device has been paired with the parent's
 * account.
 */
class GuardianApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)

        val store = LocalStore(this)
        if (store.isEnrolled) {
            UploadWorker.schedule(this)
        }
        Logger.i("Guardian application started")
    }
}
