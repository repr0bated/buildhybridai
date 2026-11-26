package com.example.callguardian

import com.example.callguardian.data.db.AiStatus
import com.example.callguardian.data.db.ReceiptEntity
import com.example.callguardian.data.db.ScreeningEventEntity
import com.example.callguardian.ui.ReceiptPresentation
import com.example.callguardian.ui.ReceiptTone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptPresentationTest {
    private fun receipt(methodId: String, status: String = "OK", input: String? = null, output: String? = null, eventId: Long? = 1) =
        ReceiptEntity(
            id = 1, eventId = eventId, methodId = methodId, registryVersion = "2026.11.4",
            inputSchema = "i", outputSchema = "o", inputJson = input ?: "{}", outputJson = output,
            executedOn = "device", proposedBy = "USER", dispatch = "USER_CONFIRMED", status = status,
        )

    private fun obj(vararg pairs: Pair<String, String>) =
        JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    @Test fun subjectPrefersNameAndMessageOverMethodId() {
        val ev = ScreeningEventEntity(id = 1, channel = "SMS", number = "5550100", displayName = "Ann", body = "Your package is held at the depot", aiStatus = AiStatus.DONE)
        val v = ReceiptPresentation.present(receipt("guardian.call.block@v1"), "Block number", ev, obj("number" to "5550100", "reason" to "scam"), null)
        assertEquals("Ann · Your package is held at the depot", v.subject)
        assertEquals("Block number", v.methodTitle)
        assertFalse(v.subject.contains("guardian.call.block"))
    }

    @Test fun callSubjectUsesNameAndCallType() {
        val ev = ScreeningEventEntity(id = 2, channel = "CALL", number = "5550199", displayName = "Bank", body = null, lookupSummary = "call log: missed · 0 s", aiStatus = AiStatus.DONE)
        val v = ReceiptPresentation.present(receipt("guardian.call.review@v1"), "Review call", ev, obj("number" to "5550199", "cachedName" to "Bank", "callType" to "MISSED", "durationSec" to "0"), null)
        assertTrue(v.subject.startsWith("Bank"))
        assertTrue(v.subject.contains("missed"))
        assertEquals("Reviewed", v.outcome)
    }

    @Test fun outcomesFollowMethodAndMuteMode() {
        assertEquals("Blocked", ReceiptPresentation.outcome(receipt("guardian.call.block@v1"), null, null))
        assertEquals("Allowed", ReceiptPresentation.outcome(receipt("guardian.call.allow@v1"), null, null))
        assertEquals("Quarantined", ReceiptPresentation.outcome(receipt("guardian.sms.quarantine@v1"), null, null))
        assertEquals("Stopped", ReceiptPresentation.outcome(receipt("guardian.call.reject@v1"), null, null))
        assertEquals("Unsubscribed", ReceiptPresentation.outcome(receipt("guardian.sms.unsubscribe@v1"), obj("service" to "Cash App"), null))
        assertEquals("Muted", ReceiptPresentation.outcome(receipt("guardian.sms.mute@v1"), obj("mode" to "MUTE"), null))
        assertEquals("Kept", ReceiptPresentation.outcome(receipt("guardian.sms.mute@v1"), obj("mode" to "KEEP"), null))
        assertEquals("Hidden", ReceiptPresentation.outcome(receipt("guardian.privacy.set_private@v1"), null, null))
    }

    @Test fun statusLabelsAndTones() {
        assertEquals("OK", ReceiptPresentation.statusLabel("OK"))
        assertEquals("Denied", ReceiptPresentation.statusLabel("DENIED"))
        assertEquals("Failed", ReceiptPresentation.statusLabel("FAILED"))
        assertEquals("Invalid", ReceiptPresentation.statusLabel("INVALID_INPUT"))
        assertEquals(ReceiptTone.ALLOW, ReceiptPresentation.statusTone("OK"))
        assertEquals(ReceiptTone.WARN, ReceiptPresentation.statusTone("DENIED"))
        assertEquals(ReceiptTone.BLOCK, ReceiptPresentation.outcomeTone(receipt("guardian.call.block@v1"), null, null))
        assertEquals(ReceiptTone.ALLOW, ReceiptPresentation.outcomeTone(receipt("guardian.call.allow@v1"), null, null))
    }

    @Test fun deniedStillHasOutcomeWordNotAsSoleBadge() {
        val v = ReceiptPresentation.present(receipt("guardian.sms.quarantine@v1", status = "DENIED"), "Quarantine text", null, obj("number" to "72975"), null)
        assertEquals("Quarantined", v.outcome)
        assertEquals("Denied", v.status)
        assertEquals("72975", v.subject)
    }

    @Test fun unsubscribeSubjectUsesServiceOrRecipients() {
        val input = JsonObject(mapOf(
            "service" to JsonPrimitive("Cash App"),
            "recipients" to JsonArray(listOf(JsonPrimitive("443634"), JsonPrimitive("72975"))),
        ))
        val v = ReceiptPresentation.present(receipt("guardian.sms.unsubscribe@v1"), "Text STOP", null, input, null)
        assertEquals("Cash App", v.subject)
        assertEquals("Unsubscribed", v.outcome)
        assertEquals("OK", v.status)
    }
}
