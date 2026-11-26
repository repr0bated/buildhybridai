package com.example.callguardian.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import timber.log.Timber

/**
 * Required of a default SMS app (WAP_PUSH_DELIVER / MMS). Call Guardian does not render MMS;
 * this stub exists so Android will grant ROLE_SMS. Incoming SMS is handled by [SmsDeliverReceiver].
 */
class MmsWapPushDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        Timber.i("WAP_PUSH_DELIVER received (MMS is not handled; stub for ROLE_SMS).")
    }
}
