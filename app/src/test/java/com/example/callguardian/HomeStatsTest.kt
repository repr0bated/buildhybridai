package com.example.callguardian

import com.example.callguardian.data.db.AiStatus
import com.example.callguardian.data.db.QuarantinedSmsEntity
import com.example.callguardian.data.db.ScreeningEventEntity
import com.example.callguardian.methods.CallLogItem
import com.example.callguardian.methods.CallLogType
import com.example.callguardian.subscriptions.InboxSms
import com.example.callguardian.ui.HomeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class HomeStatsTest {
    private val tz = TimeZone.getTimeZone("UTC")
    private val noon = 1_704_000_000_000L // fixed

    private fun ev(
        id: Long, risk: String = "SAFE", ai: String = AiStatus.DONE, channel: String = "SMS",
        receivedAt: Long = noon, quarantined: Boolean = false, category: String = "NONE",
        auto: Boolean = false, action: String = "none", user: String? = null, number: String? = "5550100",
        body: String? = "hello", source: String = "sms",
    ) = ScreeningEventEntity(
        id = id, channel = channel, number = number, body = body, receivedAt = receivedAt,
        aiStatus = ai, risk = risk, category = category, quarantined = quarantined,
        autoApplied = auto, finalAction = action, userAction = user, source = source,
    )

    @Test fun unprocessedIncludesPendingFailedAndNotRun() {
        assertTrue(HomeStats.isUnprocessed(ev(1, ai = AiStatus.PENDING)))
        assertTrue(HomeStats.isUnprocessed(ev(11, ai = AiStatus.UNPROCESSED)))
        assertTrue(HomeStats.isUnprocessed(ev(2, ai = AiStatus.FAILED)))
        assertTrue(HomeStats.isUnprocessed(ev(3, ai = AiStatus.NOT_RUN)))
        assertFalse(HomeStats.isUnprocessed(ev(4, ai = AiStatus.DONE)))
        assertFalse(HomeStats.isUnprocessed(ev(5, ai = AiStatus.REJECTED)))
    }

    @Test fun blockedTodayCountsOnlyToday() {
        val start = HomeStats.startOfDay(noon, tz)
        val evs = listOf(
            ev(1, receivedAt = start + 1_000, user = "BLOCKED_BY_LIST"),
            ev(2, receivedAt = start - 1_000, user = "BLOCKED_BY_LIST"),
            ev(3, receivedAt = start + 2_000, auto = true, action = "block_number"),
            ev(4, receivedAt = start + 3_000, risk = "SAFE"),
        )
        assertEquals(2, HomeStats.blockedToday(evs, noon, tz))
    }

    @Test fun needsAttentionPrefersUnprocessedThenHighRisk() {
        val evs = listOf(
            ev(1, risk = "SAFE", ai = AiStatus.DONE, receivedAt = noon),
            ev(2, risk = "HIGH", ai = AiStatus.DONE, receivedAt = noon - 10),
            ev(3, risk = "SAFE", ai = AiStatus.PENDING, receivedAt = noon - 20),
            ev(4, risk = "CRITICAL", ai = AiStatus.FAILED, receivedAt = noon - 5),
        )
        val att = HomeStats.needsAttention(evs, 3)
        assertEquals(listOf(4L, 3L, 2L), att.map { it.id })
    }

    @Test fun textAndCallAlreadyAnalyzed() {
        val done = ev(9, ai = AiStatus.DONE, number = "5550100", body = "Hi there", receivedAt = noon, channel = "SMS")
        val sms = InboxSms(1, "5550100", "Hi there", noon)
        assertTrue(HomeStats.textAlreadyAnalyzed(sms, listOf(done)))
        assertFalse(HomeStats.textAlreadyAnalyzed(InboxSms(2, "5550100", "other", noon), listOf(done)))
        val callDone = ev(8, ai = AiStatus.DONE, channel = "CALL", number = "5550199", receivedAt = noon, source = "call_log", body = null)
        val call = CallLogItem(3, "5550199", "Ann", false, CallLogType.MISSED, 0, noon, 1, 0)
        assertTrue(HomeStats.callAlreadyAnalyzed(call, listOf(callDone)))
    }

    @Test fun countsIncludeQuarantineFolder() {
        val c = HomeStats.counts(listOf(ev(1, ai = AiStatus.PENDING)), listOf(QuarantinedSmsEntity(address = "1", body = "x", date = 1, hiddenAt = 1)), noon, tz)
        assertEquals(1, c.unprocessed)
        assertEquals(1, c.quarantined)
    }

    @Test fun progressLabelDropsSenderAndReadsAnalyzingOf() {
        val s = com.example.callguardian.ui.ProcessAllState(running = true, current = 0, total = 505, label = "28581")
        assertEquals("Analyzing 0 of 505", s.progressLabel)
        assertTrue(!s.progressLabel.contains("28581"))
        assertTrue(!s.progressLabel.contains("/"))
    }
}
