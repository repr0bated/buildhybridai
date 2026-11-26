package com.example.callguardian

import com.example.callguardian.data.db.AiStatus
import com.example.callguardian.data.db.QuarantinedSmsEntity
import com.example.callguardian.data.db.ReceiptEntity
import com.example.callguardian.data.db.ScreeningEventEntity
import com.example.callguardian.privacy.PrivacyKeys
import com.example.callguardian.subscriptions.InboxSms
import com.example.callguardian.subscriptions.SubscriptionGroup
import com.example.callguardian.model.MessageSource
import com.example.callguardian.model.RiskLevel
import com.example.callguardian.ui.SearchChip
import com.example.callguardian.ui.SearchKind
import com.example.callguardian.ui.SearchMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatcherTest {
    private fun ev(
        id: Long, channel: String = "SMS", number: String? = "5550100", name: String? = "Ann",
        body: String? = "Your package is ready", risk: String = "HIGH", category: String = "PACKAGE_DELIVERY",
        quarantined: Boolean = false, source: String = "sms",
    ) = ScreeningEventEntity(
        id = id, channel = channel, number = number, displayName = name, body = body,
        receivedAt = 1_000L * id, aiStatus = AiStatus.DONE, risk = risk, category = category,
        quarantined = quarantined, source = source, reasons = "delivery scam",
    )

    @Test fun ftsQueryUsesPrefixAndSkipsDigitOnly() {
        assertEquals("package* OR ready*", SearchMatcher.ftsQuery("package ready"))
        assertNull(SearchMatcher.ftsQuery("5550100"))
        assertNull(SearchMatcher.ftsQuery("  "))
        assertFalse(SearchMatcher.ftsQuery("usps-help")!!.contains("\""))
    }

    @Test fun matchesNumberByDigitsAndNameAndVerdict() {
        val e = ev(1)
        assertTrue(SearchMatcher.matchesText("555", e.number, e.displayName, e.body))
        assertTrue(SearchMatcher.matchesText("ann", e.number, e.displayName, e.body))
        assertTrue(SearchMatcher.matchesText("package", e.body, e.category))
        assertTrue(SearchMatcher.matchesText("HIGH", e.risk))
        assertFalse(SearchMatcher.matchesText("toll", e.body, e.category))
    }

    @Test fun chipsFilterCallsTextsScamPrivateAndQuarantined() {
        val marks = setOf(PrivacyKeys.event(2))
        val events = listOf(
            ev(1, channel = "CALL", body = null, risk = "SAFE", category = "NONE", name = "Bank"),
            ev(2, body = "secret code", risk = "HIGH"),
            ev(3, body = "hello", risk = "SAFE", category = "NONE", quarantined = true, number = "22000"),
        )
        fun run(q: String, vararg chips: SearchChip) = SearchMatcher.merge(
            q, chips.toSet(), marks, events, emptyList(), emptyList(), emptyList(),
        )
        assertEquals(setOf(1L), run("", SearchChip.CALLS).map { it.eventId }.toSet())
        assertEquals(setOf(2L, 3L), run("", SearchChip.TEXTS).map { it.eventId }.toSet())
        assertEquals(setOf(2L, 3L), run("", SearchChip.SCAM).map { it.eventId }.toSet())
        assertEquals(setOf(2L), run("", SearchChip.PRIVATE).map { it.eventId }.toSet())
        assertEquals(setOf(3L), run("", SearchChip.QUARANTINED).map { it.eventId }.toSet())
        assertTrue(run("", SearchChip.PRIVATE).single().private)
    }

    @Test fun subscriptionChipMatchesServiceName() {
        val g = SubscriptionGroup(
            service = "Cash App", senders = listOf("72975", "443634"), count = 3,
            lastBody = "Reply STOP to opt out", lastDate = 5, signals = listOf("keyword"),
            keyword = "STOP", links = emptyList(), bestLink = null, brand = "Cash App",
            scamRisk = RiskLevel.SAFE, source = MessageSource.SMS, messageIds = listOf(10, 11),
        )
        val hits = SearchMatcher.merge("cash", setOf(SearchChip.SUBSCRIPTION), emptySet(), emptyList(), emptyList(), listOf(g), emptyList())
        assertEquals(1, hits.size)
        assertEquals(SearchKind.SUBSCRIPTION, hits[0].kind)
        assertEquals("Cash App", hits[0].title)
    }

    @Test fun privateItemsStillMatch() {
        val e = ev(9, body = "hidden body")
        val hits = SearchMatcher.merge("hidden", emptySet(), setOf(PrivacyKeys.event(9)), listOf(e), emptyList(), emptyList(), emptyList())
        assertEquals(1, hits.size)
        assertTrue(hits[0].private)
    }

    @Test fun voiceAndWhatsappCountAsTexts() {
        val evs = listOf(
            ev(1, source = "google_voice", body = "Voice ping"),
            ev(2, source = "whatsapp", body = "WhatsApp ping"),
        )
        val hits = SearchMatcher.merge("ping", setOf(SearchChip.TEXTS), emptySet(), evs, emptyList(), emptyList(), emptyList())
        assertEquals(2, hits.size)
    }

    @Test fun receiptsMatchMethodTitle() {
        val r = ReceiptEntity(
            id = 4, eventId = null, methodId = "guardian.sms.unsubscribe@v1", registryVersion = "2026.11.4",
            inputSchema = "i", outputSchema = "o", inputJson = "{\"service\":\"Acme\"}", outputJson = "{}",
            executedOn = "device", proposedBy = "USER", dispatch = "USER_CONFIRMED", status = "OK",
            notes = "", createdAt = 40, durationMs = 1,
        )
        val hits = SearchMatcher.merge("acme", emptySet(), emptySet(), emptyList(), listOf(r), emptyList(), emptyList(), methodTitle = { "Text STOP" })
        assertEquals(SearchKind.RECEIPT, hits.single().kind)
    }

    @Test fun inboxHitWhenNotAlreadyAnEvent() {
        val inbox = listOf(InboxSms(7, "5550199", "See you at 6?", 70))
        val hits = SearchMatcher.merge("see you", setOf(SearchChip.TEXTS), emptySet(), emptyList(), emptyList(), emptyList(), emptyList(), inbox = inbox)
        assertEquals(7L, hits.single().id)
    }
}
