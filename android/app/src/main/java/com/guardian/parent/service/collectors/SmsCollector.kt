package com.guardian.parent.service.collectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.guardian.parent.core.CollectorIds
import com.guardian.parent.core.Config
import com.guardian.parent.core.Logger
import com.guardian.parent.data.CollectedItem
import com.guardian.parent.data.ItemKind
import com.guardian.parent.data.SmsPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the SMS inbox / sent box of the device.
 *
 * Requires the `READ_SMS` permission, which the user grants explicitly.
 */
class SmsCollector(private val context: Context) : Collector {

    override val id: String = CollectorIds.SMS

    override suspend fun collect(): List<CollectedItem> = withContext(Dispatchers.IO) {
        try {
            if (!hasPermission(Manifest.permission.READ_SMS)) {
                Logger.d("SMS permission is not granted")
                return@withContext emptyList()
            }

            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.TYPE,
                Telephony.Sms.DATE
            )
            val selection = "${Telephony.Sms.DATE} >= ?"
            val args = arrayOf((System.currentTimeMillis() - Config.SMS_WINDOW_SEC * 1000L).toString())

            val messages = ArrayList<SmsPayload>()
            val cursor = try {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    projection,
                    selection,
                    args,
                    "${Telephony.Sms.DATE} DESC"
                )
            } catch (t: Throwable) {
                Logger.e("Unable to query the SMS provider", t)
                null
            }

            cursor?.use {
                val idIndex = it.getColumnIndex(Telephony.Sms._ID)
                val addressIndex = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIndex = it.getColumnIndex(Telephony.Sms.BODY)
                val typeIndex = it.getColumnIndex(Telephony.Sms.TYPE)
                val dateIndex = it.getColumnIndex(Telephony.Sms.DATE)

                while (it.moveToNext()) {
                    val id = if (idIndex >= 0) it.getLong(idIndex) else 0L
                    val address = if (addressIndex >= 0) it.getString(addressIndex) else null
                    val body = if (bodyIndex >= 0) it.getString(bodyIndex) else null
                    val type = if (typeIndex >= 0) it.getInt(typeIndex) else Telephony.Sms.MESSAGE_TYPE_INBOX
                    val date = if (dateIndex >= 0) it.getLong(dateIndex) else 0L

                    messages += SmsPayload(
                        address = address?.takeIf { value -> value.isNotBlank() },
                        body = body,
                        type = typeName(type),
                        at = date
                    )
                    Logger.d("SMS id=$id type=${typeName(type)}")
                }
            }

            messages.map { CollectedItem(ItemKind.SMS, payload = it.toJson()) }
        } catch (t: Throwable) {
            Logger.e("SMS collection failed", t)
            emptyList()
        }
    }

    /** The contract knows `inbox`, `sent` and `draft`. */
    private fun typeName(raw: Int): String = when (raw) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
        Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "sent"
        Telephony.Sms.MESSAGE_TYPE_FAILED -> "sent"
        Telephony.Sms.MESSAGE_TYPE_QUEUED -> "sent"
        else -> "inbox"
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
