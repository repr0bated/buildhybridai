package com.example.callguardian.screening

import com.example.callguardian.accounts.OwnedAccount
import com.example.callguardian.policy.SchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Local, deterministic answers for the required scam_verdict fields
 * `reputable_official` and `held_account`. Used to fill the model output
 * when those fields are omitted, null, or not YES/NO/UNKNOWN, before schema
 * validation. The UI must show Yes/No/Unknown, never "no answer".
 */
object VerdictFields {
    const val YES = "YES"
    const val NO = "NO"
    const val UNKNOWN = "UNKNOWN"

    val VALUES = setOf(YES, NO, UNKNOWN)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Official domain of the claimed brand → YES. A look-alike domain → NO.
     * Mixed or no domain evidence → UNKNOWN.
     */
    fun localReputableOfficial(body: String?): String {
        val text = body.orEmpty()
        val hosts = OfficialDomains.hostsIn(text)
        val mentioned = OfficialDomains.mentionedBrands(text)
        val official = mentioned.any { OfficialDomains.officialIn(hosts, it) != null } ||
            hosts.any { OfficialDomains.isAnyOfficialHost(it) }
        val lookalike = mentioned.any { OfficialDomains.lookalikeIn(hosts, it) != null } ||
            hosts.any { h -> mentioned.any { OfficialDomains.isLookalike(h, it) } }
        return when {
            official && !lookalike -> YES
            lookalike && !official -> NO
            else -> UNKNOWN
        }
    }

    /**
     * Owned sender or owned-account domain → YES. Saved contact but not owned → UNKNOWN.
     * Neither a contact nor an owned account → NO.
     */
    fun localHeldAccount(isContact: Boolean, ownedSender: Boolean, ownedDomain: Boolean): String = when {
        ownedSender || ownedDomain -> YES
        isContact -> UNKNOWN
        else -> NO
    }

    fun ownedDomainMatch(body: String?, owned: List<OwnedAccount>): Boolean {
        val hosts = OfficialDomains.hostsIn(body.orEmpty())
        if (hosts.isEmpty()) return false
        return owned.any { acct -> hosts.any { acct.matchesDomain(it) } }
    }

    /** Normalize a model value to YES/NO/UNKNOWN, or null if it should be filled locally. */
    fun normalize(raw: Any?): String? {
        val s = when (raw) {
            null, is JsonNull -> return null
            is JsonPrimitive -> raw.contentOrNull
            is JsonElement -> raw.toString().trim('"')
            else -> raw.toString()
        }?.trim()?.uppercase()?.replace(' ', '_') ?: return null
        if (s.isEmpty() || s == "NULL" || s == "NONE" || s == "NO_ANSWER" || s == "—" || s == "-") return null
        return when (s) {
            YES, "TRUE", "Y" -> YES
            NO, "FALSE", "N" -> NO
            UNKNOWN -> UNKNOWN
            else -> null
        }
    }

    /**
     * Insert local answers for omitted or invalid [reputable_official] / [held_account].
     * If the text is not JSON, it is returned unchanged so schema validation can reject it.
     */
    fun fillOmitted(rawText: String?, reputableOfficial: String, heldAccount: String): String {
        val extracted = SchemaValidator.extractJson(rawText.orEmpty())
        if (extracted.isBlank()) return rawText.orEmpty()
        val obj = runCatching { json.parseToJsonElement(extracted).jsonObject }.getOrNull() ?: return rawText.orEmpty()
        return buildJsonObject {
            obj.forEach { (k, v) -> if (k != "reputable_official" && k != "held_account") put(k, v) }
            put("reputable_official", JsonPrimitive(normalize(obj["reputable_official"]) ?: reputableOfficial))
            put("held_account", JsonPrimitive(normalize(obj["held_account"]) ?: heldAccount))
        }.toString()
    }

    /** Human label for the UI: Yes, No, or Unknown. Never "no answer" or a blank dash. */
    fun display(raw: String?): String = when (normalize(raw)) {
        YES -> "Yes"
        NO -> "No"
        else -> "Unknown"
    }

    fun isTriStateField(name: String): Boolean =
        name == "reputable_official" || name == "held_account"
}
