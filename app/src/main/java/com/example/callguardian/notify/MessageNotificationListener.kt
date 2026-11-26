package com.example.callguardian.notify

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.callguardian.integrations.IntegrationCatalog
import com.example.callguardian.screening.ScreeningEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Reads new messages from the notifications of the apps listed in integrations.json
 * (kind = notification_listener: Google Voice, WhatsApp, WhatsApp Business) and feeds them into
 * the same rules + Gemini Nano 4 pipeline with a typed source. Read-only: it never replies,
 * dismisses or sends anything, and it only sees messages that arrive while access is granted.
 */
@AndroidEntryPoint
class MessageNotificationListener : NotificationListenerService() {
    @Inject lateinit var engine: ScreeningEngine
    @Inject lateinit var catalog: IntegrationCatalog
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Recently screened (package, sender, text) hashes; apps re-post the same notification often. */
    private val seen = object : LinkedHashMap<Int, Unit>(64, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, Unit>?) = size > 200 }
    private val lastTime = HashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val integration = catalog.forPackage(sbn.packageName) ?: return
        val n = sbn.notification ?: return
        val f = fields(n)
        val since = lastTime[sbn.key] ?: 0
        val parsed = NotificationParser.parse(f, { catalog.ignores(integration, it) }, since)
        if (parsed.isEmpty()) return
        parsed.maxOf { it.time }.takeIf { it > 0 }?.let { lastTime[sbn.key] = it }
        val fresh = synchronized(seen) { parsed.filter { m -> seen.put((sbn.packageName + m.sender + m.text).hashCode(), Unit) == null } }
        if (fresh.isEmpty()) return
        scope.launch {
            fresh.forEach { m ->
                runCatching { engine.screenNotificationMessage(integration.id.name, catalog.source(integration), m.sender, m.conversation, m.text) }
                    .onFailure { Timber.w(it, "Screening %s notification failed", integration.id) }
            }
        }
    }

    private fun fields(n: Notification): NotificationFields {
        val x = n.extras ?: Bundle()
        val msgs: List<StyledMessage> = (if (Build.VERSION.SDK_INT >= 33) x.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
            else @Suppress("DEPRECATION") x.getParcelableArray(Notification.EXTRA_MESSAGES))
            ?.mapNotNull { p ->
                val b = p as? Bundle ?: return@mapNotNull null
                val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
                val sender = (if (Build.VERSION.SDK_INT >= 33) b.getParcelable("sender_person", android.app.Person::class.java) else @Suppress("DEPRECATION") b.getParcelable("sender_person"))
                    ?.name?.toString() ?: b.getCharSequence("sender")?.toString()
                StyledMessage(sender, text, b.getLong("time"))
            }.orEmpty()
        return NotificationFields(
            title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = x.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = x.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            conversationTitle = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            isGroupConversation = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            messages = msgs,
            isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            selfName = x.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString(),
        )
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
