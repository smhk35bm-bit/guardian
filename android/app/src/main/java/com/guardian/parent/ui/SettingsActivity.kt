package com.guardian.parent.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.guardian.parent.R
import com.guardian.parent.core.Logger
import com.guardian.parent.data.LocalStore
import com.guardian.parent.data.Repo
import com.guardian.parent.service.MonitorService
import com.guardian.parent.service.UploadWorker
import com.guardian.parent.ui.adapters.CollectorAdapter

/**
 * Everything the parent can tune on the device: the server address, the name
 * of the device, the collectors and the local retry queue.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var store: LocalStore
    private lateinit var serverInput: EditText
    private lateinit var deviceNameInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        store = LocalStore(this)
        serverInput = findViewById(R.id.serverUrlInput)
        deviceNameInput = findViewById(R.id.deviceNameInput)

        serverInput.setText(store.serverUrl.orEmpty())
        deviceNameInput.setText(store.deviceName)

        val list: RecyclerView = findViewById(R.id.collectorList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = CollectorAdapter(store)

        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
        findViewById<Button>(R.id.clearQueueButton).setOnClickListener { clearQueue() }
        findViewById<Button>(R.id.unpairButton).setOnClickListener { confirmUnpair() }
    }

    private fun save() {
        val url = serverInput.text.toString().trim()
        if (url.isNotEmpty()) {
            store.serverUrl = url
        }
        val name = deviceNameInput.text.toString().trim()
        if (name.isNotEmpty()) {
            store.deviceName = name
        }
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    private fun clearQueue() {
        Repo(applicationContext).clearQueue()
        Toast.makeText(this, R.string.settings_queue_cleared, Toast.LENGTH_SHORT).show()
    }

    /**
     * Removing the pairing stops every kind of data collection: the service is
     * stopped, the periodic job is cancelled and the token is deleted.
     */
    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setMessage(R.string.settings_unpair_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_unpair) { _, _ -> unpair() }
            .show()
    }

    private fun unpair() {
        try {
            stopService(Intent(this, MonitorService::class.java))
        } catch (t: Throwable) {
            Logger.w("Unable to stop the monitoring service", t)
        }
        UploadWorker.cancel(this)
        Repo(applicationContext).clearQueue()
        store.clearEnrollment()
        Toast.makeText(this, R.string.settings_unpaired, Toast.LENGTH_SHORT).show()
        startActivity(
            Intent(this, EnrollmentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}
