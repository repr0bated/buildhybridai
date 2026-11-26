package com.example.callguardian.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class QuickReplyReceiver : BroadcastReceiver() {

    @Inject lateinit var gateway: MessagingGateway
    @Inject lateinit var notifier: ConversationNotifier

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
        if (address.isBlank() || text.isBlank()) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val result = gateway.send(MessagePresentation.splitRecipients(address), text)
                if (result.ok) notifier.cancel(address)
            } catch (t: Throwable) {
                Timber.w(t, "Quick reply failed")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.example.callguardian.sms.QUICK_REPLY"
        const val EXTRA_ADDRESS = "address"
        const val KEY_REPLY = "quick_reply_text"
    }
}
