package com.example.callguardian

import com.example.callguardian.ai.Prompts
import com.example.callguardian.firebase.InferenceTelemetry
import com.example.callguardian.model.Channel
import com.example.callguardian.model.GuardianMethod
import com.example.callguardian.model.RiskLevel
import com.example.callguardian.model.ScamCategory
import com.example.callguardian.model.VerdictSource
import com.example.callguardian.screening.RuleScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceTelemetryTest {

    @Test fun mapsVerdictSourceToNanoCloudOrLocal() {
        assertEquals("nano", InferenceTelemetry.sourceOf(VerdictSource.ON_DEVICE))
        assertEquals("cloud", InferenceTelemetry.sourceOf(VerdictSource.IN_CLOUD))
        assertEquals("local", InferenceTelemetry.sourceOf(VerdictSource.RULES))
        assertEquals("local", InferenceTelemetry.sourceOf(VerdictSource.USER))
    }

    @Test fun neverAcceptsPhoneNumbersOrMessageTextAsParams() {
        assertNull(InferenceTelemetry.safeParam("+15551234567"))
        assertNull(InferenceTelemetry.safeParam("555-123-4567"))
        assertNull(InferenceTelemetry.safeParam("Your USPS package is waiting at the depot"))
        assertNull(InferenceTelemetry.safeParam("click here\nhttps://example.com"))
        assertEquals("nano", InferenceTelemetry.safeParam("nano", setOf("nano", "cloud", "local")))
        assertNull(InferenceTelemetry.safeParam("nano", setOf("cloud")))
        assertNull(InferenceTelemetry.safeParam("on_device_failed", InferenceTelemetry.CATEGORIES))
    }

    @Test fun fallbackTokensAreClosed() {
        assertEquals("none", InferenceTelemetry.fallbackOf(null))
        assertEquals("none", InferenceTelemetry.fallbackOf("used cloud because the body said hi"))
        assertEquals("on_device_failed", InferenceTelemetry.fallbackOf("on_device_failed"))
        assertEquals("background", InferenceTelemetry.fallbackOf("background"))
        assertEquals("error9", InferenceTelemetry.fallbackOf("error9"))
    }

    @Test fun categoriesMatchTheSchemaEnum() {
        for (c in ScamCategory.entries) {
            assertTrue(c.name, c.name in InferenceTelemetry.CATEGORIES)
        }
        assertFalse("15551234567" in InferenceTelemetry.CATEGORIES)
    }

    @Test fun promptVariantBIsDistinctAndStillAsksRequiredQuestions() {
        val rules = RuleScan(0, emptyList(), RiskLevel.LOW, ScamCategory.NONE, GuardianMethod.NONE, 0)
        val a = Prompts.triage(Channel.SMS, "sender", false, "hello", null, rules, variant = "A")
        val b = Prompts.triage(Channel.SMS, "sender", false, "hello", null, rules, variant = "B")
        assertEquals("A", Prompts.normalizeVariant("A"))
        assertEquals("B", Prompts.normalizeVariant("b"))
        assertEquals("A", Prompts.normalizeVariant("nope"))
        assertNotEquals(a, b)
        assertTrue(b.contains("Variant B"))
        for (p in listOf(a, b)) {
            assertTrue(p.contains("reputable_official"))
            assertTrue(p.contains("held_account"))
        }
    }
}
