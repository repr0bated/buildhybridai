package com.example.callguardian.sms

import android.content.Context
import android.provider.ContactsContract
import timber.log.Timber

data class ContactHit(
    val name: String,
    val number: String,
    val display: String = if (name.isBlank()) number else "$name · $number",
)

/** Lightweight contacts lookup for the compose picker and conversation titles. */
object ContactDirectory {

    fun displayName(context: Context, address: String): String? {
        val parts = MessagePresentation.splitRecipients(address)
        if (parts.isEmpty()) return null
        val names = parts.map { lookupName(context, it) ?: it }
        return if (parts.size == 1) names.first().takeIf { it != parts.first() } else names.joinToString(", ")
    }

    fun lookupName(context: Context, number: String): String? {
        if (number.isBlank() || number.any { it.isLetter() } && number.none { it.isDigit() }) return null
        return runCatching {
            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                .appendPath(number)
                .build()
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.onFailure { Timber.w(it, "Contact lookup failed") }.getOrNull()
    }

    fun search(context: Context, query: String, limit: Int = 40): List<ContactHit> {
        val q = query.trim()
        val out = LinkedHashMap<String, ContactHit>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection: String?
        val args: Array<String>?
        if (q.isBlank()) {
            selection = null
            args = null
        } else {
            selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            args = arrayOf("%$q%", "%$q%")
        }
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                args,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
            )?.use { c ->
                val iN = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val iP = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext() && out.size < limit) {
                    val number = c.getString(iP)?.trim().orEmpty()
                    if (number.isBlank()) continue
                    val key = MessagePresentation.threadKey(number)
                    if (key in out) continue
                    val name = c.getString(iN)?.trim().orEmpty()
                    out[key] = ContactHit(name = name, number = number)
                }
            }
        }.onFailure { Timber.w(it, "Contact search failed") }
        return out.values.toList()
    }
}
