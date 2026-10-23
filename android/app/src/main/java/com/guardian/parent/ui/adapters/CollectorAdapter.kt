package com.guardian.parent.ui.adapters

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.guardian.parent.R
import com.guardian.parent.core.CollectorIds
import com.guardian.parent.data.LocalStore

/**
 * Lists every collector with a switch and the interval it runs at.
 *
 * The state is written straight into [LocalStore]; the monitoring service
 * picks the new values up on its next tick.
 */
class CollectorAdapter(
    private val store: LocalStore,
    private val onChanged: () -> Unit = {}
) : RecyclerView.Adapter<CollectorAdapter.Holder>() {

    /** One row of the list. */
    data class Row(
        val id: String,
        val titleRes: Int,
        val descriptionRes: Int
    )

    private val rows: List<Row> = listOf(
        Row(CollectorIds.LOCATION, R.string.collector_location, R.string.collector_location_desc),
        Row(CollectorIds.USAGE, R.string.collector_usage, R.string.collector_usage_desc),
        Row(CollectorIds.APPS, R.string.collector_apps, R.string.collector_apps_desc),
        Row(CollectorIds.CALLS, R.string.collector_calls, R.string.collector_calls_desc),
        Row(CollectorIds.SMS, R.string.collector_sms, R.string.collector_sms_desc),
        Row(CollectorIds.CONTACTS, R.string.collector_contacts, R.string.collector_contacts_desc),
        Row(
            CollectorIds.NOTIFICATIONS,
            R.string.collector_notifications,
            R.string.collector_notifications_desc
        ),
        Row(CollectorIds.SCREENSHOT, R.string.collector_screenshot, R.string.collector_screenshot_desc),
        Row(CollectorIds.CAMERA, R.string.collector_camera, R.string.collector_camera_desc),
        Row(CollectorIds.HEARTBEAT, R.string.collector_heartbeat, R.string.collector_heartbeat_desc)
    )

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.collectorTitle)
        val description: TextView = view.findViewById(R.id.collectorDescription)
        val toggle: SwitchCompat = view.findViewById(R.id.collectorSwitch)
        val interval: EditText = view.findViewById(R.id.intervalInput)
        var watcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_collector, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.title.setText(row.titleRes)
        holder.description.setText(row.descriptionRes)

        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = store.collectorEnabled(row.id)
        holder.toggle.setOnCheckedChangeListener { _, isChecked ->
            store.setCollectorEnabled(row.id, isChecked)
            onChanged()
        }

        // The watcher is removed before the text is set, otherwise recycling
        // would write the value of the previous row into the store.
        holder.watcher?.let { holder.interval.removeTextChangedListener(it) }
        val current = store.collectorInterval(row.id)
        if (holder.interval.text.toString() != current.toString()) {
            holder.interval.setText(current.toString())
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.trim()?.toLongOrNull() ?: return
                store.setCollectorInterval(row.id, value)
                onChanged()
            }
        }
        holder.watcher = watcher
        holder.interval.addTextChangedListener(watcher)
    }

    override fun getItemCount(): Int = rows.size
}
