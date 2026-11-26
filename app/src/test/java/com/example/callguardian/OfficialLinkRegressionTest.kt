package com.example.callguardian

import com.example.callguardian.model.Channel
import com.example.callguardian.model.RiskLevel
import com.example.callguardian.model.ScamCategory
import com.example.callguardian.screening.HeuristicScanner
import com.example.callguardian.screening.OfficialDomains
import com.example.callguardian.screening.ScanInput
import com.example.callguardian.screening.VerdictCalibrator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Redacted fixtures from Jeremy's inbox (hosts kept; digits and names removed).
 * A link alone is never phishing. Official brand domains and first-party shorteners
 * are a positive signal. Look-alikes stay impersonation.
 */
class OfficialLinkRegressionTest {

    data class Row(val id: String, val body: String, val expect: String, val hosts: List<String>)

    companion object {
        lateinit var rows: List<Row>
        lateinit var counts: Map<String, String>

        @JvmStatic @BeforeClass
        fun load() {
            val jsonFile = listOf(
                File("src/main/assets/schemas/official_domains.json"),
                File("app/src/main/assets/schemas/official_domains.json"),
            ).first { it.isFile }
            OfficialDomains.load(jsonFile.readText())
            val root = Json.parseToJsonElement(javaClass.classLoader!!.getResource("official_link_fixtures.json")!!.readText()).jsonObject
            counts = root["counts"]!!.jsonObject.mapValues { it.value.toString() }
            rows = root["messages"]!!.jsonArray.map { el ->
                val o = el.jsonObject
                Row(
                    id = o["id"]!!.jsonPrimitive.content,
                    body = o["body"]!!.jsonPrimitive.content,
                    expect = o["expect"]!!.jsonPrimitive.content,
                    hosts = o["hosts"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                )
            }
        }
    }

    private fun decide(body: String): VerdictCalibrator.Decision {
        val input = ScanInput(channel = Channel.SMS, number = "12345", body = body, isContact = false)
        val scan = HeuristicScanner.scan(input)
        return VerdictCalibrator.fuse(scan, VerdictCalibrator.features(input), channel = Channel.SMS)
    }

    @Test fun officialShortenersAreRecognized() {
        assertTrue("py.pl", OfficialDomains.isAnyOfficialHost("py.pl"))
        assertTrue("a.co", OfficialDomains.isAnyOfficialHost("a.co"))
        assertTrue("g.co", OfficialDomains.isAnyOfficialHost("g.co"))
        assertFalse("pineapple.com must not be an Apple lookalike", OfficialDomains.isLookalike("pineapple.com", "apple"))
        assertTrue(OfficialDomains.isLookalike("ezpass-pay.info", "ezpass") || OfficialDomains.lookalikeIn(listOf("ezpass-pay.info"), "ezpass") != null)
    }

    @Test fun expectClearIsNotPhishingLink() {
        val failures = mutableListOf<String>()
        var afterFp = 0
        for (row in rows.filter { it.expect == "CLEAR" }) {
            val d = decide(row.body)
            val phishing = d.category == ScamCategory.PHISHING_LINK
            val scam = d.band == VerdictCalibrator.Band.SCAM || d.risk >= RiskLevel.HIGH
            if (phishing || scam) {
                afterFp++
                failures += "${row.id} band=${d.band} risk=${d.risk} cat=${d.category} reasons=${d.reasons}"
            }
        }
        val before = 4 // counts.before_false_positives_among_expect_clear (sms-01, 08, 10, 11)
        assertEquals("before FPs among expect-CLEAR: $before; after: $afterFp\n" + failures.joinToString("\n"), 0, afterFp)
    }

    @Test fun realLookalikesStayScam() {
        for (row in rows.filter { it.expect == "SCAM" }) {
            val d = decide(row.body)
            assertTrue("${row.id} should stay SCAM/HIGH, got ${d.band} ${d.risk} ${d.reasons}", d.band == VerdictCalibrator.Band.SCAM || d.risk >= RiskLevel.HIGH || d.category == ScamCategory.IMPERSONATION)
        }
    }

    @Test fun linkAloneIsClear() {
        val d = decide("See https://telvf.com/x for details.")
        assertTrue(d.reasons.toString(), d.band != VerdictCalibrator.Band.SCAM)
        assertTrue(d.category != ScamCategory.PHISHING_LINK)
    }

    @Test fun publicShortenerAloneIsNotPhishing() {
        val alone = decide("Here's the page: https://bit.ly/abc")
        assertTrue(alone.reasons.toString(), alone.category != ScamCategory.PHISHING_LINK)
        val urgent = decide("URGENT: your account will be suspended. Pay now https://bit.ly/abc")
        assertTrue(urgent.reasons.toString(), urgent.flagsLikePhish || urgent.band != VerdictCalibrator.Band.CLEAR)
    }

    private val VerdictCalibrator.Decision.flagsLikePhish: Boolean
        get() = category == ScamCategory.PHISHING_LINK || reasons.any { it.contains("shortener", true) || it.contains("urgency", true) || it.contains("Pressure", true) }
}
