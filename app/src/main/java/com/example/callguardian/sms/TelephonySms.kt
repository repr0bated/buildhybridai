package com.example.callguardian.sms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import timber.log.Timber

/** Reads and writes the system SMS provider. Writes only succeed when this app is the default SMS app. */
object TelephonySms {

    fun insertInbox(context: Context, address: String, body: String, date: Long, dateSent: Long = 0, read: Boolean = true): Uri? {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            if (dateSent > 0) put(Telephony.Sms.DATE_SENT, dateSent)
            put(Telephony.Sms.READ, if (read) 1 else 0)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        return runCatching { context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) }
            .onFailure { Timber.w(it, "Failed to write inbox SMS") }
            .getOrNull()
    }

    fun insertSent(context: Context, address: String, body: String, date: Long = System.currentTimeMillis()): Uri? {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE)
        }
        return runCatching { context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values) }
            .onFailure { Timber.w(it, "Failed to write sent SMS") }
            .getOrNull()
    }

    fun delete(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }
            .onFailure { Timber.w(it, "Failed to delete SMS $uri") }
            .getOrDefault(false)

    fun findInbox(context: Context, address: String, body: String, date: Long, uri: String? = null): InboxMatch? {
        val uriId = uri?.substringAfterLast('/')?.toLongOrNull()
        val rows = mutableListOf<InboxMatch>()
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        runCatching {
            context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                var n = 0
                while (c.moveToNext() && n++ < 400) {
                    rows += InboxMatch(c.getLong(iId), c.getString(iAddr).orEmpty(), c.getString(iBody).orEmpty(), c.getLong(iDate))
                }
            }
        }.onFailure { Timber.w(it, "Inbox query failed") }
        return InboxMatcher.pick(rows, address, body, date, uriId)
    }

    fun deleteInbox(context: Context, address: String, body: String, date: Long, uri: String? = null): Uri? {
        uri?.let { u -> runCatching { Uri.parse(u) }.getOrNull()?.let { if (delete(context, it)) return it } }
        val match = findInbox(context, address, body, date, uri) ?: return null
        val u = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, match.id.toString())
        return if (delete(context, u)) u else null
    }
}
