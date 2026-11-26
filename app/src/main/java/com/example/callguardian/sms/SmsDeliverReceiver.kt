package com.example.callguardian.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.callguardian.integrations.IntegrationPrefs
import com.example.callguardian.screening.ScreeningEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * SMS_DELIVER is sent only to the default SMS app. We write the message to the
 * telephony provider, ingest it into the Clear SMS engine, then run screening.
 * The default SMS role is never taken automatically.
 */
@AndroidEntryPoint
class SmsDeliverReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: ScreeningEngine
    @Inject lateinit var integrations: IntegrationPrefs
    @Inject lateinit var gateway: MessagingGateway
    @Inject lateinit var notifier: ConversationNotifier

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return
        val bySender = messages.groupBy { it.displayOriginatingAddress ?: it.originatingAddress }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(9_000) {
                    bySender.forEach { (sender, parts) ->
                        if (sender == null) return@forEach
                        val body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody.orEmpty() }
                        val date = parts.maxOf { it.timestampMillis }
                        val dateSent = parts.first().timestampMillis
                        val screen = integrations.enabled("phone_sms")
                        val uri = TelephonySms.insertInbox(context, sender, body, date, dateSent = dateSent, read = screen)
                        val systemId = smsRowId(uri)
                        val threadId = smsThreadId(context, uri)
                        runCatching {
                            gateway.ingestSms(sender, body, date, systemId, dateSent, threadId)
                        }.onFailure { Timber.w(it, "engine ingest failed") }
                        if (BlockedNumbers.isBlocked(context, sender)) return@forEach
                        if (screen) {
                            integrations.seen("phone_sms")
                            engine.screenSms(sender, body, smsUri = uri?.toString())
                        }
                        val label = ContactDirectory.displayName(context, sender)
                        notifier.notifyIncoming(sender, body, label)
                    }
                }
            } catch (t: Throwable) {
                Timber.e(t, "SMS_DELIVER screening failed")
            } finally {
                pending.finish()
            }
        }
    }
}
