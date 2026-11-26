package com.example.callguardian

import com.example.callguardian.integrations.IntegrationCatalog
import com.example.callguardian.model.MessageSource
import com.example.callguardian.notify.NotificationFields
import com.example.callguardian.notify.NotificationParser
import com.example.callguardian.notify.StyledMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrationsTest {
    private val cat = IntegrationCatalog(TestAssets::read)

    @Test fun catalogLoadsAllSources() {
        val ids = cat.all.map { it.id.name }.toSet()
        assertTrue(ids.containsAll(setOf("phone_sms", "phone_calls", "google_voice", "whatsapp", "whatsapp_business", "jev")))
        assertEquals("google_voice", cat.forPackage("com.google.android.apps.googlevoice")!!.id.name)
        assertEquals("whatsapp_business", cat.forPackage("com.whatsapp.w4b")!!.id.name)
        assertNull(cat.forPackage("com.example.other"))
        assertEquals(MessageSource.WHATSAPP, cat.source(cat.byId("whatsapp")!!))
        assertEquals(MessageSource.GOOGLE_VOICE, cat.source(cat.byId("google_voice")!!))
    }

    @Test fun messageSourceEnumMatchesSchema() {
        val e = Json.parseToJsonElement(TestAssets.read("message_source.schema.json")).jsonObject["properties"]!!.jsonObject["source"]!!.jsonObject["enum"]!!
            .jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(e, MessageSource.entries.map { it.wire }.toSet())
    }

    private val wa = { cat.byId("whatsapp")!! }
    private fun ignore(t: String) = cat.ignores(wa(), t)

    @Test fun whatsappSummaryIsIgnored() {
        assertTrue(ignore("3 new messages"))
        assertTrue(ignore("5 messages from 2 chats"))
        val f = NotificationFields("WhatsApp", "3 new messages", null, null, false, emptyList(), isGroupSummary = false)
        assertEquals(0, NotificationParser.parse(f, ::ignore).size)
    }

    @Test fun groupSummaryFlagIsDropped() {
        val f = NotificationFields("Ann", "hi", null, null, false, emptyList(), isGroupSummary = true)
        assertEquals(0, NotificationParser.parse(f, ::ignore).size)
    }

    @Test fun groupMessagesKeepConversationAndSender() {
        val f = NotificationFields("Family (2 messages)", null, null, "Family (2 messages)", true,
            listOf(StyledMessage("Bob", "Claim your prize at http://x.top", 10), StyledMessage(null, "my own reply", 11), StyledMessage("Ann", "lol", 12)), false)
        val out = NotificationParser.parse(f, ::ignore, since = 0)
        assertEquals(listOf("Bob", "Ann"), out.map { it.sender })
        assertEquals("Family", out[0].conversation)
        assertEquals(1, NotificationParser.parse(f, ::ignore, since = 11).size)
    }

    @Test fun plainNotificationWithGroupTitle() {
        val f = NotificationFields("Deals Group: Mike", "Send me the code", null, null, false, emptyList(), false)
        val p = NotificationParser.parse(f, ::ignore).single()
        assertEquals("Mike", p.sender); assertEquals("Deals Group", p.conversation)
    }

    @Test fun oneToOneHasNoConversation() {
        val f = NotificationFields("+1 555-0100", null, null, null, false, listOf(StyledMessage("+1 555-0100", "USPS: package held", 5)), false)
        val p = NotificationParser.parse(f, ::ignore).single()
        assertNull(p.conversation); assertNotNull(p.text)
    }
}
