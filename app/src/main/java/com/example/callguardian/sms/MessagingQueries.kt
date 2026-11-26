package com.example.callguardian.sms

import android.content.Context
import android.net.Uri
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
    val threadId: Long? = null,
    val isGroup: Boolean = false,
    val recipients: List<String> = emptyList(),
)

data class ThreadBubble(
    val id: Long,
    val body: String,
    val incoming: Boolean,
    val date: Long,
    val status: Int = Telephony.Sms.STATUS_NONE,
    val badge: MessageBadge? = null,
    val isMms: Boolean = false,
    val attachments: List<MmsPartRef> = emptyList(),
    val deliveryLabel: String? = null,
)

data class MmsPartRef(
    val uri: Uri,
    val mime: String,
    val name: String? = null,
)

/**
 * Reads the system SMS/MMS providers for the Messages tab. Writes go through
 * the compiled Clear SMS engine and only succeed when Call Guardian holds
 * ROLE_SMS. Inspired by Clear SMS's provider importers.
 */
object MessagingQueries {

    private val CONVERSATIONS: Uri = Uri.parse("content://mms-sms/conversations?simple=true")
    private val CANONICAL: Uri = Uri.parse("content://mms-sms/canonical-addresses")
    private val MMS_PART: Uri = Uri.parse("content://mms/part")

    fun threadIdFor(context: Context, addresses: Collection<String>): Long? {
        val dests = addresses.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (dests.isEmpty()) return null
        return runCatching { Telephony.Threads.getOrCreateThreadId(context, dests) }
            .onFailure { Timber.w(it, "thread id lookup failed") }
            .getOrNull()
            ?.takeIf { it > 0L }
    }

    fun conversations(context: Context, limit: Int = 400): List<ConversationRow> {
        val fromProvider = conversationsFromThreads(context, limit)
        if (fromProvider.isNotEmpty()) return fromProvider.map { decorate(context, it) }
        return (smsOnlyConversations(context, limit) + mmsOnlyConversations(context, limit))
            .groupBy { MessagePresentation.threadKey(it.address) }
            .values
            .map { group -> group.maxBy { it.date } }
            .sortedByDescending { it.date }
            .take(limit)
            .map { decorate(context, it) }
    }

    fun thread(context: Context, dest: String, limit: Int = 200): List<ThreadBubble> {
        val threadId = threadIdFor(context, MessagePresentation.splitRecipients(dest))
        val sms = smsThread(context, dest, threadId)
        val mms = mmsThread(context, dest, threadId)
        return (sms + mms).sortedBy { it.date }.let { if (it.size > limit) it.takeLast(limit) else it }
    }

