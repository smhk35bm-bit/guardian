package com.guardian.parent.ui

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.guardian.parent.R
import com.guardian.parent.core.Logger
import com.guardian.parent.core.Permissions
import com.guardian.parent.service.MediaProjectionHolder
import com.guardian.parent.service.MonitorService
import com.guardian.parent.ui.adapters.PermissionAction
import com.guardian.parent.ui.adapters.PermissionAdapter
import com.guardian.parent.ui.adapters.PermissionRow

/**
 * Walks the user through the permissions the app needs.
 *
 * Every single step is explained in plain words and can be revoked later from
 * the system settings. The order follows the recommendation of the platform:
 * notifications, location, background location, usage access, call log, SMS,
 * contacts, camera and finally the screen capture consent.
 */
class PermissionActivity : AppCompatActivity() {

    private lateinit var adapter: PermissionAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            Logger.i("The user denied: ${denied.joinToString()}")
        }
        refresh()
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            try {
                // The consent is only stored here. Since Android 14 the
                // projection may be created by a foreground service of the
                // type `mediaProjection`, so the service picks it up.
                MediaProjectionHolder.storeConsent(result.resultCode, data)
                MonitorService.startWithProjection(this)
                Toast.makeText(this, R.string.screen_capture_started, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Logger.e("Unable to start the screen capture", t)
            }
        }
        refresh()
    }

    /** Follows the state of the screen capture consent. */
    private val projectionListener: (Boolean) -> Unit = { _ ->
        runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission)

        adapter = PermissionAdapter { row -> onAction(row) }
        val list: RecyclerView = findViewById(R.id.permissionList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        // The service creates the projection, therefore the row has to follow
        // the state of the holder instead of a local flag.
        MediaProjectionHolder.addListener(projectionListener)
    }

    override fun onStop() {
        MediaProjectionHolder.removeListener(projectionListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val rows = ArrayList<PermissionRow>(9)

        rows += row(
            id = "notifications",
            titleRes = R.string.perm_notifications,
            descriptionRes = R.string.perm_notifications_desc,
            granted = Permissions.notificationsGranted(this),
            action = PermissionAction.GRANT,
            actionLabelRes = R.string.perm_action_grant
        )

        rows += row(
            id = "location",
            titleRes = R.string.perm_location,
            descriptionRes = R.string.perm_location_desc,
            granted = Permissions.locationGranted(this),
            action = PermissionAction.GRANT,
            actionLabelRes = R.string.perm_action_grant
        )

        rows += row(
            id = "background_location",
            titleRes = R.string.perm_background_location,
            descriptionRes = R.string.perm_background_location_desc,
            granted = Permissions.backgroundLocationGranted(this),
            action = PermissionAction.GRANT,
            actionLabelRes = R.string.perm_action_grant
        )

        rows += row(
            id = "usage_stats",
            titleRes = R.string.perm_usage,
            descriptionRes = R.string.perm_usage_desc,
            granted = Permissions.usageStatsGranted(this),
            action = PermissionAction.SETTINGS,
            actionLabelRes = R.string.perm_action_settings
        )

        rows += row(
            id = "call_log",
            titleRes = R.string.perm_call_log,
            descriptionRes = R.string.perm_call_log_desc,
            granted = Permissions.callLogGranted(this),
            action = PermissionAction.GRANT,
            actionLabelRes = R.string.perm_action_grant
        )

        rows += row(
            id = "sms",
            titleRes = R.string.perm_sms,
            descriptionRes = R.string.perm_sms_desc,
            granted = Permissions.smsGranted(this),
            action = PermissionAction.GRANT,
            actionLabelRes = R.string.perm_action_grant
        )

        rows += row(
            id = "contacts",
            titleRes = R.string.perm_contacts,
            descriptionRes = R.string.perm_contacts_desc,
            granted = Permissions.contactsGranted(this),
            action = PermissionAction.GRANT,
            actionLabelRes = R.string.perm_action_grant
        )

        rows += row(
            id = "camera",
            titleRes = R.string.perm_camera,
            descriptionRes = R.string.perm_camera_desc,
            granted = Permissions.cameraGranted(this),
            action = PermissionAction.GRANT,
            actionLabelRes = R.string.perm_action_grant
        )

        rows += row(
            id = "screen_capture",
            titleRes = R.string.perm_screen_capture,
            descriptionRes = R.string.perm_screen_capture_desc,
            granted = MediaProjectionHolder.isActive(),
            action = PermissionAction.SCREEN_CAPTURE,
            actionLabelRes = if (MediaProjectionHolder.isActive()) {
                R.string.perm_action_stop
            } else {
                R.string.perm_action_start
            }
        )

        adapter.submit(rows)
    }

    private fun row(
        id: String,
        titleRes: Int,
        descriptionRes: Int,
        granted: Boolean,
        action: PermissionAction,
        actionLabelRes: Int
    ) = PermissionRow(id, titleRes, descriptionRes, granted, action, actionLabelRes)

    private fun onAction(row: PermissionRow) {
        when (row.action) {
            PermissionAction.GRANT -> requestPermissionsFor(row.id)
            PermissionAction.SETTINGS -> openUsageAccessSettings()
            PermissionAction.SCREEN_CAPTURE -> toggleScreenCapture()
        }
    }

    private fun requestPermissionsFor(id: String) {
        val permissions = when (id) {
            "notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openAppNotificationSettings()
                return
            }

            "location" -> arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )

            "background_location" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    // Before Android 10 the foreground permission is enough.
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }

            "call_log" -> arrayOf(Manifest.permission.READ_CALL_LOG)
            "sms" -> arrayOf(Manifest.permission.READ_SMS)
            "contacts" -> arrayOf(Manifest.permission.READ_CONTACTS)
            "camera" -> arrayOf(Manifest.permission.CAMERA)
            else -> return
        }
        permissionLauncher.launch(permissions)
    }

    private fun openUsageAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (t: Throwable) {
            Logger.w("Unable to open the usage access settings", t)
            openAppDetailsSettings()
        }
    }

    private fun openAppNotificationSettings() {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (t: Throwable) {
            Logger.w("Unable to open the notification settings", t)
            openAppDetailsSettings()
        }
    }

    private fun openAppDetailsSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (t: Throwable) {
            Logger.w("Unable to open the application settings", t)
        }
    }

    /**
     * Starts or stops the screen capture.
     *
     * The consent itself is shown by the system, the app cannot capture
     * anything without the explicit confirmation of the user.
     */
    private fun toggleScreenCapture() {
        if (MediaProjectionHolder.isActive()) {
            MediaProjectionHolder.release()
            Toast.makeText(this, R.string.screen_capture_stopped, Toast.LENGTH_SHORT).show()
            refresh()
            return
        }
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                ?: return
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } catch (t: Throwable) {
            Logger.e("Unable to ask for the screen capture consent", t)
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
        }
    }
}
