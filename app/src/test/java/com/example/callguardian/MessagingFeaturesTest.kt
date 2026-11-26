package com.example.callguardian

import com.example.callguardian.ai.SmartReplies
import com.example.callguardian.sms.MessagePresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingFeaturesTest {

    @Test fun groupThreadKeyIsStableAndOrderIndependent() {
        val a = MessagePresentation.threadKey("+1 (555) 010-0199, 5550100200")
        val b = MessagePresentation.threadKey("5550100200,+15550100199")
        assertEquals(a, b)
        assertTrue(MessagePresentation.isGroup("5550100199,5550100200"))
        assertFalse(MessagePresentation.isGroup("+1 (555) 010-0199"))
    }

    @Test fun splitAndJoinRecipients() {
        val parts = MessagePresentation.splitRecipients("Ada, 5550100199; 5550100199")
        assertEquals(listOf("Ada", "5550100199"), parts)
        assertEquals("Ada, 5550100199", MessagePresentation.joinRecipients(parts))
    }

    @Test fun destinationFromSendToStillWorks() {
        assertEquals("+15550100199", MessagePresentation.destinationFromUri("+15550100199?body=hi"))
    }

    @Test fun smartReplyParserStripsBulletsAndLimits() {
        val parsed = SmartReplies.parseReplies(
            """
            1. Sounds good
            - On my way
            * Thanks!
            Suggest something else that should be dropped
            "See you soon"
            """.trimIndent(),
        )
        assertEquals(listOf("Sounds good", "On my way", "Thanks!"), parsed)
    }

    @Test fun emptySmartReplies() {
        assertTrue(SmartReplies.parseReplies(null).isEmpty())
        assertTrue(SmartReplies.parseReplies("   ").isEmpty())
    }
}
