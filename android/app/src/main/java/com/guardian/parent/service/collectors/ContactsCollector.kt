package com.guardian.parent.service.collectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.guardian.parent.core.CollectorIds
import com.guardian.parent.core.Logger
import com.guardian.parent.data.CollectedItem
import com.guardian.parent.data.ContactPayload
import com.guardian.parent.data.ItemKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Uploads the address book of the device.
 *
 * The server keeps the phone number as the natural key, so a periodic sync is
 * idempotent: renamed contacts are updated, deleted ones simply stop being
 * reported. The whole book is sent as one payload instead of one request per
 * contact, which matters on a metered 4G connection.
 *
 * Requires `READ_CONTACTS`, granted explicitly by the user.
 */
class ContactsCollector(private val context: Context) : Collector {

    override val id: String = CollectorIds.CONTACTS

    override suspend fun collect(): List<CollectedItem> = withContext(Dispatchers.IO) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Logger.d("Contacts permission is not granted, skipping the sync")
                return@withContext emptyList()
            }

            val emails = loadEmails()
            val seen = HashSet<String>()
            val contacts = ArrayList<ContactPayload>()

            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID
            )
            val cursor = try {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC"
                )
            } catch (t: Throwable) {
                Logger.e("Unable to query the contacts provider", t)
                null
            }

            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)

                while (it.moveToNext() && contacts.size < MAX_CONTACTS) {
                    val rawNumber = if (numberIndex >= 0) it.getString(numberIndex) else null
                    val number = normalise(rawNumber) ?: continue
                    if (!seen.add(number)) continue
                    val name = if (nameIndex >= 0) it.getString(nameIndex) else null
                    val contactId = if (idIndex >= 0) it.getLong(idIndex) else 0L
                    contacts += ContactPayload(
                        name = name?.takeIf { value -> value.isNotBlank() },
                        number = number,
                        email = emails[contactId]
                    )
                }
            }

            if (contacts.isEmpty()) return@withContext emptyList()

            val array = JSONArray()
            contacts.forEach { array.put(it.toJson()) }
            val payload = JSONObject().put("contacts", array)
            Logger.d("Collected ${contacts.size} contacts")
            listOf(CollectedItem(ItemKind.CONTACTS, payload = payload))
        } catch (t: Throwable) {
            Logger.e("Contacts collection failed", t)
            emptyList()
        }
    }

    /** Keeps the number readable but consistent: digits plus a leading `+`. */
    private fun normalise(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val builder = StringBuilder(trimmed.length)
        for ((index, ch) in trimmed.withIndex()) {
            when {
                ch.isDigit() -> builder.append(ch)
                ch == '+' && index == 0 -> builder.append(ch)
                ch == ' ' || ch == '-' || ch == '(' || ch == ')' -> Unit
                else -> builder.append(ch)
            }
        }
        val value = builder.toString()
        return value.takeIf { it.count { ch -> ch.isDigit() } >= 5 }
    }

    private fun loadEmails(): Map<Long, String> {
        val result = HashMap<Long, String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.ADDRESS
        )
        val cursor = try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                projection,
                null,
                null,
                null
            )
        } catch (t: Throwable) {
            null
        }
        cursor?.use {
            val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
            val addressIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (it.moveToNext()) {
                val id = if (idIndex >= 0) it.getLong(idIndex) else continue
                val address = if (addressIndex >= 0) it.getString(addressIndex) else null
                if (!address.isNullOrBlank() && !result.containsKey(id)) {
                    result[id] = address.lowercase(Locale.ROOT)
                }
            }
        }
        return result
    }

    private companion object {
        /** A very large address book would not fit in one JSON request. */
        const val MAX_CONTACTS = 5000
    }
}
