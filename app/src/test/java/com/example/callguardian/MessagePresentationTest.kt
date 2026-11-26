package com.example.callguardian

import com.example.callguardian.sms.MessageBadge
import com.example.callguardian.sms.MessagePresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePresentationTest {

    @Test fun gsmSingleSegment() {
        val s = MessagePresentation.segments("Hello")
        assertEquals("GSM-7", s.encoding)
        assertEquals(1, s.units)
        assertEquals(155, s.remainingInSegment)
    }

    @Test fun gsmSecondSegmentStartsAt161() {
        val s = MessagePresentation.segments("a".repeat(161))
        assertEquals("GSM-7", s.encoding)
        assertEquals(2, s.units)
    }

    @Test fun ucs2ForEmoji() {
        val s = MessagePresentation.segments("hello 👋")
        assertEquals("UCS-2", s.encoding)
        assertEquals(1, s.units)
    }

    @Test fun queryMatchesDigitsAndSnippet() {
        assertTrue(MessagePresentation.matchesQuery("+1 (555) 010-0199", "Your code is 123", "Bank", "555010"))
        assertTrue(MessagePresentation.matchesQuery("Cash App", "Receipt", null, "receipt"))
        assertFalse(MessagePresentation.matchesQuery("Cash App", "Receipt", null, "paypal"))
    }

    @Test fun dateDividerOnDayChange() {
        val day = 24L * 60 * 60 * 1000
        assertTrue(MessagePresentation.shouldShowDateDivider(null, 1_700_000_000_000))
        assertFalse(MessagePresentation.shouldShowDateDivider(1_700_000_000_000, 1_700_000_000_000 + 3_600_000))
        assertTrue(MessagePresentation.shouldShowDateDivider(1_700_000_000_000, 1_700_000_000_000 + day + 1))
    }

    @Test fun badgesPreferCodeOwnedSubscriptionThenScam() {
        assertEquals(MessageBadge.CODE, MessagePresentation.badge(risk = "SAFE", reasons = "CLEAR (Code): verification or login code")?.id)
        assertEquals(MessageBadge.OWNED, MessagePresentation.badge(risk = "SAFE", reasons = "Owned account: Cash App (sender and official domain match).")?.id)
        assertEquals(MessageBadge.SUBSCRIPTION, MessagePresentation.badge(risk = "LOW", reasons = "Looks like a subscription. Reply STOP to opt out.")?.id)
        assertEquals(MessageBadge.STOPPED, MessagePresentation.badge(subscriptionStatus = "STOP_SENT")?.id)
        assertEquals(MessageBadge.CANCELED, MessagePresentation.badge(subscriptionStatus = "CANCELED", reasons = "Still texting after cancel")?.id)
        assertEquals(MessageBadge.SCAM, MessagePresentation.badge(risk = "CRITICAL", category = "PHISHING_LINK")?.id)
        assertEquals(MessageBadge.SUSPICIOUS, MessagePresentation.badge(risk = "MEDIUM", category = "NONE")?.id)
        assertNull(MessagePresentation.badge(risk = "SAFE", category = "NONE", reasons = "Link goes to an official site (py.pl)"))
    }

    @Test fun threadKeyStripsFormatting() {
        assertEquals(MessagePresentation.threadKey("+1 (555) 010-0199"), MessagePresentation.threadKey("15550100199"))
    }

    @Test fun destinationFromSendTo() {
        assertEquals("+15550100199", MessagePresentation.destinationFromUri("+15550100199"))
        assertEquals("+15550100199", MessagePresentation.destinationFromUri("+15550100199?body=hi"))
        assertNull(MessagePresentation.destinationFromUri(null))
        assertEquals("555", MessagePresentation.destinationFromUri(null, "555"))
    }
}
