package com.guardian.parent.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.guardian.parent.R
import com.guardian.parent.core.Logger
import com.guardian.parent.core.TimeFmt
import com.guardian.parent.data.LocalStore
import com.guardian.parent.service.MonitorService
import com.guardian.parent.ui.adapters.CollectorAdapter

/**
 * The dashboard of the app on the child's phone.
 *
 * It shows whether the phone is connected to the parent's server, which
 * collectors are running and offers the "send now" / "re-pair" actions.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: LocalStore
    private lateinit var statusServer: TextView
    private lateinit var statusDevice: TextView
    private lateinit var statusLastUpdate: TextView
    private lateinit var statusQueue: TextView
    private lateinit var statusService: TextView
    private lateinit var statusError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = LocalStore(this)
        if (!store.isEnrolled) {
            // Without a pairing there is nothing to show: send the user to
            // the enrollment screen.
            startActivity(Intent(this, EnrollmentActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        statusServer = findViewById(R.id.statusServer)
        statusDevice = findViewById(R.id.statusDevice)
        statusLastUpdate = findViewById(R.id.statusLastUpdate)
        statusQueue = findViewById(R.id.statusQueue)
        statusService = findViewById(R.id.statusService)
        statusError = findViewById(R.id.statusError)

        val list: RecyclerView = findViewById(R.id.collectorList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = CollectorAdapter(store) { refreshStatus() }

        findViewById<Button>(R.id.sendNowButton).setOnClickListener { sendNow() }
        findViewById<Button>(R.id.permissionsButton).setOnClickListener {
            startActivity(Intent(this, PermissionActivity::class.java))
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.relinkButton).setOnClickListener {
            startActivity(Intent(this, EnrollmentActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (!store.isEnrolled) return
        refreshStatus()
    }

    /**
     * Asks the running service to collect and upload everything right now.
     */
    private fun sendNow() {
        try {
            val intent = Intent(this, MonitorService::class.java)
                .setAction(MonitorService.ACTION_COLLECT_NOW)
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, R.string.sent_now, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Logger.e("Unable to trigger a manual collection", t)
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshStatus() {
        val last = store.lastUploadAt
        val error = store.lastError

        statusServer.text = when {
            store.lastAuthError -> getString(R.string.token_expired)
            last <= 0L -> getString(R.string.status_server_unknown)
            System.currentTimeMillis() - last < ONLINE_WINDOW_MS ->
                getString(R.string.status_server_online)
            else -> getString(R.string.status_server_offline)
        }

        statusDevice.text = getString(
            R.string.status_device,
            store.deviceName.ifBlank { getString(R.string.app_short_name) },
            store.deviceId
        )

        statusLastUpdate.text = if (last <= 0L) {
            getString(R.string.status_never)
        } else {
            getString(R.string.status_last_update, TimeFmt.human(last))
        }

        statusQueue.text = getString(R.string.status_queued, store.loadQueue().size)
        statusService.text = getString(
            if (MonitorService.isRunning) R.string.status_running else R.string.status_stopped
        )

        if (error.isNullOrBlank()) {
            statusError.visibility = View.GONE
        } else {
            statusError.text = error
            statusError.visibility = View.VISIBLE
        }
    }

    companion object {
        /** The server counts as "connected" when something was sent recently. */
        private const val ONLINE_WINDOW_MS = 5 * 60_000L
    }
}
