package com.guardian.parent.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.guardian.parent.R

/**
 * What the button of a permission row does when it is pressed.
 */
enum class PermissionAction {
    /** Ask the system for a runtime permission. */
    GRANT,

    /** Send the user to the matching system settings screen. */
    SETTINGS,

    /** Start or stop the screen capture consent. */
    SCREEN_CAPTURE
}

/** One row of the permission list. */
data class PermissionRow(
    val id: String,
    val titleRes: Int,
    val descriptionRes: Int,
    val granted: Boolean,
    val action: PermissionAction,
    val actionLabelRes: Int
)

/**
 * Lists the permissions the monitoring depends on and shows their state.
 */
class PermissionAdapter(
    private val onAction: (PermissionRow) -> Unit
) : RecyclerView.Adapter<PermissionAdapter.Holder>() {

    private var rows: List<PermissionRow> = emptyList()

    fun submit(newRows: List<PermissionRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.permissionTitle)
        val description: TextView = view.findViewById(R.id.permissionDescription)
        val state: TextView = view.findViewById(R.id.permissionState)
        val action: Button = view.findViewById(R.id.permissionAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_permission, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.title.setText(row.titleRes)
        holder.description.setText(row.descriptionRes)
        holder.state.setText(
            if (row.granted) R.string.perm_granted else R.string.perm_denied
        )
        holder.state.setTextColor(
            holder.itemView.context.getColor(
                if (row.granted) R.color.guardian_ok else R.color.guardian_warning
            )
        )
        holder.action.setText(row.actionLabelRes)
        holder.action.setOnClickListener { onAction(row) }
    }

    override fun getItemCount(): Int = rows.size
}
