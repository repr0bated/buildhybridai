package com.example.callguardian.methods

import com.example.callguardian.jev.JevTruthTable
import com.example.callguardian.subscriptions.LinkCandidate
import com.example.callguardian.subscriptions.LinkSafety
import com.example.callguardian.subscriptions.SubscriptionGroup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Registry methods with `"proposable": false`: only the user can start them (a tap, then a
 * schema-generated confirmation sheet). No model can propose them and nothing auto-runs them.
 * RegistryTest keeps this enum and the registry in step.
 */
enum class UserMethod(val alias: String) {
    REVIEW_CALL("review_call"),
    JEV_CHECK("jev_check"),
    UNSUBSCRIBE_SMS("unsubscribe_sms"),
    MANAGE_SUBSCRIPTION("manage_subscription"),
    MUTE_SENDER("mute_sender"),
    SET_INTEGRATION("set_integration"),
    SET_PRIVATE("set_private"),
    CLEAR_PRIVATE("clear_private"),
    MARK_OWNED("mark_owned"),
    UNMARK_OWNED("unmark_owned"),
    NOT_SCAM("not_a_scam");

    companion object { fun fromAlias(a: String) = entries.firstOrNull { it.alias == a } }
}

enum class CallLogType { INCOMING, OUTGOING, MISSED, REJECTED, BLOCKED, VOICEMAIL, UNKNOWN }

/** One call log row plus what code computed about it (frequency, contact status). */
data class CallLogItem(
    val id: Long,
    val number: String?,
    val cachedName: String?,
    val isContact: Boolean,
    val type: CallLogType,
    val durationSec: Long,
    val date: Long,
    val callsFromNumber30d: Int,
    val callsWithin15Min: Int,
)

/** Builds user-method inputs from trusted app data. Every result is validated against its schema before dispatch (and in RegistryTest). */
object UserMethodInputs {
    fun reviewCall(c: CallLogItem, runOnDevice: Boolean = true): JsonObject = buildJsonObject {
        put("callLogId", c.id.coerceAtLeast(1))
        put("number", c.number?.filter { it.isDigit() || it == '+' }?.takeIf { it.trimStart('+').length >= 3 } ?: "hidden")
        c.cachedName?.takeIf { it.isNotBlank() }?.let { put("cachedName", it.take(80)) }
        put("isContact", c.isContact)
        put("callType", c.type.name)
        put("durationSec", c.durationSec.coerceAtLeast(0))
        put("callAt", c.date.coerceAtLeast(0))
        put("callsFromNumber30d", c.callsFromNumber30d.coerceAtLeast(0))
        put("callsWithin15Min", c.callsWithin15Min.coerceAtLeast(0))
        put("runOnDevice", runOnDevice)
    }

    fun jevSender(number: String?, isContact: Boolean): String = when {
        isContact -> "saved_contact"
        number.isNullOrBlank() -> "hidden"
        number.all { it.isDigit() } && number.length in 4..6 -> "short_code"
        else -> "unknown_number"
    }

    fun jevCheck(eventId: Long, number: String?, isContact: Boolean, redactedText: String, source: String): JsonObject = buildJsonObject {
        put("eventId", eventId); put("sender", jevSender(number, isContact)); put("redactedText", redactedText.take(2000)); put("source", source)
    }

    fun unsubscribe(g: SubscriptionGroup): JsonObject = buildJsonObject {
        put("service", g.service.take(80).ifBlank { g.senders.first() })
        put("recipients", JsonArray(g.textableSenders.map { JsonPrimitive(it) }))
        put("keyword", g.keyword)
        put("messagesInGroup", g.count.coerceAtLeast(1))
        put("reason", (g.signals.joinToString(", ").ifBlank { "subscription sender" }).take(200))
        put("source", "sms")
    }


    fun manageSubscription(sender: String, source: String, link: LinkCandidate, chosenBy: String, safety: LinkSafety): JsonObject = buildJsonObject {
        put("sender", sender.take(64)); put("source", source)
        put("url", link.url.take(500))
        put("domain", link.domain.take(120))
        put("linkKind", link.kind.name)
        put("chosenBy", chosenBy)
        put("suspicious", safety.suspicious)
        safety.warning?.let { put("warning", it.take(200)) }
        put("acknowledgeWarning", false)
    }

