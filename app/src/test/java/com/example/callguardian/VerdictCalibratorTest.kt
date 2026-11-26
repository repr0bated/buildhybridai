package com.example.callguardian

import com.example.callguardian.accounts.OwnedAccount
import com.example.callguardian.model.Channel
import com.example.callguardian.model.RiskLevel
import com.example.callguardian.screening.HeuristicScanner
import com.example.callguardian.screening.ScanInput
import com.example.callguardian.screening.VerdictCalibrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictCalibratorTest {

    private fun decide(number: String?, body: String, isContact: Boolean = false, isTrusted: Boolean = false, shortCodeHistory: Boolean = false, owned: List<OwnedAccount> = emptyList(), modelConf: Int? = null): VerdictCalibrator.Decision {
        val mine = owned.firstOrNull { it.matchesSender(number) }
        val input = ScanInput(Channel.SMS, number, body, isContact = isContact, isTrusted = isTrusted, shortCodeHistory = shortCodeHistory, owned = mine, allOwned = owned)
        val rules = HeuristicScanner.scan(input)
        return VerdictCalibrator.fuse(rules, VerdictCalibrator.features(input), modelConfidence = modelConf, channel = Channel.SMS)
    }

    @Test fun officialEzPassDomainIsClearOrUnsettledNotScam() {
        val body = "E-ZPass: You have a \$4.15 unpaid toll. Pay today to avoid a late fee: https://www.e-zpassny.com/en/home/index.shtml"
        val d = decide("+13055550188", body)
        assertTrue("band=${d.band} score=${d.score} reasons=${d.reasons}", d.band != VerdictCalibrator.Band.SCAM)
        assertTrue(d.band == VerdictCalibrator.Band.CLEAR || d.band == VerdictCalibrator.Band.UNSETTLED)
        assertTrue(d.confidence < 100)
        assertTrue(d.reasons.any { it.contains("e-zpassny.com") })
    }

    @Test fun lookalikeEzPassDomainIsScam() {
        val body = "E-ZPass FINAL NOTICE: You have an unpaid toll of \$4.15. Pay today to avoid a \$50 late fee: http://ezpass-pay.info/settle"
        val d = decide("+13055550188", body)
        assertEquals(VerdictCalibrator.Band.SCAM, d.band)
        assertTrue(d.risk >= RiskLevel.HIGH)
        assertTrue(d.reasons.any { it.contains("look-alike", ignoreCase = true) || it.contains("Look-alike") || it.contains("ezpass-pay.info") })
        assertTrue(d.confidence <= VerdictCalibrator.MAX_CONFIDENCE)
        assertTrue(d.confidence < 100)
    }

    @Test fun ownedSenderAndOfficialDomainIsClearOwned() {
        val owned = listOf(OwnedAccount(1, "E-ZPass", listOf("51185"), listOf("e-zpassny.com", "ezpassnj.com")))
        val body = "E-ZPass: your monthly statement is ready. View it at https://www.e-zpassny.com/account"
        val d = decide("51185", body, owned = owned)
        assertEquals(VerdictCalibrator.Band.CLEAR, d.band)
        assertEquals(RiskLevel.SAFE, d.risk)
        assertEquals("E-ZPass", d.ownedLabel)
        assertTrue(d.reasons.any { it.contains("Owned account") })
    }

    @Test fun ownedBrandFromUnknownSenderIsImpersonation() {
        val owned = listOf(OwnedAccount(1, "E-ZPass", listOf("51185"), listOf("e-zpassny.com")))
        val body = "E-ZPass FINAL NOTICE: unpaid toll. Pay now: http://ezpass-pay.info/settle"
        val d = decide("+19995550123", body, owned = owned)
        assertEquals(VerdictCalibrator.Band.SCAM, d.band)
        assertTrue(d.reasons.any { it.contains("Impersonation") })
    }

    @Test fun neverShowsOneHundredEvenIfModelIsCertain() {
        val body = "E-ZPass FINAL NOTICE: unpaid toll http://ezpass-pay.info/settle"
        val d = decide("+13055550188", body, modelConf = 100)
        assertTrue(d.confidence <= VerdictCalibrator.MAX_CONFIDENCE)
        assertNotEquals(100, d.confidence)
        assertEquals(92, VerdictCalibrator.capModelConfidence(100))
    }

    @Test fun trustedOrContactLowersOfficialTollToClear() {
        val body = "E-ZPass: You have a \$4.15 unpaid toll. Pay at https://ezpassnj.com/en/home"
        val d = decide("+18005551212", body, isContact = true)
        assertEquals(VerdictCalibrator.Band.CLEAR, d.band)
    }

    @Test fun cashAppCodeTextIsClearCodeNeverScam() {
        val body = "Your Cash App code is 123456"
        val d = decide("28581", body)
        assertEquals(VerdictCalibrator.Band.CLEAR, d.band)
        assertEquals(RiskLevel.SAFE, d.risk)
        assertEquals("Code", d.codeLabel)
        assertTrue(d.reasons.any { it.contains("CLEAR (Code)") })
        assertTrue(d.confidence < 100)
        val high = decide("28581", body, modelConf = 100)
        assertEquals(VerdictCalibrator.Band.CLEAR, high.band)
        assertNotEquals(VerdictCalibrator.Band.SCAM, high.band)
    }

    @Test fun cashAppDashedCodeTextIsClearCode() {
        // Real Cash App login codes are formatted like 123-456, not a bare 6-digit run.
        val body = "Cash App: This code is for you and only you. Code: 123-456. To prevent fraud, never share it anywhere or with anyone, including people posing as Cash App."
        val d = decide("28581", body)
        assertEquals("band=${d.band} reasons=${d.reasons}", VerdictCalibrator.Band.CLEAR, d.band)
        assertEquals(RiskLevel.SAFE, d.risk)
        assertEquals("Code", d.codeLabel)
        assertTrue(d.reasons.any { it.contains("CLEAR (Code)") })
        val rules = HeuristicScanner.scan(ScanInput(Channel.SMS, "28581", body))
        assertTrue("flags=${rules.flags}", rules.flags.none { it.id == "otp" || it.id == "money" })
        assertTrue(rules.flags.any { it.id == "verification_code" })
    }

    @Test fun cashAppNeverShareWarningIsStillClearCode() {
        val body = "Cash App: This code is for you and only you. Code: 123456. To prevent fraud, never share it anywhere or with anyone."
        val d = decide("28581", body)
        assertEquals("band=${d.band} reasons=${d.reasons}", VerdictCalibrator.Band.CLEAR, d.band)
        assertEquals("Code", d.codeLabel)
        assertTrue(d.reasons.none { it.contains("Asks you to share a code") })
        val rules = HeuristicScanner.scan(ScanInput(Channel.SMS, "28581", body))
        assertTrue("flags=${rules.flags}", rules.flags.none { it.id == "otp" || it.id == "money" })
        assertTrue(rules.flags.any { it.id == "verification_code" })
    }

    @Test fun otpThatAsksToShareTheCodeIsFlagged() {
        val body = "Your Cash App code is 123456. Reply with this code to confirm your account."
        val d = decide("28581", body)
        assertNotEquals(VerdictCalibrator.Band.CLEAR, d.band)
        assertTrue("band=${d.band} reasons=${d.reasons}", d.band == VerdictCalibrator.Band.SCAM || d.band == VerdictCalibrator.Band.UNSETTLED)
        assertEquals(null, d.codeLabel)
        val rules = HeuristicScanner.scan(ScanInput(Channel.SMS, "28581", body))
        assertTrue(rules.flags.any { it.id == "otp" })
    }

    @Test fun otpWithPhishingLinkIsNotClearCode() {
        val body = "Your Cash App code is 123456. Verify at http://cashapp-secure.xyz/login"
        val d = decide("28581", body)
        assertNotEquals("Code", d.codeLabel)
        assertTrue("band=${d.band} reasons=${d.reasons}", d.band != VerdictCalibrator.Band.CLEAR)
    }
}
