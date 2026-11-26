package com.example.callguardian.sms

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.repository.SenderBlocker
import app.clearsms.mms.MmsInbound
import app.clearsms.mms.MmsSender
import app.clearsms.mms.MmsSizeBudget
import app.clearsms.mms.OutgoingAttachmentStager
import app.clearsms.mms.StagingResult
import app.clearsms.sms.OutgoingSendDispatcher
import app.clearsms.sms.SimInfo
import app.clearsms.sms.SubscriptionSource
import app.clearsms.ui.common.UiPrefs
import app.clearsms.work.InitialSyncWorker
import app.clearsms.work.MessageScheduler
import app.clearsms.work.SimBackfillWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Host facade over the compiled Clear SMS engine. */
@Singleton
class MessagingGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messages: MessageRepository,
    private val dispatcher: OutgoingSendDispatcher,
    private val scheduler: MessageScheduler,
    private val mmsSender: MmsSender,
    private val mmsInbound: MmsInbound,
    private val subscriptions: SubscriptionSource,
    private val stager: OutgoingAttachmentStager,
    private val blocker: SenderBlocker,
    private val uiPrefs: UiPrefs,
) {
    data class SendResult(val ok: Boolean, val messageId: Long? = null, val error: String? = null)

    suspend fun ingestSms(
        sender: String,
        body: String,
        timestampMs: Long,
        systemSmsId: Long?,
        dateSentMs: Long?,
        providerThreadId: Long?,
    ) = withContext(Dispatchers.IO) {
        messages.ingestIncoming(sender, body, timestampMs, systemSmsId, dateSentMs, providerThreadId)
    }

    suspend fun ingestMms(pdu: ByteArray, subscriptionId: Int?): Long? = withContext(Dispatchers.IO) {
        mmsInbound.onNotification(pdu, subscriptionId = subscriptionId)
    }

    fun activeSims(): List<SimInfo> = runCatching { subscriptions.activeSims() }.getOrDefault(emptyList())

    fun defaultSmsSubscriptionId(): Int? = runCatching { subscriptions.defaultSmsSubscriptionId() }.getOrNull()

    suspend fun draftFor(threadId: Long): String? = withContext(Dispatchers.IO) {
        runCatching { messages.draftFor(threadId) }.getOrNull()
    }

    suspend fun saveDraft(threadId: Long, text: String) = withContext(Dispatchers.IO) {
        runCatching { messages.saveDraft(threadId, text) }
            .onFailure { Timber.w(it, "Room draft save failed") }
    }

    suspend fun setPinned(threadId: Long, pinned: Boolean) = withContext(Dispatchers.IO) {
        runCatching { messages.setPinned(listOf(threadId), pinned) }
    }

    suspend fun setArchived(threadId: Long, archived: Boolean) = withContext(Dispatchers.IO) {
        runCatching { messages.archiveThreads(listOf(threadId), archived) }
    }

    suspend fun block(address: String) {
        withContext(Dispatchers.IO) {
            runCatching { blocker.block(address) }
            BlockedNumbers.block(context, address)
        }
    }

    suspend fun unblock(address: String) {
        withContext(Dispatchers.IO) {
            runCatching { blocker.unblock(address) }
            BlockedNumbers.unblock(context, address)
        }
    }

    fun isBlocked(address: String): Boolean = BlockedNumbers.isBlocked(context, address)

    suspend fun enableDeliveryReports() {
        runCatching { uiPrefs.setDeliveryReports(true) }
    }

    suspend fun deliveryReportsEnabled(): Boolean = runCatching { uiPrefs.deliveryReports.first() }.getOrDefault(false)

    fun startEngineMaintenance() {
        runCatching { app.clearsms.sms.SenderRegion.install(context) }
        runCatching { app.clearsms.notification.Channels.ensureCreated(context) }
        runCatching { SimBackfillWorker.enqueue(context) }
        runCatching { InitialSyncWorker.enqueue(context) }
    }

    suspend fun rearmScheduled() {
        runCatching { scheduler.rearmAll() }.onFailure { Timber.w(it, "rearm scheduled sends failed") }
    }

    /**
     * Sends or schedules a message. Multiple recipients or any attachment
     * becomes a group/multimedia MMS. Scheduling is SMS-only (one recipient,
     * no attachments) because the engine's WorkManager/alarm path stores a
     * single SMS body.
     */
    suspend fun send(
        destinations: List<String>,
        body: String,
        attachmentUris: List<Uri> = emptyList(),
        subscriptionId: Int? = null,
        scheduledAtMs: Long? = null,
    ): SendResult = withContext(Dispatchers.IO) {
        val dests = destinations.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (dests.isEmpty()) return@withContext SendResult(false, error = "Add a recipient.")
        if (body.isBlank() && attachmentUris.isEmpty()) {
            return@withContext SendResult(false, error = "Write a message or attach a file.")
        }
        if (!SmsRole.held(context)) {
            return@withContext SendResult(false, error = "Call Guardian is not the default SMS app.")
        }
        try {
            if (scheduledAtMs != null) {
                if (attachmentUris.isNotEmpty() || dests.size > 1) {
                    return@withContext SendResult(
                        false,
                        error = "Scheduled send is SMS-only (one recipient, no attachments).",
                    )
                }
                val id = scheduler.schedule(dests.first(), body, subscriptionId, scheduledAtMs)
                return@withContext SendResult(true, id)
            }
            val groupOrMms = dests.size > 1 || attachmentUris.isNotEmpty()
            if (groupOrMms) {
                val staged = mutableListOf<app.clearsms.mms.StagedAttachment>()
                val budget = MmsSizeBudget.forCarrier(null)
                var used = 0L
                for (uri in attachmentUris) {
                    when (val result = stager.stage(uri, budget, used)) {
                        is StagingResult.Staged -> {
                            staged += result.attachment
                            used += result.attachment.sizeBytes
                        }
                        is StagingResult.TooLarge ->
                            return@withContext SendResult(false, error = "An attachment is too large for MMS (${result.limitBytes} byte limit).")
                        StagingResult.Unreadable ->
                            return@withContext SendResult(false, error = "Couldn't read an attachment.")
                    }
                }
                val id = mmsSender.send(dests.joinToString(","), body, staged, subscriptionId)
                return@withContext SendResult(true, id)
            }
            val outcome = dispatcher.send(dests.first(), body, emptyList(), subscriptionId)
            SendResult(true, outcome.messageId)
        } catch (t: Throwable) {
            Timber.w(t, "Send failed")
            SendResult(false, error = t.message ?: "Couldn't send the message.")
        }
    }

    companion object {
        fun from(context: Context): MessagingGateway =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                MessagingGatewayEntryPoint::class.java,
            ).gateway()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MessagingGatewayEntryPoint {
    fun gateway(): MessagingGateway
    fun smartReplies(): com.example.callguardian.ai.SmartReplies
    fun conversationNotifier(): ConversationNotifier
}

fun smsThreadId(context: Context, uri: Uri?): Long? {
    if (uri == null) return null
    return runCatching {
        context.contentResolver.query(uri, arrayOf(Telephony.Sms.THREAD_ID), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(Telephony.Sms.THREAD_ID)
                if (i >= 0) c.getLong(i) else null
            } else null
        }
    }.getOrNull()
}

fun smsRowId(uri: Uri?): Long? = uri?.lastPathSegment?.toLongOrNull()
