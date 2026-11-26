package com.example.callguardian

import com.example.callguardian.ai.GeminiDeveloperClient
import com.example.callguardian.ai.Prompts
import com.example.callguardian.jev.JevQuestions
import com.example.callguardian.model.Channel
import com.example.callguardian.policy.SchemaValidator
import com.example.callguardian.schemaui.FieldKind
import com.example.callguardian.schemaui.SchemaField
import com.example.callguardian.schemaui.SchemaForm
import com.example.callguardian.screening.OfficialDomains
import com.example.callguardian.screening.RuleScan
import com.example.callguardian.screening.VerdictFields
import com.example.callguardian.model.GuardianMethod
import com.example.callguardian.model.RiskLevel
import com.example.callguardian.model.ScamCategory
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VerdictFieldsTest {

    private val schema = SchemaValidator(TestAssets.read("scam_verdict.schema.json"), "scam_verdict.schema.json")
    private val rules = RuleScan(0, emptyList(), RiskLevel.LOW, ScamCategory.NONE, GuardianMethod.NONE, 0)

    @Before fun loadDomains() {
        OfficialDomains.load(TestAssets.read("official_domains.json"))
    }

    @Test fun schemaRequiresYesNoUnknownFields() {
        val json = TestAssets.read("scam_verdict.schema.json")
        assertTrue(json.contains("\"reputable_official\""))
        assertTrue(json.contains("\"held_account\""))
        val missing = """{"verdict":"SCAM","confidence":91,"reasons":["Look-alike USPS link"]}"""
        assertFalse(schema.validate(missing).valid)
        val complete = """{"verdict":"SCAM","confidence":91,"reasons":["Look-alike USPS link"],"reputable_official":"NO","held_account":"NO"}"""
        assertTrue(schema.validate(complete).errors.toString(), schema.validate(complete).valid)
    }

    @Test fun onDevicePromptAsksBothQuestions() {
        assertEquals("2026.11.2", JevQuestions.QUESTIONS_VERSION)
        val prompt = Prompts.triage(
            Channel.SMS, "+15551234567", isContact = false,
            body = "Your USPS package is waiting https://usps-help.top/track",
            lookup = null, rules = rules,
        )
        assertTrue(prompt.contains("reputable_official"))
        assertTrue(prompt.contains("held_account"))
        assertTrue(prompt.contains("YES, NO or UNKNOWN") || (prompt.contains("YES") && prompt.contains("NO") && prompt.contains("UNKNOWN")))
        assertTrue(prompt.contains("official domain"))
        assertTrue(prompt.contains("look-alike") || prompt.contains("look-alike domain") || prompt.contains("look-alike"))
        assertTrue(prompt.contains("owned"))
    }

    @Test fun fillsOmittedFieldsFromLocalSignalsAndThenValidates() {
        val raw = """{"verdict":"SCAM","confidence":91,"reasons":["Look-alike USPS link"]}"""
        val official = VerdictFields.localReputableOfficial("Your USPS package is waiting https://usps-help.top/track")
        assertEquals(VerdictFields.NO, official)
        val held = VerdictFields.localHeldAccount(isContact = false, ownedSender = false, ownedDomain = false)
        assertEquals(VerdictFields.NO, held)
        val filled = VerdictFields.fillOmitted(raw, official, held)
        val report = schema.validate(filled)
        assertTrue(report.errors.toString(), report.valid)
        assertTrue(filled.contains("\"reputable_official\":\"NO\""))
        assertTrue(filled.contains("\"held_account\":\"NO\""))
    }

    @Test fun officialDomainIsYesAndDoesNotOverwriteAValidModelAnswer() {
        val body = "PayPal: confirm your account https://www.paypal.com/signin"
        assertEquals(VerdictFields.YES, VerdictFields.localReputableOfficial(body))
        val raw = """{"verdict":"CLEAR","confidence":70,"reasons":["Official PayPal link"],"reputable_official":"YES","held_account":"UNKNOWN"}"""
        val filled = VerdictFields.fillOmitted(raw, VerdictFields.NO, VerdictFields.NO)
        assertTrue(filled.contains("\"reputable_official\":\"YES\""))
        assertTrue(filled.contains("\"held_account\":\"UNKNOWN\""))
    }

    @Test fun heldAccountYesForOwnedNoForStrangerUnknownForContact() {
        assertEquals(VerdictFields.YES, VerdictFields.localHeldAccount(isContact = true, ownedSender = true, ownedDomain = false))
        assertEquals(VerdictFields.YES, VerdictFields.localHeldAccount(isContact = false, ownedSender = false, ownedDomain = true))
        assertEquals(VerdictFields.UNKNOWN, VerdictFields.localHeldAccount(isContact = true, ownedSender = false, ownedDomain = false))
        assertEquals(VerdictFields.NO, VerdictFields.localHeldAccount(isContact = false, ownedSender = false, ownedDomain = false))
    }

    @Test fun uiShowsYesNoUnknownNeverNoAnswer() {
        val field = SchemaField("reputable_official", "Reputable official", null, FieldKind.ENUM, required = true, readOnly = true)
        assertEquals("Unknown", SchemaForm.display(field, null))
        assertEquals("Unknown", SchemaForm.display(field, JsonNull))
        assertEquals("Yes", SchemaForm.display(field, JsonPrimitive("YES")))
        assertEquals("No", SchemaForm.display(field, JsonPrimitive("NO")))
        assertEquals("Unknown", SchemaForm.display(field, JsonPrimitive("UNKNOWN")))
        assertEquals("Unknown", SchemaForm.display(field, JsonPrimitive("no answer")))
        assertEquals("Unknown", VerdictFields.display(null))
        assertFalse(SchemaForm.display(field, null).equals("no answer", ignoreCase = true))
        assertFalse(SchemaForm.display(field, JsonNull).contains("no answer", ignoreCase = true))
    }

    @Test fun byokResponseParserReadsCandidateText() {
        val payload = """{"candidates":[{"content":{"parts":[{"text":"hello from gemini"}]}}]}"""
        assertEquals("hello from gemini", GeminiDeveloperClient.textOf(payload))
    }
}
