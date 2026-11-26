package com.example.callguardian.ui

import com.example.callguardian.data.db.AiStatus
import com.example.callguardian.data.db.QuarantinedSmsEntity
import com.example.callguardian.data.db.ReceiptEntity
import com.example.callguardian.data.db.ScreeningEventEntity
import com.example.callguardian.model.RiskLevel
import com.example.callguardian.subscriptions.InboxSms
import com.example.callguardian.methods.CallLogItem
import java.util.Calendar
import java.util.TimeZone

/** Pure helpers for the Home dashboard and the Process-all queue. Unit-tested. */
object HomeStats {
    fun isUnprocessed(e: ScreeningEventEntity): Boolean =
        e.aiStatus == AiStatus.NOT_RUN || e.aiStatus == AiStatus.PENDING ||
            e.aiStatus == AiStatus.FAILED || e.aiStatus == AiStatus.RUNNING

    fun risk(e: ScreeningEventEntity): RiskLevel =
        runCatching { RiskLevel.valueOf(e.risk) }.getOrDefault(RiskLevel.SAFE)

    fun isBlocked(e: ScreeningEventEntity): Boolean =
        e.userAction == "BLOCKED_BY_LIST" || e.quarantined ||
            (e.autoApplied && (e.finalAction == "block_number" || e.finalAction == "reject_call"))

    fun isScamFlag(e: ScreeningEventEntity): Boolean =
        isBlocked(e) || risk(e) >= RiskLevel.HIGH || e.category != "NONE"

    fun startOfDay(now: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun blockedToday(events: List<ScreeningEventEntity>, now: Long, tz: TimeZone = TimeZone.getDefault()): Int {
        val start = startOfDay(now, tz)
        return events.count { it.receivedAt in start..now && isBlocked(it) }
    }

    fun scamsFlaggedToday(events: List<ScreeningEventEntity>, now: Long, tz: TimeZone = TimeZone.getDefault()): Int {
        val start = startOfDay(now, tz)
        return events.count { it.receivedAt in start..now && isScamFlag(it) }
    }

    fun unprocessed(events: List<ScreeningEventEntity>): List<ScreeningEventEntity> =
        events.filter { isUnprocessed(it) }

    fun needsAttention(events: List<ScreeningEventEntity>, limit: Int = 5): List<ScreeningEventEntity> =
        events.filter { isUnprocessed(it) || risk(it) >= RiskLevel.HIGH }
            .sortedWith(
                compareByDescending<ScreeningEventEntity> { isUnprocessed(it) }
                    .thenByDescending { risk(it).ordinal }
                    .thenByDescending { it.receivedAt },
            )
            .take(limit)

    fun digits(s: String?) = s?.filter { it.isDigit() }.orEmpty()

    fun textAlreadyAnalyzed(m: InboxSms, events: List<ScreeningEventEntity>): Boolean {
        val addr = digits(m.address)
        return events.any { e ->
            !isUnprocessed(e) && e.channel == "SMS" &&
                (digits(e.number) == addr || e.displayName == m.address) &&
                (e.body == m.body || (kotlin.math.abs(e.receivedAt - m.date) < 5_000 && e.body?.take(40) == m.body.take(40)))
        }
    }

    fun callAlreadyAnalyzed(c: CallLogItem, events: List<ScreeningEventEntity>): Boolean {
        val addr = digits(c.number)
        return events.any { e ->
            !isUnprocessed(e) && e.channel == "CALL" && e.source == "call_log" &&
                (addr.isEmpty() || digits(e.number) == addr) &&
                kotlin.math.abs(e.receivedAt - c.date) < 5_000
        }
    }

    data class Counts(
        val blockedToday: Int,
        val scamsFlagged: Int,
        val unprocessed: Int,
        val quarantined: Int,
    )

    fun counts(events: List<ScreeningEventEntity>, quarantined: List<QuarantinedSmsEntity>, now: Long, tz: TimeZone = TimeZone.getDefault()) =
        Counts(blockedToday(events, now, tz), scamsFlaggedToday(events, now, tz), unprocessed(events).size, quarantined.size)

    fun recentReceipts(receipts: List<ReceiptEntity>, limit: Int = 6) = receipts.sortedByDescending { it.createdAt }.take(limit)
}

data class ProcessAllState(
    val running: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val label: String? = null,
    val done: Int = 0,
    val failed: Int = 0,
    val cancelled: Boolean = false,
    val candidates: List<com.example.callguardian.accounts.OwnedCandidate> = emptyList(),
) {
    val fraction: Float get() = if (total <= 0) 0f else (current.coerceAtMost(total) / total.toFloat())
    /** Progress copy for the Home/Activity button. Never includes a sender or other stray number. */
    val progressLabel: String get() = "Analyzing $current of $total"
}

