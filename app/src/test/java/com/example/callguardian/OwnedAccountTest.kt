package com.example.callguardian

import com.example.callguardian.accounts.OwnedAccount
import com.example.callguardian.accounts.OwnedCandidates
import com.example.callguardian.accounts.SenderIds
import com.example.callguardian.data.db.ScreeningEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedAccountTest {
    @Test fun senderNormalizationMatchesVariants() {
        assertEquals("5550100123", SenderIds.normalize("+1 (555) 010-0123"))
        assertTrue(SenderIds.same("+15550100123", "(555) 010-0123"))
        assertTrue(SenderIds.isShortCode("51185"))
        assertFalse(SenderIds.isShortCode("+15550100123"))
    }

    @Test fun accountMatchesSender() {
        val a = OwnedAccount(1, "E-ZPass", listOf("51185"), listOf("e-zpassny.com"))
        assertTrue(a.matchesSender("51185"))
        assertFalse(a.matchesSender("99999"))
        assertTrue(a.matchesDomain("www.e-zpassny.com"))
        assertFalse(a.matchesDomain("ezpass-pay.info"))
    }

    @Test fun processAllSuggestsRepeatOfficialSendersAndNeverLookalikes() {
        fun ev(id: Long, number: String, body: String) = ScreeningEventEntity(
            id = id, channel = "SMS", number = number, body = body, receivedAt = id, isTest = false,
        )
        val official = listOf(
            ev(1, "51185", "E-ZPass: statement ready https://www.e-zpassny.com/account"),
            ev(2, "51185", "E-ZPass: pay at https://e-zpassny.com/pay"),
        )
        val sug = OwnedCandidates.suggest(official, already = emptyList())
        assertEquals(1, sug.size)
        assertEquals("E-ZPass", sug[0].service)
        assertTrue(sug[0].domains.contains("e-zpassny.com"))
        assertEquals(2, sug[0].messageCount)

        val lookalike = listOf(
            ev(3, "13055550188", "E-ZPass FINAL NOTICE http://ezpass-pay.info/settle"),
            ev(4, "13055550188", "E-ZPass pay now http://ezpass-pay.info/go"),
        )
        assertTrue(OwnedCandidates.suggest(lookalike, already = emptyList()).isEmpty())

        val already = listOf(OwnedAccount(1, "E-ZPass", listOf("51185"), listOf("e-zpassny.com")))
        assertTrue(OwnedCandidates.suggest(official, already).isEmpty())
    }
}
