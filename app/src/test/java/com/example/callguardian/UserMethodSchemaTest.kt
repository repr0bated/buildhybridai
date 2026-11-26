package com.example.callguardian

import com.example.callguardian.methods.CallLogItem
import com.example.callguardian.methods.CallLogType
import com.example.callguardian.methods.UserMethod
import com.example.callguardian.methods.UserMethodInputs
import com.example.callguardian.model.MessageSource
import com.example.callguardian.subscriptions.InboxSms
import com.example.callguardian.subscriptions.LinkSafety
import com.example.callguardian.subscriptions.SubscriptionDetector
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMethodSchemaTest {
    private val reg = TestAssets.registry
    private fun m(u: UserMethod) = reg.byUser(u)!!

    @Test fun everyUserMethodIsRegisteredAndNotProposable() {
        UserMethod.entries.forEach { u ->
            val r = assertNotNullR(reg.byUser(u), u.name)
            assertFalse(r.proposable)
        }
        // The model can never propose a user-only method.
        UserMethod.entries.forEach { u -> assertTrue(reg.proposableMethods.none { it.spec.alias == u.alias }) }
    }
    private fun <T> assertNotNullR(v: T?, msg: String): T { assertNotNull(msg, v); return v!! }

    @Test fun reviewCallInputValidates() {
        val c = CallLogItem(7, "+1 (555) 010-0199", "Ann", false, CallLogType.MISSED, 0, 1_700_000_000_000, 4, 2)
        val r = m(UserMethod.REVIEW_CALL).inputValidator.validate(UserMethodInputs.reviewCall(c).toString())
        assertTrue(r.errors.toString(), r.valid)
        val hidden = c.copy(number = null, cachedName = null)
        assertTrue(m(UserMethod.REVIEW_CALL).inputValidator.validate(UserMethodInputs.reviewCall(hidden).toString()).valid)
    }

    @Test fun jevCheckInputAndOutputValidate() {
        val inp = UserMethodInputs.jevCheck(3, "72975", false, "Your package is held: https://usps-help.top/[path]", "lab_sample")
        val ri = m(UserMethod.JEV_CHECK).inputValidator.validate(inp.toString())
        assertTrue(ri.errors.toString(), ri.valid)
        val out = UserMethodInputs.jevOutput(3, mapOf(
            "asks_payment" to 0.92, "deadline_pressure" to 0.81, "claims_organization" to 0.97, "asks_open_link" to 0.99,
            "asks_share_code" to 0.02, "asks_login_details" to 0.1, "offers_money" to 0.01, "moves_to_other_app" to 0.0, "stranger_opener" to 0.03,
        ), false, "jev-latest", "2026.11.1", 210, 900)
        val ro = m(UserMethod.JEV_CHECK).outputValidator.validate(out.toString())
        assertTrue(ro.errors.toString(), ro.valid)
        assertEquals(JsonPrimitive("SCAM"), out["verdict"])
    }

    @Test fun jevCheckRejectsRawPhoneNumberAsSender() {
        val bad = buildJsonObject { put("eventId", JsonPrimitive(1)); put("sender", JsonPrimitive("+15550100199")); put("redactedText", JsonPrimitive("hi")); put("source", JsonPrimitive("sms")) }
        assertFalse(m(UserMethod.JEV_CHECK).inputValidator.validate(bad.toString()).valid)
    }

    private val promo = listOf(
        InboxSms(1, "72975", "Acme: 30% off today! Shop acme.com/sale Reply STOP to opt out. Msg&data rates may apply", 2000),
        InboxSms(2, "72975", "Acme: new arrivals. Manage texts at https://acme.com/prefs or text STOP to end", 3000),
        InboxSms(3, "+15550100111", "See you at 6?", 4000),
    )

    @Test fun subscriptionGroupingAndKeyword() {
        val g = SubscriptionDetector.group(promo)
        assertEquals(1, g.size)
        assertEquals("72975", g[0].sender); assertEquals(2, g[0].count); assertEquals("STOP", g[0].keyword)
        assertEquals("Acme", g[0].brand)
        val r = m(UserMethod.UNSUBSCRIBE_SMS).inputValidator.validate(UserMethodInputs.unsubscribe(g[0]).toString())
        assertTrue(r.errors.toString(), r.valid)
    }

    @Test fun personalTextsAndBareShortCodesAreNotSubscriptions() {
        assertFalse(SubscriptionDetector.isSubscription("+15550100111", "See you at 6?"))
        assertFalse(SubscriptionDetector.isSubscription("22000", "Your verification code is 123456"))
        assertTrue(SubscriptionDetector.isSubscription("22000", "Deals inside! Reply STOP to cancel"))
    }

    @Test fun unsubscribeLinkIsPickedAndBrandMatches() {
        val links = SubscriptionDetector.links("Acme: deals. Unsubscribe: https://acme.com/u/abc or shop https://acme.com/sale")
        val best = SubscriptionDetector.bestLink(links)!!
        assertEquals("https://acme.com/u/abc", best.url)
        val safe = SubscriptionDetector.linkSafety(best, "Acme", com.example.callguardian.model.RiskLevel.SAFE)
        assertFalse(safe.warning, safe.suspicious)
        val inp = UserMethodInputs.manageSubscription("72975", "sms", best, "RULES", safe)
        assertTrue(m(UserMethod.MANAGE_SUBSCRIPTION).inputValidator.validate(inp.toString()).valid)
    }

    @Test fun mismatchedOrShortenedLinksAreSuspicious() {
        val l = SubscriptionDetector.links("Acme: to stop texts visit https://acme-billing.top/optout")[0]
        val s = SubscriptionDetector.linkSafety(l, "Acme", com.example.callguardian.model.RiskLevel.SAFE)
        assertTrue(s.suspicious); assertNotNull(s.warning)
        val b = SubscriptionDetector.links("Acme: unsubscribe bit.ly/x1y2")[0]
        assertTrue(SubscriptionDetector.linkSafety(b, "Acme", com.example.callguardian.model.RiskLevel.SAFE).suspicious)
        val scammy = SubscriptionDetector.linkSafety(SubscriptionDetector.links("unsubscribe https://acme.com/u")[0], "Acme", com.example.callguardian.model.RiskLevel.HIGH)
        assertTrue(scammy.suspicious)
    }

    @Test fun muteAndIntegrationInputsValidate() {
        for (mode in listOf("MUTE", "KEEP", "CLEAR")) {
            val r = m(UserMethod.MUTE_SENDER).inputValidator.validate(UserMethodInputs.mute("72975", "sms", mode).toString())
            assertTrue("$mode ${r.errors}", r.valid)
        }
        assertTrue(m(UserMethod.MUTE_SENDER).inputValidator.validate(UserMethodInputs.mute("Acme Deals", "whatsapp", "MUTE").toString()).valid)
        val r = m(UserMethod.SET_INTEGRATION).inputValidator.validate(UserMethodInputs.setIntegration("google_voice", true, "installed · access granted").toString())
        assertTrue(r.errors.toString(), r.valid)
        assertFalse(m(UserMethod.SET_INTEGRATION).inputValidator.validate(UserMethodInputs.setIntegration("telegram_x", true, "").toString()).valid)
    }

    @Test fun voiceSourceDoesNotOfferSms() {
        val g = SubscriptionDetector.group(listOf(InboxSms(1, "Acme", "Acme: sale. Reply STOP to opt out", 1, MessageSource.GOOGLE_VOICE)))
        assertEquals(MessageSource.GOOGLE_VOICE, g.single().source)
    }

    @Test fun noLinkMeansNoBest() = assertNull(SubscriptionDetector.bestLink(SubscriptionDetector.links("Acme: shop acme.com today")))

    @Test fun setAndClearPrivateInputsValidate() {
        val set = UserMethodInputs.setPrivate("EVENT", "Hide Ann", eventId = 3, address = "5550100", source = "sms")
        val rs = m(UserMethod.SET_PRIVATE).inputValidator.validate(set.toString())
        assertTrue(rs.errors.toString(), rs.valid)
        val thread = UserMethodInputs.setPrivate("THREAD", "Hide texts from 72975", address = "72975", source = "sms")
        assertTrue(m(UserMethod.SET_PRIVATE).inputValidator.validate(thread.toString()).valid)
        val clear = UserMethodInputs.clearPrivate("CONTACT", "Show this contact again", address = "5550100", source = "call_log")
        val rc = m(UserMethod.CLEAR_PRIVATE).inputValidator.validate(clear.toString())
        assertTrue(rc.errors.toString(), rc.valid)
        assertFalse(m(UserMethod.SET_PRIVATE).inputValidator.validate(UserMethodInputs.setPrivate("FOLDER", "x").toString()).valid)
    }

    @Test fun cashAppSendersMergeIntoOneService() {
        val rows = listOf(
            InboxSms(1, "443634", "Cash App: you paid \$4. Reply STOP to opt out", 1),
            InboxSms(2, "72975", "Cash App: your code is on the way. Reply STOP to end", 2),
        )
        val g = SubscriptionDetector.group(rows)
        assertEquals(1, g.size)
        assertTrue(g[0].senders.toSet().containsAll(setOf("443634", "72975")))
        assertEquals(2, g[0].count)
        val r = m(UserMethod.UNSUBSCRIBE_SMS).inputValidator.validate(UserMethodInputs.unsubscribe(g[0]).toString())
        assertTrue(r.errors.toString(), r.valid)
    }
}

