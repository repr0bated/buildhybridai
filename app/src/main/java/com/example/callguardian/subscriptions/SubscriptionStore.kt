package com.example.callguardian.subscriptions

import com.example.callguardian.accounts.SenderIds
import com.example.callguardian.data.db.SafeDb
import com.example.callguardian.data.db.SubscriptionDao
import com.example.callguardian.data.db.SubscriptionEntity
import com.example.callguardian.data.db.SubscriptionHistoryDao
import com.example.callguardian.data.db.SubscriptionStatusHistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

data class SubscriptionRecord(
    val id: Long,
    val sender: String,
    val service: String,
    val source: String,
    val status: SubscriptionStatus,
    val updatedAt: Long,
    val stopSentAt: Long? = null,
    val unsubscribedAt: Long? = null,
    val canceledAt: Long? = null,
    val stopReceiptId: Long? = null,
    val unsubscribedEventId: Long? = null,
    val unsubscribedReceiptId: Long? = null,
    val pendingCancelAsk: Boolean = false,
    val muted: Boolean = false,
)

/**
 * Room-backed subscription lifecycle. Owned accounts are a separate store and are never
 * removed when a subscription is canceled.
 */
@Singleton
class SubscriptionStore @Inject constructor(
    private val dao: SubscriptionDao,
    private val history: SubscriptionHistoryDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var snapshot: List<SubscriptionRecord> = emptyList()
        private set

    val records: Flow<List<SubscriptionRecord>> = dao.observeAll()
        .map { rows -> rows.map { it.toModel() } }
        .onEach { snapshot = it }
        .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    fun bySender(number: String?): SubscriptionRecord? {
        if (number.isNullOrBlank()) return null
        val n = SenderIds.normalize(number)
        return snapshot.firstOrNull { it.sender == number || it.sender == n || SenderIds.normalize(it.sender) == n }
    }

    suspend fun ensure(sender: String, service: String = "", source: String = "sms"): SubscriptionRecord {
        val existing = dao.bySender(sender) ?: dao.bySender(SenderIds.normalize(sender))
        if (existing != null) return existing.toModel()
        val now = System.currentTimeMillis()
        val row = SubscriptionEntity(
            sender = SenderIds.normalize(sender).ifBlank { sender },
            service = service.take(80),
            source = source,
            status = SubscriptionStatus.ACTIVE.name,
            createdAt = now,
            updatedAt = now,
            pendingCancelAsk = false,
            muted = false,
        )
        val id = SafeDb.write("subscriptions.ensure") { dao.insert(row) } ?: 0L
        return row.copy(id = id).toModel()
    }

    suspend fun markStopSent(senders: Collection<String>, service: String, receiptId: Long?, keyword: String? = null) {
        val now = System.currentTimeMillis()
        for (raw in senders) {
            val rec = ensure(raw, service)
            transition(rec.id, SubscriptionStatus.STOP_SENT, "STOP sent after confirmation", receiptId, null) { e ->
                e.copy(stopSentAt = now, stopReceiptId = receiptId, keyword = keyword ?: e.keyword, service = service.ifBlank { e.service })
            }
        }
    }

    suspend fun markUnsubscribed(sender: String, eventId: Long?, receiptId: Long? = null, reason: String = "Unsubscribe confirmation received") {
        val rec = bySender(sender) ?: ensure(sender)
        val now = System.currentTimeMillis()
        transition(rec.id, SubscriptionStatus.UNSUBSCRIBED, reason, receiptId, eventId) { e ->
            e.copy(unsubscribedAt = now, unsubscribedEventId = eventId, unsubscribedReceiptId = receiptId)
        }
    }

    suspend fun markManageOpened(sender: String, service: String = "") {
        val rec = ensure(sender, service)
        val now = System.currentTimeMillis()
        SafeDb.write("subscriptions.manage") {
            dao.update(dao.get(rec.id)!!.copy(pendingCancelAsk = true, updatedAt = now, service = service.ifBlank { rec.service }))
        }
    }

    suspend fun confirmCanceled(id: Long, receiptId: Long? = null) {
        val now = System.currentTimeMillis()
        transition(id, SubscriptionStatus.CANCELED, "User confirmed cancel after Manage subscription", receiptId, null) { e ->
            e.copy(canceledAt = now, canceledReceiptId = receiptId, pendingCancelAsk = false)
        }
    }

    suspend fun dismissCancelAsk(id: Long) {
        SafeDb.write("subscriptions.dismissAsk") {
            dao.get(id)?.let { dao.update(it.copy(pendingCancelAsk = false, updatedAt = System.currentTimeMillis())) }
        }
    }

    suspend fun pendingCancelAsks(): List<SubscriptionRecord> = dao.pendingCancelAsks().map { it.toModel() }

    suspend fun history(id: Long) = history.forSubscription(id)

    private suspend fun transition(
        id: Long,
        to: SubscriptionStatus,
        reason: String,
        receiptId: Long?,
        eventId: Long?,
        tweak: (SubscriptionEntity) -> SubscriptionEntity = { it },
    ) {
        val now = System.currentTimeMillis()
        SafeDb.write("subscriptions.transition") {
            val cur = dao.get(id) ?: return@write
            val from = cur.status
            val updated = tweak(cur).copy(status = to.name, updatedAt = now)
            dao.update(updated)
            history.insert(SubscriptionStatusHistoryEntity(
                subscriptionId = id, fromStatus = from, toStatus = to.name, at = now,
                reason = reason.take(200), receiptId = receiptId, eventId = eventId,
            ))
        }
    }
}

private fun SubscriptionEntity.toModel() = SubscriptionRecord(
    id = id, sender = sender, service = service, source = source,
    status = SubscriptionStatus.from(status), updatedAt = updatedAt,
    stopSentAt = stopSentAt, unsubscribedAt = unsubscribedAt, canceledAt = canceledAt,
    stopReceiptId = stopReceiptId, unsubscribedEventId = unsubscribedEventId,
    unsubscribedReceiptId = unsubscribedReceiptId, pendingCancelAsk = pendingCancelAsk, muted = muted,
)
