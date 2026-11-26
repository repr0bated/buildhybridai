package com.example.callguardian.sms

/**
 * Pure presentation helpers for the Messages tab (conversation list, thread, compose).
 * Ported in spirit from Clear SMS (itsluminous/ClearSMS, Apache-2.0) and restyled for
 * Call Guardian's terminal theme. Keep this file free of Android framework types so
 * unit tests can run on the JVM.
 */
data class MessageBadge(
    val id: String,
    val label: String,
) {
    companion object {
        const val CODE = "code"
        const val OWNED = "owned"
        const val SUBSCRIPTION = "subscription"
        const val SCAM = "scam"
        const val SUSPICIOUS = "suspicious"
        const val STOPPED = "stopped"
        const val CANCELED = "canceled"
    }
}

data class SmsSegments(
    val encoding: String,
    val units: Int,
    val remainingInSegment: Int,
    val charsPerSegment: Int,
) {
    val label: String get() = "$units $encoding · $remainingInSegment left"
}

data class ConversationFilter(
    val query: String = "",
    val showArchived: Boolean = false,
)

object MessagePresentation {

    /** GSM 03.38 default alphabet plus the extension table (those count as two septets). */
    private val GSM7 = (
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
            "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
        ).toSet()
    private val GSM7_EXT = "^{}\\[~]|€".toSet()

    fun threadKey(address: String): String {
        val digits = address.filter { it.isDigit() }
        return digits.ifBlank { address.trim().lowercase() }
    }

    fun matchesQuery(address: String, snippet: String, displayName: String?, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val hay = listOf(address, snippet, displayName.orEmpty()).joinToString(" ").lowercase()
        if (q.lowercase() in hay) return true
        val qd = q.filter { it.isDigit() }
        return qd.length >= 3 && qd in threadKey(address)
    }

    fun shouldShowDateDivider(previousMillis: Long?, currentMillis: Long): Boolean {
        if (previousMillis == null) return true
        val day = 24L * 60 * 60 * 1000
        return (previousMillis / day) != (currentMillis / day)
    }

    /**
     * One inline category badge for a screened message. Order: Code, Owned, Subscription
     * (including STOP_SENT / CANCELED), then Scam / Suspicious. A link alone is never a
     * scam badge — only a recorded scam/suspicious risk or category is.
     */
    fun badge(
        risk: String? = null,
        category: String? = null,
        reasons: String? = null,
        advice: String? = null,
        subscriptionStatus: String? = null,
        owned: Boolean = false,
    ): MessageBadge? {
        val why = listOf(reasons, advice).filterNotNull().joinToString("\n")
        val status = subscriptionStatus?.uppercase()
        if (status == "CANCELED") return MessageBadge(MessageBadge.CANCELED, "Canceled")
        if (status == "STOP_SENT" || status == "UNSUBSCRIBED") return MessageBadge(MessageBadge.STOPPED, "Stopped")
        if (owned || why.contains("Owned account", ignoreCase = true)) {
            return MessageBadge(MessageBadge.OWNED, "Owned")
        }
        if (why.contains("CLEAR (Code)", ignoreCase = true) ||
            (why.contains("verification or login code", ignoreCase = true) && risk.equals("SAFE", true))
        ) {
            return MessageBadge(MessageBadge.CODE, "Code")
        }
        if (status == "ACTIVE" || why.contains("subscription", ignoreCase = true) || why.contains("Reply STOP", ignoreCase = true)) {
            return MessageBadge(MessageBadge.SUBSCRIPTION, "Subscription")
        }
        val cat = category?.uppercase().orEmpty()
        val riskyCat = cat.isNotEmpty() && cat != "NONE"
        val riskName = risk?.uppercase().orEmpty()
        if (riskName in setOf("HIGH", "CRITICAL") || (riskyCat && riskName != "SAFE" && riskName != "LOW")) {
            return MessageBadge(MessageBadge.SCAM, "Scam")
        }
        if (riskName == "MEDIUM") return MessageBadge(MessageBadge.SUSPICIOUS, "Suspicious")
        return null
    }

    fun segments(text: String): SmsSegments {
        val gsmUnits = gsmSeptets(text)
        return if (gsmUnits != null) {
            val per = if (gsmUnits <= 160) 160 else 153
            val units = if (gsmUnits == 0) 1 else (gsmUnits + per - 1) / per
            val used = if (gsmUnits == 0) 0 else ((gsmUnits - 1) % per) + 1
            SmsSegments("GSM-7", units, per - used, per)
        } else {
            val n = text.length
            val per = if (n <= 70) 70 else 67
            val units = if (n == 0) 1 else (n + per - 1) / per
            val used = if (n == 0) 0 else ((n - 1) % per) + 1
            SmsSegments("UCS-2", units, per - used, per)
        }
    }

    private fun gsmSeptets(text: String): Int? {
        var n = 0
        for (ch in text) {
            when {
                ch in GSM7 -> n += 1
                ch in GSM7_EXT -> n += 2
                else -> return null
            }
        }
        return n
    }

    /** sms:/smsto:/mms:/mmsto: destination, ignoring a trailing query string. */
    fun destinationFromUri(schemeSpecificPart: String?, addressExtra: String? = null): String? {
        val raw = schemeSpecificPart?.substringBefore('?') ?: return addressExtra?.trim()?.takeIf { it.isNotBlank() }
        return raw.trim().takeIf { it.isNotBlank() } ?: addressExtra?.trim()?.takeIf { it.isNotBlank() }
    }
}