    fun setIntegration(id: String, enabled: Boolean, status: String): JsonObject = buildJsonObject {
        put("integration", id); put("enabled", enabled); put("statusAtChange", status.take(160))
    }

    fun mute(sender: String, source: String, mode: String): JsonObject = buildJsonObject { put("sender", sender.take(64)); put("source", source); put("mode", mode) }

    fun setPrivate(kind: String, label: String, eventId: Long? = null, address: String? = null, callLogId: Long? = null, source: String? = null): JsonObject =
        privacyInput(kind, label, eventId, address, callLogId, source)
    fun clearPrivate(kind: String, label: String, eventId: Long? = null, address: String? = null, callLogId: Long? = null, source: String? = null): JsonObject =
        privacyInput(kind, label, eventId, address, callLogId, source)
    private fun privacyInput(kind: String, label: String, eventId: Long?, address: String?, callLogId: Long?, source: String?) = buildJsonObject {
        put("kind", kind)
        put("label", label.take(80))
        if (eventId != null) put("eventId", eventId)
        if (!address.isNullOrBlank()) put("address", address.take(64))
        if (callLogId != null) put("callLogId", callLogId)
        if (!source.isNullOrBlank()) put("source", source)
    }

    fun mute(g: SubscriptionGroup, mode: String): JsonObject = buildJsonObject {
        put("sender", (g.service.ifBlank { g.sender }).take(64))
        put("service", g.service.take(80))
        put("senders", JsonArray(g.senders.map { JsonPrimitive(it.take(64)) }))
        put("source", g.source.wire)
        put("mode", mode)
    }

    fun markOwned(service: String, senders: List<String>, domains: List<String> = emptyList(), eventId: Long? = null): JsonObject = buildJsonObject {
        put("service", service.take(80))
        put("senders", JsonArray(senders.map { JsonPrimitive(it.take(64)) }))
        if (domains.isNotEmpty()) put("domains", JsonArray(domains.map { JsonPrimitive(it.take(120)) }))
        if (eventId != null) put("eventId", eventId)
    }

    fun unmarkOwned(accountId: Long, service: String, label: String? = null): JsonObject = buildJsonObject {
        put("accountId", accountId)
        put("service", service.take(80))
        put("label", (label ?: "Remove $service from Owned accounts").take(160))
    }

    fun notScam(eventId: Long, number: String, label: String, markOwned: Boolean = false, service: String? = null, domains: List<String> = emptyList()): JsonObject = buildJsonObject {
        put("eventId", eventId)
        put("number", number.take(64))
        put("label", label.take(80))
        put("markOwned", markOwned)
        if (!service.isNullOrBlank()) put("service", service.take(80))
        if (domains.isNotEmpty()) put("domains", JsonArray(domains.map { JsonPrimitive(it.take(120)) }))
    }

    /** Jev probabilities from the function → output object (verdict decided here, in code). */
    fun jevOutput(eventId: Long, signals: Map<String, Double>, isContact: Boolean, model: String, questionsVersion: String, inputTokens: Int, latencyMs: Long): JsonObject {
        val probs = JevTruthTable.Signal.entries.associateWith { s -> signals[s.id] ?: if (s == JevTruthTable.Signal.REPUTABLE_OFFICIAL) 0.05 else error("Jev returned no answer for ${s.id}") }
        val r = JevTruthTable.decide(probs, isContact)
        return buildJsonObject {
            put("eventId", eventId); put("verdict", r.verdict.name); put("matchedRule", r.rule.take(80))
            put("unsettledSignals", kotlinx.serialization.json.JsonArray(r.unsettled.map { kotlinx.serialization.json.JsonPrimitive(it.id) }))
            probs.forEach { (s, p) -> put(s.id, Math.round(p.coerceIn(0.0, 1.0) * 100).toInt()) }
            put("model", model.take(64)); put("questionsVersion", questionsVersion.take(20))
            put("inputTokens", inputTokens.coerceAtLeast(0)); put("latencyMs", latencyMs.coerceAtLeast(0))
        }
    }
}
