package com.example.callguardian.ai

import com.example.callguardian.model.Channel
import com.example.callguardian.screening.RuleScan

/** Prompts are short on purpose: on-device requests are capped at ~4000 tokens. */
object Prompts {

    fun triage(
        channel: Channel, senderLabel: String, isContact: Boolean, body: String?, lookup: String?, rules: RuleScan,
        senderFeatures: String? = null, linkDomains: String? = null, ownedList: String? = null,
        variant: String = "A",
    ): String = buildString {
        appendLine("You are Call Guardian, a scam detector running privately on the user's phone.")
        appendLine("Classify this incoming ${if (channel == Channel.SMS) "text message" else "phone call"}.")
        if (normalizeVariant(variant) == "B") {
            appendLine("Variant B: Be conservative. Prefer SUSPICIOUS over CLEAR when evidence is thin. An official domain is supporting evidence only, never proof by itself.")
        }
        appendLine("Respond with JSON only, matching {\"verdict\": one of SCAM, SUSPICIOUS, CLEAR, SUBSCRIPTION, CODE, OWNED, \"confidence\": 0-99, \"reasons\": up to 3 short strings, \"reputable_official\": YES, NO or UNKNOWN, \"held_account\": YES, NO or UNKNOWN}.")
        appendLine("SCAM if fraudulent, SUSPICIOUS if unsettled, CLEAR if legitimate, SUBSCRIPTION if a list the user could opt out of, CODE if a login or verification code, OWNED if it is from an account the user owns.")
        appendLine("reputable_official: YES if a link uses an official domain of the claimed brand, NO if a look-alike domain is used, UNKNOWN otherwise.")
        appendLine("held_account: YES if this is from an account the user marked as owned, NO if it is not a saved contact and not an owned account, UNKNOWN if it is a saved contact but not owned.")
        appendLine("A link alone is never phishing. An official domain of the claimed brand is a positive signal. A look-alike domain is impersonation.")
        appendLine("Never treat a brand name alone as proof the message is legitimate. An official link domain lowers suspicion; a look-alike domain raises it.")
        appendLine("Do not use 100 for confidence; leave room for uncertainty. Prefer a mid confidence when sender or domain evidence is mixed.")
        appendLine("A text that only contains a login or verification code, with no link and no request to share the code, call, or reply, is legitimate (CLEAR / Code) — especially from a short code. 'Never share this code' is a standard warning, not a scam. Flag a code text only if it asks the user to share the code or includes a link.")
        appendLine()
        appendLine("Sender: $senderLabel (${if (isContact) "saved contact" else "not in contacts"})")
        if (!senderFeatures.isNullOrBlank()) appendLine("Sender features: $senderFeatures")
        if (!linkDomains.isNullOrBlank()) appendLine("Link domains: $linkDomains")
        if (!ownedList.isNullOrBlank()) appendLine("User's owned accounts: $ownedList")
        if (!lookup.isNullOrBlank()) appendLine("Caller facts: $lookup")
        if (rules.flags.isNotEmpty()) appendLine("Local rule hints (may be wrong): ${rules.reasons.joinToString("; ")}")
        if (channel == Channel.SMS) {
            appendLine("Message:")
            appendLine("\"\"\"")
            appendLine(body.orEmpty().take(800))
            appendLine("\"\"\"")
        } else {
            appendLine("There is no transcript; judge from the caller metadata only.")
        }
        appendLine("Respond with JSON only.")
    }

    fun deep(channel: Channel, redactedSender: String, redactedBody: String?, onDeviceSummary: String?): String = buildString {
        appendLine("You are a fraud analyst helping an ordinary phone user. Personal data has been redacted as [name], [number], [digits], [email], [path].")
        appendLine("Explain clearly whether this ${if (channel == Channel.SMS) "text message" else "phone call"} is a scam, which manipulation tactics it uses, and exactly what the user should do next.")
        appendLine("Never tell the user to tap the link or call the number in the message. Recommend official channels instead.")
        appendLine("Also pick ONE method to offer the user: NONE, BLOCK_NUMBER, ALLOW_NUMBER, REJECT_CALL, QUARANTINE_SMS, REPORT_SCAM or SAVE_CONTACT.")
        appendLine()
        appendLine("Sender: $redactedSender")
        if (!onDeviceSummary.isNullOrBlank()) appendLine("On-device first opinion: $onDeviceSummary")
        if (!redactedBody.isNullOrBlank()) {
            appendLine("Message:")
            appendLine("\"\"\"")
            appendLine(redactedBody.take(4000))
            appendLine("\"\"\"")
        }
        appendLine("Respond with JSON only.")
    }

    fun subscription(service: String, senders: List<String>, sourceLabel: String, messages: List<String>, links: List<String>): String = buildString {
        appendLine("You check texts privately on the user's phone. Decide whether these texts are an automated subscription, alert or marketing list the user could opt out of.")
        appendLine("Name the company or service they come from (for example Cash App or Spotify). If several numbers belong to one service, use the same name. Leave service empty only if it cannot be told.")
        if (service.isNotBlank()) appendLine("Suggested service: $service")
        appendLine("Senders: ${senders.joinToString()} (via $sourceLabel)")
        appendLine("Recent texts:")
        messages.take(5).forEach { appendLine("- \"${it.take(300)}\"") }
        if (links.isEmpty()) appendLine("Links: none") else {
            appendLine("Links (pick the one that unsubscribes or manages the subscription, or 0 if none does):")
            links.take(6).forEachIndexed { i, l -> appendLine("${i + 1}. $l") }
        }
        appendLine("Respond with JSON only.")
    }

    fun normalizeVariant(variant: String?): String =
        if (variant?.trim().equals("B", ignoreCase = true)) "B" else "A"
}
