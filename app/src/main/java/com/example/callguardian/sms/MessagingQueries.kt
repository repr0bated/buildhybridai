package com.example.callguardian.sms

import android.content.Context
import android.provider.Telephony
import timber.log.Timber

data class ConversationRow(
    val address: String,
    val snippet: String,
    val date: Long,
    val unread: Int = 0,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val displayName: String? = null,
    val badge: MessageBadge? = null,
)

data class ThreadBubble(
    val id: Long,
    val body: String,
    val incoming: Boolean,
    val date: Long,
    val status: Int = Telephony.Sms.STATUS_NONE,
    val badge: MessageBadge? = null,
)

/**
 * Reads the system SMS provider for the Messages tab. Writes still go through [TelephonySms]
 * and only succeed when Call Guardian holds ROLE_SMS. Inspired by Clear SMS's provider
 * importers (third_party/clearsms), reimplemented against our types.
 */
object MessagingQueries {

    fun conversations(context: Context, limit: Int = 400): List<ConversationRow> {
        val seen = LinkedHashMap<String, ConversationRow>()
        val unread = HashMap<String, Int>()
        val proj = arrayOf(
            Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
            Telephony.Sms.READ, Telephony.Sms.TYPE,
        )
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, proj, null, null, "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                val iA = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iB = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iD = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iR = c.getColumnIndex(Telephony.Sms.READ)
                val iT = c.getColumnIndex(Telephony.Sms.TYPE)
                var n = 0
                while (c.moveToNext() && n++ < 2_000) {
                    val address = c.getString(iA)?.trim().orEmpty()
                    if (address.isBlank()) continue
                    val key = MessagePresentation.threadKey(address)
                    val incoming = iT < 0 || c.getInt(iT) == Telephony.Sms.MESSAGE_TYPE_INBOX
                    val isRead = iR >= 0 && c.getInt(iR) == 1
                    if (incoming && !isRead) unread[key] = (unread[key] ?: 0) + 1
                    if (seen.size < limit && key !in seen) {
                        seen[key] = ConversationRow(
                            address = address,
                            snippet = c.getString(iB).orEmpty(),
                            date = c.getLong(iD),
                        )
                    }
                }
            }
        }.onFailure { Timber.w(it, "Conversation query failed") }
        return seen.values.map { row ->
            val key = MessagePresentation.threadKey(row.address)
            row.copy(unread = unread[key] ?: 0)
        }
    }

    fun thread(context: Context, dest: String, limit: Int = 200): List<ThreadBubble> {
        val want = MessagePresentation.threadKey(dest)
        val out = mutableListOf<ThreadBubble>()
        val proj = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.STATUS,
        )
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, proj, null, null, "${Telephony.Sms.DATE} ASC",
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iA = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iB = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iD = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iT = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val iS = c.getColumnIndex(Telephony.Sms.STATUS)
                while (c.moveToNext()) {
                    if (MessagePresentation.threadKey(c.getString(iA).orEmpty()) != want) continue
                    out += ThreadBubble(
                        id = c.getLong(iId),
                        body = c.getString(iB).orEmpty(),
                        incoming = c.getInt(iT) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                        date = c.getLong(iD),
                        status = if (iS >= 0) c.getInt(iS) else Telephony.Sms.STATUS_NONE,
                    )
                }
            }
        }.onFailure { Timber.w(it, "Thread query failed") }
        return if (out.size > limit) out.takeLast(limit) else out
    }

    fun deleteThread(context: Context, dest: String): Int {
        if (!SmsRole.held(context)) return 0
        val want = MessagePresentation.threadKey(dest)
        var deleted = 0
        thread(context, dest, limit = 10_000).forEach { b ->
            val uri = android.net.Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, b.id.toString())
            if (TelephonySms.delete(context, uri)) deleted++
        }
        // also attempt a bulk delete for any missed rows
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS), null, null, null,
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iA = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                while (c.moveToNext()) {
                    if (MessagePresentation.threadKey(c.getString(iA).orEmpty()) != want) continue
                    val uri = android.net.Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, c.getLong(iId).toString())
                    if (TelephonySms.delete(context, uri)) deleted++
                }
            }
        }
        return deleted
    }

    fun deleteMessage(context: Context, id: Long): Boolean {
        if (!SmsRole.held(context)) return false
        val uri = android.net.Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString())
        return TelephonySms.delete(context, uri)
    }
}
