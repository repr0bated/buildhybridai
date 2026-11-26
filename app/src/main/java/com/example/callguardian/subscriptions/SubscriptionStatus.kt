package com.example.callguardian.subscriptions

/** Lifecycle of a subscription. Transitions are recorded in subscription_status_history. */
enum class SubscriptionStatus {
    ACTIVE, STOP_SENT, UNSUBSCRIBED, CANCELED;

    companion object {
        fun from(raw: String?): SubscriptionStatus =
            raw?.let { runCatching { valueOf(it) }.getOrNull() } ?: ACTIVE
    }
}

object UnsubscribeConfirmation {
    private val patterns = listOf(
        Regex("""(?i)you have been unsubscribed"""),
        Regex("""(?i)you've been unsubscribed"""),
        Regex("""(?i)you will no longer receive"""),
        Regex("""(?i)you'll no longer receive"""),
        Regex("""(?i)you will no longer get"""),
        Regex("""(?i)successfully unsubscribed"""),
        Regex("""(?i)removed from (this|our|the) (list|mailing|subscription)"""),
        Regex("""(?i)opted out"""),
        Regex("""(?i)is now unsubscribed"""),
        Regex("""(?i)unsubscribe (request )?received"""),
    )

    fun matches(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        return patterns.any { it.containsMatchIn(body) }
    }
}
