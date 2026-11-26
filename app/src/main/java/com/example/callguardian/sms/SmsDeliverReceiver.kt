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
 * SMS_DELIVER is sent only to the default SMS app. We must write the message to the telephony
 * provider ourselves, then run the same screening pipeline as [SmsReceiver].
 */
@AndroidEntryPoint
class SmsDeliverReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: ScreeningEngine
    @Inject lateinit var integrations: IntegrationPrefs

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return
        val bySender = messages.groupBy { it.displayOriginatingAddress ?: it.originatingAddress }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(9_000) {
                    if (!integrations.enabled("phone_sms")) {
                        // Still write: as default SMS app we must persist the message.
                        bySender.forEach { (sender, parts) ->
                            val body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody.orEmpty() }
                            val date = parts.maxOf { it.timestampMillis }
                            if (sender != null) TelephonySms.insertInbox(context, sender, body, date, read = false)
                        }
                        return@withTimeoutOrNull
                    }
                    integrations.seen("phone_sms")
                    bySender.forEach { (sender, parts) ->
                        val body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody.orEmpty() }
                        val date = parts.maxOf { it.timestampMillis }
                        val uri = sender?.let { TelephonySms.insertInbox(context, it, body, date, dateSent = parts.first().timestampMillis, read = true) }
                        engine.screenSms(sender, body, smsUri = uri?.toString())
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
