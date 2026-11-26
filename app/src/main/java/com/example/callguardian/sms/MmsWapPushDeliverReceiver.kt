package com.example.callguardian.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * WAP_PUSH_DELIVER for incoming MMS. The compiled Clear SMS engine parses
 * the notification PDU, stores a pending row, and downloads the content.
 */
@AndroidEntryPoint
class MmsWapPushDeliverReceiver : BroadcastReceiver() {
    @Inject lateinit var gateway: MessagingGateway

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val pdu = intent.getByteArrayExtra("data")
        if (pdu == null) {
            Timber.w("WAP_PUSH_DELIVER without PDU")
            return
        }
        val subscriptionId = sequenceOf("subscription", "android.telephony.extra.SUBSCRIPTION_INDEX")
            .map { intent.getIntExtra(it, -1) }
            .firstOrNull { it >= 0 }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val id = gateway.ingestMms(pdu, subscriptionId)
                if (id == null) Timber.w("Undecodable MMS notification dropped")
                else Timber.i("MMS notification accepted id=%s", id)
            } catch (t: Throwable) {
                Timber.e(t, "WAP_PUSH_DELIVER failed")
            } finally {
                pending.finish()
            }
        }
    }
}
