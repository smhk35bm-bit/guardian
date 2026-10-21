package com.guardian.parent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.guardian.parent.core.Logger
import com.guardian.parent.data.LocalStore

/**
 * Restarts the monitoring service after a reboot or after the app was updated.
 *
 * Starting a foreground service from a boot receiver is one of the few
 * situations Android explicitly allows, so the monitoring survives a restart
 * of the phone.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != QUICKBOOT_POWERON &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) {
            return
        }

        val store = LocalStore(context)
        if (!store.isEnrolled) {
            Logger.d("Boot received but the device is not paired yet")
            return
        }

        Logger.i("Boot received, restarting the monitoring service")
        MonitorService.start(context)
    }

    companion object {
        /** Some vendors send this broadcast instead of `BOOT_COMPLETED`. */
        private const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
