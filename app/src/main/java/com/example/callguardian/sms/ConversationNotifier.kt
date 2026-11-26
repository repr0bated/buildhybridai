package com.example.callguardian.sms

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.example.callguardian.MainActivity
import com.example.callguardian.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One notification channel per conversation, plus a RemoteInput quick reply.
 * Muted threads stay silent. Privacy blur hides bodies on the lock screen.
 */
@Singleton
class ConversationNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun notifyIncoming(address: String, body: String, label: String? = null) {
        val prefs = ConversationPrefs(context)
        val key = MessagePresentation.threadKey(address)
        if (prefs.isMuted(key)) return
        if (BlockedNumbers.isBlocked(context, address)) return
        val title = when {
            !label.isNullOrBlank() -> label
            MessagePresentation.isGroup(address) -> "Group conversation"
            else -> address
        }
        val text = body.ifBlank { "MMS" }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channelId = ensureChannel(nm, key, title)
        val open = PendingIntent.getActivity(
            context,
            key.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_ADDRESS, address)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val replyIntent = PendingIntent.getBroadcast(
            context,
            key.hashCode() + 17,
            Intent(context, QuickReplyReceiver::class.java).apply {
                action = QuickReplyReceiver.ACTION_REPLY
                putExtra(QuickReplyReceiver.EXTRA_ADDRESS, address)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remote = RemoteInput.Builder(QuickReplyReceiver.KEY_REPLY).setLabel("Reply").build()
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_notification, "Reply", replyIntent)
            .addRemoteInput(remote)
            .setAllowGeneratedReplies(true)
            .build()
        val person = Person.Builder().setName(title).setKey(key).build()
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.MessagingStyle(person).addMessage(text, System.currentTimeMillis(), person))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(reply)
        runCatching { nm.notify(NOTIFY_PREFIX + key.hashCode(), builder.build()) }
    }

    fun cancel(address: String) {
        val key = MessagePresentation.threadKey(address)
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(NOTIFY_PREFIX + key.hashCode())
    }

    private fun ensureChannel(nm: NotificationManager, key: String, title: String): String {
        val id = "cg_conv_" + key.take(40).replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannelGroup(GROUP_ID) == null) {
                nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP_ID, "Conversations"))
            }
            val existing = nm.getNotificationChannel(id)
            if (existing == null) {
                nm.createNotificationChannel(
                    NotificationChannel(id, title, NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Messages in this conversation"
                        group = GROUP_ID
                    },
                )
            } else if (existing.name != title) {
                nm.createNotificationChannel(
                    NotificationChannel(id, title, existing.importance).apply {
                        description = existing.description
                        group = GROUP_ID
                    },
                )
            }
        }
        return id
    }

    companion object {
        const val EXTRA_OPEN_ADDRESS = "open_address"
        private const val GROUP_ID = "cg_conversations"
        private const val NOTIFY_PREFIX = 0xC640000
    }
}
