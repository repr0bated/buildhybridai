package com.example.callguardian

import com.example.callguardian.lookup.LookupCostEstimate
import com.example.callguardian.lookup.LookupProvider
import com.example.callguardian.lookup.ReverseLookup
import com.example.callguardian.lookup.generated.twilio.LookupResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.example.callguardian.model.LookupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseLookupTest {

    @Test fun e164NormalizesTenDigitUsNumbers() {
        assertEquals("+15551234567", ReverseLookup.toE164("(555) 123-4567"))
        assertEquals("+15551234567", ReverseLookup.toE164("15551234567"))
        assertEquals("+15551234567", ReverseLookup.toE164("+1 (555) 123-4567"))
        assertEquals("+15551234567", ReverseLookup.cacheKey("5551234567"))
        assertNull(ReverseLookup.toE164("hidden"))
        assertNull(ReverseLookup.toE164("123"))
    }

    @Test fun mergeKeepsFirstNonNullFieldByProviderOrder() {
        val twilio = LookupResult(
            phoneNumber = "+15551234567",
            displayName = "TWILIO NAME",
            carrier = null,
            lineType = "mobile",
            source = "Twilio",
            sourceId = LookupProvider.TWILIO.id,
        )
        val telnyx = LookupResult(
            phoneNumber = "+15551234567",
            displayName = "TELNYX NAME",
            carrier = "Verizon",
            lineType = "voip",
            source = "Telnyx",
            sourceId = LookupProvider.TELNYX.id,
        )
        val merged = ReverseLookup.merge(
            listOf(telnyx, twilio),
            order = listOf(LookupProvider.TWILIO.id, LookupProvider.TELNYX.id),
        )
        assertEquals("TWILIO NAME", merged.displayName.value)
        assertEquals(LookupProvider.TWILIO.displayName, merged.displayName.sourceLabel)
        assertEquals("Verizon", merged.carrier.value)
        assertEquals(LookupProvider.TELNYX.displayName, merged.carrier.sourceLabel)
        assertEquals("Mobile", merged.lineType.value)
        assertEquals(LookupProvider.TWILIO.displayName, merged.lineType.sourceLabel)
    }

    @Test fun confirmMessageMarksEstimateAndSkipsCached() {
        val estimate = LookupCostEstimate(
            providerId = LookupProvider.TWILIO.id,
            providerLabel = LookupProvider.TWILIO.displayName,
            uniqueCount = 12,
            cachedCount = 2,
            toQueryCount = 10,
            usdPerLookup = LookupProvider.TWILIO.usdPerLookup,
            estimatedUsd = 10 * LookupProvider.TWILIO.usdPerLookup,
            priceNote = LookupProvider.TWILIO.priceNote,
            priceSourceUrl = LookupProvider.TWILIO.priceSourceUrl,
        )
        val msg = estimate.confirmMessage()
        assertTrue(msg.contains("Look up 10 numbers"))
        assertTrue(msg.contains("Skipped 2 already cached"))
        assertTrue(msg.contains("This is an estimate"))
        assertTrue(msg.contains("$0.18"))
        assertFalse(msg.contains("Spy Dialer"))
    }

    @Test fun twilioGeneratedModelsParseOfficialShape() {
        val json = """
            {"calling_country_code":"1","country_code":"US","phone_number":"+15551234567",
             "national_format":"(555) 123-4567","valid":true,
             "caller_name":{"caller_name":"ACME","caller_type":"BUSINESS","error_code":null},
             "line_type_intelligence":{"type":"mobile","carrier_name":"Verizon","mobile_country_code":"310"}}
        """.trimIndent()
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val parsed = moshi.adapter(LookupResponse::class.java).fromJson(json)!!
        assertEquals("+15551234567", parsed.phoneNumber)
        assertEquals(true, parsed.valid)
        assertEquals("ACME", parsed.callerName?.callerName)
        assertEquals("mobile", parsed.lineTypeIntelligence?.type_)
        assertEquals("Verizon", parsed.lineTypeIntelligence?.carrierName)
    }
}
