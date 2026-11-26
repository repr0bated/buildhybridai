package com.example.callguardian

import com.example.callguardian.jev.JevTruthTable
import com.example.callguardian.jev.JevTruthTable.Signal
import com.example.callguardian.jev.JevTruthTable.Verdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JevTruthTableTest {
    private fun probs(vararg yes: Pair<Signal, Double>): Map<Signal, Double> =
        Signal.entries.associateWith { 0.05 } + yes.toMap()

    @Test fun thresholds() {
        assertEquals(JevTruthTable.Reading.YES, JevTruthTable.read(0.7))
        assertEquals(JevTruthTable.Reading.NO, JevTruthTable.read(0.3))
        assertEquals(JevTruthTable.Reading.UNSETTLED, JevTruthTable.read(0.5))
        assertEquals(JevTruthTable.Reading.UNSETTLED, JevTruthTable.read(0.69))
    }

    @Test fun clearWhenNothingFires() = assertEquals(Verdict.CLEAR, JevTruthTable.decide(probs(), false).verdict)

    @Test fun codeRequestWithCompanyClaimIsScam() =
        assertEquals(Verdict.SCAM, JevTruthTable.decide(probs(Signal.ASKS_SHARE_CODE to 0.95, Signal.CLAIMS_ORGANIZATION to 0.9), false).verdict)

    @Test fun loginDetailsAloneIsScam() = assertEquals(Verdict.SCAM, JevTruthTable.decide(probs(Signal.ASKS_LOGIN_DETAILS to 0.8), true).verdict)

    @Test fun linkPlusDeadlineIsScam() =
        assertEquals(Verdict.SCAM, JevTruthTable.decide(probs(Signal.ASKS_OPEN_LINK to 0.9, Signal.DEADLINE_PRESSURE to 0.85), false).verdict)

    @Test fun moneyOfferPlusOtherAppIsScam() =
        assertEquals(Verdict.SCAM, JevTruthTable.decide(probs(Signal.OFFERS_MONEY to 0.9, Signal.MOVES_TO_OTHER_APP to 0.9), false).verdict)

    @Test fun strongSignalFromStrangerIsSuspiciousButNotFromContact() {
        assertEquals(Verdict.SUSPICIOUS, JevTruthTable.decide(probs(Signal.ASKS_PAYMENT to 0.9), false).verdict)
        assertEquals(Verdict.CLEAR, JevTruthTable.decide(probs(Signal.ASKS_PAYMENT to 0.9), true).verdict)
    }

    @Test fun linkAloneIsClear() = assertEquals(Verdict.CLEAR, JevTruthTable.decide(probs(Signal.ASKS_OPEN_LINK to 0.95), false).verdict)

    @Test fun unsettledThatFlipsTheRowIsUnsettled() {
        val r = JevTruthTable.decide(probs(Signal.ASKS_OPEN_LINK to 0.9, Signal.DEADLINE_PRESSURE to 0.5), false)
        assertEquals(Verdict.UNSETTLED, r.verdict)
        assertEquals(listOf(Signal.DEADLINE_PRESSURE), r.unsettled)
    }

    @Test fun unsettledThatDoesNotMatterKeepsVerdict() {
        val r = JevTruthTable.decide(probs(Signal.ASKS_LOGIN_DETAILS to 0.9, Signal.STRANGER_OPENER to 0.5), false)
        assertEquals(Verdict.SCAM, r.verdict)
        assertEquals(listOf(Signal.STRANGER_OPENER), r.unsettled)
    }

    /** Exhaustive: every yes/no/unsettled combination of the ten signals gives a verdict. */
    @Test fun everyCombinationIsDefined() {
        val values = listOf(0.1, 0.5, 0.9)
        var n = 0
        fun rec(i: Int, acc: Map<Signal, Double>) {
            if (i == Signal.entries.size) { JevTruthTable.decide(acc, false); JevTruthTable.decide(acc, true); n++; return }
            values.forEach { rec(i + 1, acc + (Signal.entries[i] to it)) }
        }
        rec(0, emptyMap())
        assertEquals(59049, n)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingAnswerIsRejected() { JevTruthTable.decide(probs() - Signal.ASKS_PAYMENT, false) }

    /** Signal ids must match the output schema and the question module in the Cloud Function. */
    @Test fun signalIdsMatchSchemaAndQuestions() {
        val props = Json.parseToJsonElement(TestAssets.read("methods/guardian.jev.check.v1.output.json")).jsonObject["properties"]!!.jsonObject.keys
        Signal.entries.forEach { assertTrue(it.id, it.id in props) }
        val q = listOf(File("../functions/src/questions.ts"), File("functions/src/questions.ts")).first { it.exists() }.readText()
        Signal.entries.forEach { assertTrue("questions.ts lacks ${it.id}", Regex("\\b${it.id}:\\s*\\{").containsMatchIn(q)) }
    }

    @Test fun reputableOfficialNeedsOfficialDomain() {
        val p = probs(Signal.REPUTABLE_OFFICIAL to 0.95, Signal.CLAIMS_ORGANIZATION to 0.9)
        // Reputable is a strong negative only when the official domain also checks out.
        val noDomain = JevTruthTable.decide(p, JevTruthTable.Context(senderIsContact = false, officialDomainChecksOut = false))
        assertEquals(Verdict.CLEAR, noDomain.verdict)
        assertEquals("no scam combination", noDomain.rule)
        val withDomain = JevTruthTable.decide(p, JevTruthTable.Context(senderIsContact = false, officialDomainChecksOut = true))
        assertEquals(Verdict.CLEAR, withDomain.verdict)
        assertEquals("reputable organization and official domain, no unusual ask", withDomain.rule)
    }

    @Test fun ownedPlusOfficialIsClear() {
        val r = JevTruthTable.decide(
            probs(),
            JevTruthTable.Context(senderIsContact = false, officialDomainChecksOut = true, fromOwnedAccount = true),
        )
        assertEquals(Verdict.CLEAR, r.verdict)
        assertEquals("owned account (sender and official domain match)", r.rule)
    }

    @Test fun lookalikePlusBrandIsScamEvenIfOwnedOrReputable() {
        val r = JevTruthTable.decide(
            probs(Signal.CLAIMS_ORGANIZATION to 0.95, Signal.REPUTABLE_OFFICIAL to 0.9),
            JevTruthTable.Context(senderIsContact = false, officialDomainChecksOut = false, lookalikeDomain = true, fromOwnedAccount = true),
        )
        assertEquals(Verdict.SCAM, r.verdict)
        assertEquals("brand claim + lookalike domain", r.rule)
    }

    @Test fun fromOwnedIsContextNotAMissingJevAnswer() {
        // 10 Jev signals only; from_owned_account is app-answered context.
        JevTruthTable.decide(probs(), JevTruthTable.Context(senderIsContact = false, fromOwnedAccount = true))
    }
}
