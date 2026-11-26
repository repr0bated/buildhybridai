package com.example.callguardian.model

data class LookupResult(
    val phoneNumber: String,
    val displayName: String? = null,
    val carrier: String? = null,
    val region: String? = null,
    val lineType: String? = null,
    val spamScore: Int? = null,
    val tags: List<String> = emptyList(),
    val source: String,
    val rawPayload: Map<String, Any?> = emptyMap(),
    val valid: Boolean? = null,
    /** Stable provider id (numlookup, abstractapi, twilio, telnyx, custom). */
    val sourceId: String = "",
) {
    val summary: String?
        get() = displayName ?: tags.firstOrNull() ?: carrier ?: region

    val resolvedSourceId: String get() = sourceId.ifBlank { source }

    /** First-class [valid], or a "valid" flag left in [rawPayload] by a source. */
    val resolvedValid: Boolean?
        get() = valid ?: ReverseLookupValidity.fromRaw(rawPayload["valid"])
}

object ReverseLookupValidity {
    fun fromRaw(value: Any?): Boolean? = when (value) {
        null -> null
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "true", "1", "yes", "valid" -> true
            "false", "0", "no", "invalid" -> false
            else -> null
        }
        else -> null
    }
}