    fun deleteThread(context: Context, dest: String): Int {
        if (!SmsRole.held(context)) return 0
        val threadId = threadIdFor(context, MessagePresentation.splitRecipients(dest))
        var deleted = 0
        if (threadId != null) {
            deleted += runCatching {
                context.contentResolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.THREAD_ID}=?", arrayOf(threadId.toString()))
            }.getOrDefault(0)
            deleted += runCatching {
                context.contentResolver.delete(Telephony.Mms.CONTENT_URI, "${Telephony.Mms.THREAD_ID}=?", arrayOf(threadId.toString()))
            }.getOrDefault(0)
        }
        thread(context, dest, limit = 10_000).forEach { b ->
            val base = if (b.isMms) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI
            val uri = Uri.withAppendedPath(base, b.id.toString())
            if (TelephonySms.delete(context, uri)) deleted++
        }
        return deleted
    }

    fun deleteMessage(context: Context, id: Long, isMms: Boolean = false): Boolean {
        if (!SmsRole.held(context)) return false
        val base = if (isMms) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI
        return TelephonySms.delete(context, Uri.withAppendedPath(base, id.toString()))
    }

    private fun decorate(context: Context, row: ConversationRow): ConversationRow {
        val recipients = row.recipients.ifEmpty { MessagePresentation.splitRecipients(row.address) }
        val name = row.displayName ?: ContactDirectory.displayName(context, row.address)
        return row.copy(
            displayName = name,
            isGroup = recipients.size > 1 || row.isGroup,
            recipients = recipients,
        )
    }

    private fun conversationsFromThreads(context: Context, limit: Int): List<ConversationRow> {
        val canonical = canonicalAddresses(context)
        if (canonical.isEmpty()) return emptyList()
        val out = mutableListOf<ConversationRow>()
        runCatching {
            context.contentResolver.query(CONVERSATIONS, null, null, null, "date DESC")?.use { c ->
                val iId = c.getColumnIndex("_id")
                val iDate = c.getColumnIndex("date")
                val iSnippet = c.getColumnIndex("snippet")
                val iRecipients = c.getColumnIndex("recipient_ids")
                val iRead = c.getColumnIndex("read")
                val iUnread = c.getColumnIndex("unread_count")
                if (iId < 0 || iDate < 0) return@use
                var n = 0
                while (c.moveToNext() && n++ < limit) {
                    val threadId = c.getLong(iId)
                    val recipientIds = if (iRecipients >= 0) c.getString(iRecipients).orEmpty() else ""
                    val addresses = recipientIds.split(' ', ',', ';')
                        .mapNotNull { it.trim().toLongOrNull()?.let(canonical::get) }
                        .filter { it.isNotBlank() }
                    if (addresses.isEmpty()) continue
                    val address = MessagePresentation.joinRecipients(addresses)
                    val read = if (iRead >= 0) c.getInt(iRead) == 1 else true
                    val unread = if (iUnread >= 0) c.getInt(iUnread) else if (!read) 1 else 0
                    out += ConversationRow(
                        address = address,
                        snippet = if (iSnippet >= 0) c.getString(iSnippet).orEmpty() else "",
                        date = c.getLong(iDate),
                        unread = unread,
                        threadId = threadId,
                        isGroup = addresses.size > 1,
                        recipients = addresses,
                    )
                }
            }
        }.onFailure { Timber.w(it, "Conversation threads query failed") }
        return out
    }

    private fun canonicalAddresses(context: Context): Map<Long, String> {
        val out = mutableMapOf<Long, String>()
        runCatching {
            context.contentResolver.query(CANONICAL, arrayOf("_id", "address"), null, null, null)?.use { c ->
                val iId = c.getColumnIndex("_id")
                val iA = c.getColumnIndex("address")
                if (iId < 0 || iA < 0) return@use
                while (c.moveToNext()) {
                    val addr = c.getString(iA)?.trim().orEmpty()
                    if (addr.isNotBlank()) out[c.getLong(iId)] = addr
                }
            }
        }.onFailure { Timber.w(it, "Canonical addresses query failed") }
        return out
    }

    private fun smsOnlyConversations(context: Context, limit: Int): List<ConversationRow> {
        val seen = LinkedHashMap<String, ConversationRow>()
        val unread = HashMap<String, Int>()
        val proj = arrayOf(
            Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
            Telephony.Sms.READ, Telephony.Sms.TYPE, Telephony.Sms.THREAD_ID,
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
                val iTh = c.getColumnIndex(Telephony.Sms.THREAD_ID)
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
                            threadId = if (iTh >= 0) c.getLong(iTh) else null,
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

    private fun mmsOnlyConversations(context: Context, limit: Int): List<ConversationRow> {
        val seen = LinkedHashMap<String, ConversationRow>()
        val proj = arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.THREAD_ID, Telephony.Mms.READ, Telephony.Mms.MESSAGE_BOX)
        runCatching {
            context.contentResolver.query(Telephony.Mms.CONTENT_URI, proj, null, null, "${Telephony.Mms.DATE} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Mms._ID)
                val iD = c.getColumnIndexOrThrow(Telephony.Mms.DATE)
                val iTh = c.getColumnIndex(Telephony.Mms.THREAD_ID)
                var n = 0
                while (c.moveToNext() && n++ < 400 && seen.size < limit) {
                    val id = c.getLong(iId)
                    val addresses = mmsAddresses(context, id)
                    if (addresses.isEmpty()) continue
                    val address = MessagePresentation.joinRecipients(addresses)
                    val key = MessagePresentation.threadKey(address)
                    if (key in seen) continue
                    val text = mmsBody(context, id).ifBlank { mmsSnippetFromParts(context, id) }
                    seen[key] = ConversationRow(
                        address = address,
                        snippet = text,
                        date = c.getLong(iD) * 1000L,
                        threadId = if (iTh >= 0) c.getLong(iTh) else null,
                        isGroup = addresses.size > 1,
                        recipients = addresses,
                    )
                }
            }
        }.onFailure { Timber.w(it, "MMS conversation query failed") }
        return seen.values.toList()
    }

    private fun smsThread(context: Context, dest: String, threadId: Long?): List<ThreadBubble> {
        val want = MessagePresentation.threadKey(dest)
        val out = mutableListOf<ThreadBubble>()
        val proj = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.STATUS, Telephony.Sms.THREAD_ID,
        )
        val selection: String?
        val args: Array<String>?
        if (threadId != null && threadId > 0) {
            selection = "${Telephony.Sms.THREAD_ID}=?"
            args = arrayOf(threadId.toString())
        } else {
            selection = null
            args = null
        }
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, proj, selection, args, "${Telephony.Sms.DATE} ASC",
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iA = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iB = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iD = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iT = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val iS = c.getColumnIndex(Telephony.Sms.STATUS)
                while (c.moveToNext()) {
                    if (threadId == null && MessagePresentation.threadKey(c.getString(iA).orEmpty()) != want) continue
                    val status = if (iS >= 0) c.getInt(iS) else Telephony.Sms.STATUS_NONE
                    out += ThreadBubble(
                        id = c.getLong(iId),
                        body = c.getString(iB).orEmpty(),
                        incoming = c.getInt(iT) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                        date = c.getLong(iD),
                        status = status,
                        deliveryLabel = smsDeliveryLabel(status, c.getInt(iT)),
                    )
                }
            }
        }.onFailure { Timber.w(it, "Thread query failed") }
        return out
    }

    private fun mmsThread(context: Context, dest: String, threadId: Long?): List<ThreadBubble> {
        val want = MessagePresentation.threadKey(dest)
        val out = mutableListOf<ThreadBubble>()
        val proj = arrayOf(
            Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX,
            Telephony.Mms.THREAD_ID, Telephony.Mms.STATUS,
        )
        val selection: String?
        val args: Array<String>?
        if (threadId != null && threadId > 0) {
            selection = "${Telephony.Mms.THREAD_ID}=?"
            args = arrayOf(threadId.toString())
        } else {
            selection = null
            args = null
        }
        runCatching {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI, proj, selection, args, "${Telephony.Mms.DATE} ASC",
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Mms._ID)
                val iD = c.getColumnIndexOrThrow(Telephony.Mms.DATE)
                val iBox = c.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val addresses = mmsAddresses(context, id)
                    if (threadId == null) {
                        val key = MessagePresentation.threadKey(MessagePresentation.joinRecipients(addresses))
                        if (key != want && addresses.none { MessagePresentation.threadKey(it) == want }) continue
                    }
                    val box = c.getInt(iBox)
                    val incoming = box == Telephony.Mms.MESSAGE_BOX_INBOX
                    val attachments = mmsParts(context, id)
                    val body = mmsBody(context, id).ifBlank {
                        attachments.joinToString(", ") { partLabel(it) }
                    }
                    out += ThreadBubble(
                        id = id,
                        body = body,
                        incoming = incoming,
                        date = c.getLong(iD) * 1000L,
                        isMms = true,
                        attachments = attachments,
                        deliveryLabel = if (!incoming) "MMS sent" else null,
                    )
                }
            }
        }.onFailure { Timber.w(it, "MMS thread query failed") }
        return out
    }

    private fun mmsAddresses(context: Context, id: Long): List<String> {
        val out = mutableListOf<String>()
        val uri = Uri.parse("content://mms/$id/addr")
        runCatching {
            context.contentResolver.query(uri, arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE), null, null, null)?.use { c ->
                val iA = c.getColumnIndex(Telephony.Mms.Addr.ADDRESS)
                val iT = c.getColumnIndex(Telephony.Mms.Addr.TYPE)
                while (c.moveToNext()) {
                    val addr = c.getString(iA)?.trim().orEmpty()
                    if (addr.isBlank() || addr == "insert-address-token") continue
                    // 137 = from, 151 = to, 130 = cc. Keep everyone except typeless blanks.
                    if (iT < 0 || c.getInt(iT) != 137 && c.getInt(iT) != 151 && c.getInt(iT) != 130) {
                        if (iT >= 0) continue
                    }
                    out += addr
                }
            }
        }
        return out.distinct()
    }

    private fun mmsBody(context: Context, id: Long): String {
        var text = ""
        runCatching {
            context.contentResolver.query(
                MMS_PART,
                arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT),
                "${Telephony.Mms.Part.MSG_ID}=?",
                arrayOf(id.toString()),
                null,
            )?.use { c ->
                val iMime = c.getColumnIndex(Telephony.Mms.Part.CONTENT_TYPE)
                val iText = c.getColumnIndex(Telephony.Mms.Part.TEXT)
                while (c.moveToNext()) {
                    val mime = c.getString(iMime).orEmpty()
                    if (mime.startsWith("text/plain") || mime == "application/smil") {
                        if (mime == "application/smil") continue
                        val t = if (iText >= 0) c.getString(iText).orEmpty() else ""
                        if (t.isNotBlank()) text = if (text.isBlank()) t else "$text\n$t"
                    }
                }
            }
        }
        return text
    }

    private fun mmsParts(context: Context, id: Long): List<MmsPartRef> {
        val out = mutableListOf<MmsPartRef>()
        runCatching {
            context.contentResolver.query(
                MMS_PART,
                arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.NAME, Telephony.Mms.Part.FILENAME),
                "${Telephony.Mms.Part.MSG_ID}=?",
                arrayOf(id.toString()),
                null,
            )?.use { c ->
                val iId = c.getColumnIndex(Telephony.Mms.Part._ID)
                val iMime = c.getColumnIndex(Telephony.Mms.Part.CONTENT_TYPE)
                val iName = c.getColumnIndex(Telephony.Mms.Part.NAME)
                val iFile = c.getColumnIndex(Telephony.Mms.Part.FILENAME)
                while (c.moveToNext()) {
                    val mime = c.getString(iMime).orEmpty()
                    if (mime.isBlank() || mime.startsWith("text/") || mime == "application/smil") continue
                    val partId = c.getLong(iId)
                    val name = (if (iName >= 0) c.getString(iName) else null)
                        ?: (if (iFile >= 0) c.getString(iFile) else null)
                    out += MmsPartRef(Uri.parse("content://mms/part/$partId"), mime, name)
                }
            }
        }
        return out
    }

    private fun mmsSnippetFromParts(context: Context, id: Long): String {
        val parts = mmsParts(context, id)
        return if (parts.isEmpty()) "MMS" else parts.joinToString(", ") { partLabel(it) }
    }

    private fun partLabel(part: MmsPartRef): String = when {
        part.mime.startsWith("image/") -> "Photo"
        part.mime.startsWith("video/") -> "Video"
        part.mime.startsWith("audio/") -> "Audio"
        else -> part.name ?: "Attachment"
    }

    private fun smsDeliveryLabel(status: Int, type: Int): String? {
        if (type != Telephony.Sms.MESSAGE_TYPE_SENT && type != Telephony.Sms.MESSAGE_TYPE_OUTBOX && type != Telephony.Sms.MESSAGE_TYPE_FAILED) {
            if (type == Telephony.Sms.MESSAGE_TYPE_FAILED) return "Failed"
            if (type != Telephony.Sms.MESSAGE_TYPE_SENT) return null
        }
        return when (status) {
            Telephony.Sms.STATUS_COMPLETE -> "Delivered"
            Telephony.Sms.STATUS_PENDING -> "Sent, waiting for delivery"
            Telephony.Sms.STATUS_FAILED -> "Delivery failed"
            Telephony.Sms.STATUS_NONE -> if (type == Telephony.Sms.MESSAGE_TYPE_FAILED) "Failed" else "Sent"
            else -> "Sent"
        }
    }
}
