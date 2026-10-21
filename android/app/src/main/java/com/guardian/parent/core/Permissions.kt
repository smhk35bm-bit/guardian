package com.guardian.parent.core

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Snapshot of the permissions the monitoring features depend on. */
data class PermissionState(
    val notifications: Boolean = false,
    val location: Boolean = false,
    val backgroundLocation: Boolean = false,
    val usageStats: Boolean = false,
    val callLog: Boolean = false,
    val sms: Boolean = false,
    val contacts: Boolean = false,
    val camera: Boolean = false,
    val screenCapture: Boolean = false
)

/**
 * Helpers that query the *current* permission state.
 *
 * The results are cached in [com.guardian.parent.data.LocalStore] so that the
 * dashboard can show the state even when a permission was revoked while the
 * service was running.
 */
object Permissions {

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun notificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return granted(context, Manifest.permission.POST_NOTIFICATIONS)
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun locationGranted(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun backgroundLocationGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /**
     * `PACKAGE_USAGE_STATS` is a special "app op" permission: it is granted
     * from the usage access settings screen, never through a runtime dialog.
     */
    fun usageStatsGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = try {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } catch (t: Throwable) {
            Logger.w("Unable to check usage access", t)
            return false
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun callLogGranted(context: Context): Boolean =
        granted(context, Manifest.permission.READ_CALL_LOG)

    fun smsGranted(context: Context): Boolean =
        granted(context, Manifest.permission.READ_SMS)

    fun contactsGranted(context: Context): Boolean =
        granted(context, Manifest.permission.READ_CONTACTS)

    fun cameraGranted(context: Context): Boolean =
        granted(context, Manifest.permission.CAMERA)

    /** Computes the full snapshot for the current process. */
    fun currentState(context: Context): PermissionState = PermissionState(
        notifications = notificationsGranted(context),
        location = locationGranted(context),
        backgroundLocation = backgroundLocationGranted(context),
        usageStats = usageStatsGranted(context),
        callLog = callLogGranted(context),
        sms = smsGranted(context),
        contacts = contactsGranted(context),
        camera = cameraGranted(context),
        screenCapture = false
    )
}
