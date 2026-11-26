package com.example.callguardian.lookup

import com.example.callguardian.data.db.LookupCacheEntity
import com.example.callguardian.data.db.ScreeningEventEntity
import com.example.callguardian.model.LookupResult
import com.example.callguardian.model.ReverseLookupValidity
import org.json.JSONObject

/** Shared helpers for manual, automatic, and bulk reverse lookup. */
object ReverseLookup {
    const val NUMLOOKUP_KEY_URL = "https://numlookup.com"
    const val ABSTRACT_KEY_URL = "https://www.abstractapi.com/api/phone-validation-api"
    const val TWILIO_KEY_URL = "https://console.twilio.com"
    const val TELNYX_KEY_URL = "https://portal.telnyx.com"
    const val ROOM_TTL_MS = 7L * 24 * 60 * 60 * 1000
    const val USER_BUDGET_MS = 10_000L
    const val SCREENING_BUDGET_MS = 2_000L

    fun toE164(number: String, defaultCountryCode: String = "1"): String? {
        val trimmed = number.trim()
        if (trimmed.equals("hidden", true) || trimmed.equals("hidden number", true)) return null
        if (trimmed.any { it.isLetter() } && trimmed.none { it.isDigit() }) return null
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 10) return null
        return when {
            trimmed.startsWith("+") -> "+$digits"
            digits.length == 11 && digits.startsWith(defaultCountryCode) -> "+$digits"
            digits.length == 10 -> "+$defaultCountryCode$digits"
            digits.length > 11 -> "+$digits"
            else -> null
        }
    }

    fun cacheKey(number: String): String =
        toE164(number) ?: number.filter { it.isDigit() }.ifBlank { number.trim() }

    fun numberVariants(number: String): List<String> {
        val raw = number.trim()
        if (raw.isEmpty()) return emptyList()
        val e164 = toE164(raw)
        val digits = raw.filter { it.isDigit() }
        val last10 = digits.takeLast(10).takeIf { it.length == 10 }
        return listOfNotNull(
            e164,
            raw,
            digits.takeIf { it.isNotEmpty() && it != raw },
            last10?.takeIf { it != digits && it != raw },
            last10?.let { "1$it" }?.takeIf { it != digits },
            digits.takeIf { it.length == 11 && it.startsWith("1") }?.drop(1),
            digits.takeIf { it.isNotEmpty() }?.let { "+$it" }?.takeIf { it != raw && it != e164 },
        ).filter { it.isNotBlank() }.distinct()
    }

    fun lookupTarget(address: String?, isGroup: Boolean = false, recipients: List<String> = emptyList()): String? {
        val candidate = when {
            !isGroup -> address
            else -> recipients.singleOrNull() ?: address
        }?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        if (candidate.equals("hidden", ignoreCase = true) || candidate.equals("hidden number", ignoreCase = true)) return null
        if (isGroup && recipients.size > 1) return null
        return candidate.takeIf { it.any(Char::isDigit) }
    }

    fun lineTypeLabel(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val k = s.lowercase()
        return when {
            "voip" in k || "voice over" in k || k == "vop" -> "VoIP"
            "mobile" in k || "cell" in k || "wireless" in k -> "Mobile"
            "land" in k || "fixed" in k || "wireline" in k || k == "line" -> "Landline"
            else -> s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    fun validityLabel(valid: Boolean?): String? = when (valid) {
        true -> "Valid"
        false -> "Invalid"
        null -> null
    }

    fun isBlockEvent(e: ScreeningEventEntity): Boolean {
        val actions = listOf(e.finalAction, e.userAction, e.proposedAction).map { it.orEmpty().lowercase() }
        return actions.any { it == "block_number" }
    }

    fun blockCount(events: List<ScreeningEventEntity>): Int = events.count { isBlockEvent(it) }

    fun merge(results: List<LookupResult>, order: List<String>): MergedLookup {
        if (results.isEmpty()) return MergedLookup(phoneNumber = "")
        val ranked = buildList {
            val seen = HashSet<Int>()
            for (id in order) {
                results.forEachIndexed { i, r ->
                    if (i !in seen && r.resolvedSourceId == id) {
                        add(r); seen.add(i)
                    }
                }
            }
            results.forEachIndexed { i, r -> if (i !in seen) add(r) }
        }
        fun field(select: (LookupResult) -> String?): LookupField {
            val hit = ranked.firstOrNull { !select(it).isNullOrBlank() } ?: return LookupField(null)
            val p = LookupProvider.fromId(hit.resolvedSourceId)
            return LookupField(select(hit), hit.resolvedSourceId, p?.displayName ?: hit.source)
        }
        return MergedLookup(
            phoneNumber = ranked.first().phoneNumber,
            displayName = field { it.displayName },
            carrier = field { it.carrier },
            lineType = field { ReverseLookup.lineTypeLabel(it.lineType) ?: it.lineType },
            region = field { it.region?.takeIf { r -> r.isNotBlank() } },
            valid = field { validityLabel(it.resolvedValid) },
            contributions = ranked,
        )
    }

    fun toEntity(result: LookupResult, key: String = cacheKey(result.phoneNumber), at: Long = System.currentTimeMillis()): LookupCacheEntity =
        LookupCacheEntity(
            phoneNumber = key,
            source = result.resolvedSourceId,
            displayName = result.displayName,
            carrier = result.carrier,
            lineType = result.lineType,
            region = result.region,
            valid = result.resolvedValid,
            rawJson = encodeRaw(result.rawPayload),
            fetchedAt = at,
        )

    fun toResult(row: LookupCacheEntity): LookupResult {
        val raw = decodeRaw(row.rawJson)
        val provider = LookupProvider.fromId(row.source)
        return LookupResult(
            phoneNumber = row.phoneNumber,
            displayName = row.displayName,
            carrier = row.carrier,
            lineType = row.lineType,
            region = row.region,
            valid = row.valid ?: ReverseLookupValidity.fromRaw(raw["valid"]),
            source = provider?.displayName ?: row.source,
            sourceId = row.source,
            rawPayload = raw,
        )
    }

    fun encodeRaw(map: Map<String, Any?>): String? {
        if (map.isEmpty()) return null
        val o = JSONObject()
        map.forEach { (k, v) ->
            when (v) {
                null -> o.put(k, JSONObject.NULL)
                is Boolean, is Number, is String -> o.put(k, v)
                else -> o.put(k, v.toString())
            }
        }
        return o.toString()
    }

    fun decodeRaw(json: String?): Map<String, Any?> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { k ->
                when (val v = o.get(k)) {
                    JSONObject.NULL -> null
                    else -> v
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun retryAfterMs(header: String?): Long {
        val sec = header?.trim()?.toLongOrNull()
        return ((sec ?: 6L) * 1_000L).coerceIn(1_000L, 60_000L)
    }
}
