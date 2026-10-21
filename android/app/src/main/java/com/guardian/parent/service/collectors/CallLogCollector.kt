package com.guardian.parent.service.collectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.guardian.parent.core.CollectorIds
import com.guardian.parent.core.Config
import com.guardian.parent.core.Logger
import com.guardian.parent.core.TimeFmt
import com.guardian.parent.data.CallPayload
import com.guardian.parent.data.CollectedItem
import com.guardian.parent.data.ItemKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the call log of the device.
 *
 * Requires the `READ_CALL_LOG` permission, which the user grants explicitly.
 */
class CallLogCollector(private val context: Context) : Collector {

    override val id: String = CollectorIds.CALLS

    override suspend fun collect(): List<CollectedItem> = withContext(Dispatchers.IO) {
        try {
            if (!hasPermission(Manifest.permission.READ_CALL_LOG)) {
                Logger.d("Call log permission is not granted")
                return@withContext emptyList()
            }

            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DURATION,
                CallLog.Calls.DATE
            )
            val selection = "${CallLog.Calls.DATE} >= ?"
            val args = arrayOf((System.currentTimeMillis() - Config.CALL_LOG_WINDOW_SEC * 1000L).toString())

            val calls = ArrayList<CallPayload>()
            val cursor = try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    selection,
                    args,
                    "${CallLog.Calls.DATE} DESC"
                )
            } catch (t: Throwable) {
                Logger.e("Unable to query the call log", t)
                null
            }

            cursor?.use {
                val idIndex = it.getColumnIndex(CallLog.Calls._ID)
                val numberIndex = it.getColumnIndex(CallLog.Calls.NUMBER)
                val cachedNameIndex = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIndex = it.getColumnIndex(CallLog.Calls.TYPE)
                val durationIndex = it.getColumnIndex(CallLog.Calls.DURATION)
                val dateIndex = it.getColumnIndex(CallLog.Calls.DATE)

                while (it.moveToNext()) {
                    val number = if (numberIndex >= 0) it.getString(numberIndex) else null
                    val cachedName = if (cachedNameIndex >= 0) it.getString(cachedNameIndex) else null
                    val type = if (typeIndex >= 0) it.getInt(typeIndex) else CallLog.Calls.MISSED_TYPE
                    val duration = if (durationIndex >= 0) it.getLong(durationIndex) else 0L
                    val date = if (dateIndex >= 0) it.getLong(dateIndex) else 0L
                    val id = if (idIndex >= 0) it.getLong(idIndex) else 0L

                    calls += CallPayload(
                        number = number?.takeIf { value -> value.isNotBlank() },
                        contactName = resolveContactName(number, cachedName),
                        type = typeName(type),
                        durationSec = duration.coerceAtLeast(0L),
                        at = date
                    )
                    Logger.d("Call id=$id type=${typeName(type)} at=${TimeFmt.iso(date)}")
                }
            }

            calls.map { CollectedItem(ItemKind.CALL, payload = it.toJson()) }
        } catch (t: Throwable) {
            Logger.e("Call log collection failed", t)
            emptyList()
        }
    }

    /** The contract only knows three call types. */
    private fun typeName(raw: Int): String = when (raw) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        else -> "missed"
    }

    /**
     * Uses the name cached by the dialer first and only then asks the contacts
     * provider (which needs the `READ_CONTACTS` permission).
     */
    private fun resolveContactName(number: String?, cachedName: String?): String? {
        if (!cachedName.isNullOrBlank()) return cachedName
        if (number.isNullOrBlank()) return null
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (t: Throwable) {
            Logger.w("Unable to resolve the contact name", t)
            null
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
