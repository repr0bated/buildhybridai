package com.example.callguardian.sms

import android.content.Context
import com.example.callguardian.data.db.QuarantinedSmsDao
import com.example.callguardian.data.db.QuarantinedSmsEntity
import com.example.callguardian.data.db.ScreeningEventEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When Call Guardian holds ROLE_SMS, quarantine hides the text from the system inbox and keeps
 * a copy in the in-app Quarantine folder. When it doesn't, this is a no-op (flag-only, as before).
 */
@Singleton
class QuarantineInbox @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: QuarantinedSmsDao,
) {
    fun isDefaultSms(): Boolean = SmsRole.held(context)
    fun observe() = dao.observeAll()
    fun observeCount() = dao.observeCount()

    suspend fun hide(event: ScreeningEventEntity): Boolean {
        if (event.isTest || !isDefaultSms()) return false
        val address = event.number ?: return false
        val body = event.body ?: return false
        val removed = TelephonySms.deleteInbox(context, address, body, event.receivedAt, event.smsUri)
        dao.insert(QuarantinedSmsEntity(
            address = address,
            body = body,
            date = event.receivedAt,
            eventId = event.id,
            hiddenAt = System.currentTimeMillis(),
            telephonyUri = removed?.toString() ?: event.smsUri,
        ))
        return true
    }

    suspend fun restore(id: Long): Boolean {
        if (!isDefaultSms()) return false
        val row = dao.get(id) ?: return false
        TelephonySms.insertInbox(context, row.address, row.body, row.date, row.dateSent, read = true)
        dao.delete(id)
        return true
    }
}
