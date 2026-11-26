package com.example.callguardian.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.example.callguardian.methods.CallLogItem
import com.example.callguardian.methods.CallLogType
import com.example.callguardian.service.ContactLookupService
import com.example.callguardian.subscriptions.InboxSms
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Reads the phone's own SMS inbox and call log. Read-only, on the phone, nothing is uploaded. */
@Singleton
class PhoneData @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contacts: ContactLookupService,
) {
    fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun inbox(limit: Int = 300): List<InboxSms> = withContext(Dispatchers.IO) {
        if (!has(Manifest.permission.READ_SMS)) return@withContext emptyList()
        val out = mutableListOf<InboxSms>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out += InboxSms(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3))
            }
        }
        out
    }

    suspend fun calls(limit: Int = 200): List<CallLogItem> = withContext(Dispatchers.IO) {
        if (!has(Manifest.permission.READ_CALL_LOG)) return@withContext emptyList()
        data class Raw(val id: Long, val number: String?, val name: String?, val type: Int, val dur: Long, val date: Long)
        val rows = mutableListOf<Raw>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE),
            null, null, "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && rows.size < 1000) rows += Raw(c.getLong(0), c.getString(1)?.ifBlank { null }, c.getString(2)?.ifBlank { null }, c.getInt(3), c.getLong(4), c.getLong(5))
        }
        val key = { n: String? -> n?.filter { it.isDigit() }?.takeLast(10) }
        val byNumber = rows.groupBy { key(it.number) }
        val contactCache = HashMap<String, Boolean>()
        rows.take(limit).map { r ->
            val same = byNumber[key(r.number)].orEmpty().takeIf { key(r.number) != null }.orEmpty()
            CallLogItem(
                id = r.id, number = r.number, cachedName = r.name,
                isContact = r.number?.let { n -> contactCache.getOrPut(n) { runCatching { contacts.lookupContact(n) != null }.getOrDefault(false) } } ?: false,
                type = when (r.type) {
                    CallLog.Calls.INCOMING_TYPE -> CallLogType.INCOMING; CallLog.Calls.OUTGOING_TYPE -> CallLogType.OUTGOING
                    CallLog.Calls.MISSED_TYPE -> CallLogType.MISSED; CallLog.Calls.REJECTED_TYPE -> CallLogType.REJECTED
                    CallLog.Calls.BLOCKED_TYPE -> CallLogType.BLOCKED; CallLog.Calls.VOICEMAIL_TYPE -> CallLogType.VOICEMAIL
                    else -> CallLogType.UNKNOWN
                },
                durationSec = r.dur, date = r.date,
                callsFromNumber30d = same.count { it.date >= r.date - 30L * 86_400_000 && it.date <= r.date + 30L * 86_400_000 && it.date >= System.currentTimeMillis() - 30L * 86_400_000 },
                callsWithin15Min = same.count { it.id != r.id && it.date in (r.date - 15 * 60_000)..r.date },
            )
        }
    }
}
