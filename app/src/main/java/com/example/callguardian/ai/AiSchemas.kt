package com.example.callguardian.ai

import com.example.callguardian.model.GuardianMethod
import com.example.callguardian.model.ModelVerdict
import com.example.callguardian.model.RiskLevel
import com.example.callguardian.model.ScamCategory
import com.google.firebase.ai.annotations.Generable
import com.google.firebase.ai.annotations.Guide
import kotlinx.serialization.Serializable

/**
 * Structured output for the triage pass. Mirrors assets/schemas/scam_verdict.schema.json.
 * On-device Nano returns this as plain JSON (no responseSchema, to avoid ErrorCode -110);
 * cloud Gemini is constrained with a Schema.obj generated from that registry schema.
 */
@Serializable
data class ScamVerdict(
    val verdict: ModelVerdict,
    val confidence: Double,
    val reasons: List<String> = emptyList(),
) {
    val confidenceInt: Int get() = confidence.toInt().coerceIn(0, 100)
}

/** Structured output for the opt-in cloud deep analysis (Gemini, ONLY_IN_CLOUD, redacted input). */
@Serializable
@Generable(description = "Detailed explanation of a suspicious message or call")
data class DeepAnalysis(
    @Guide(description = "Overall risk to the user")
    val riskLevel: RiskLevel,
    @Guide(description = "Most likely scam type, or NONE if benign")
    val category: ScamCategory,
    @Guide(description = "Confidence from 0 to 100", minimum = 0.0, maximum = 100.0)
    val confidence: Int,
    @Guide(description = "Two or three sentence plain-English explanation of what is going on")
    val summary: String,
    @Guide(description = "Manipulation tactics the sender is using", maxItems = 5)
    val tactics: List<String>,
    @Guide(description = "Concrete next steps for the user", maxItems = 5)
    val whatToDo: List<String>,
    @Guide(description = "The ONE typed method to call next (never EXPLAIN_CLOUD here), or NONE")
    val method: GuardianMethod,
) {
    companion object
}

/**
 * On-device confirmation for a subscription sender (Gemini Nano 4, built on Gemma 4). Mirrors
 * assets/schemas/subscription_check.schema.json, which the raw output is validated against.
 */
@Serializable
@Generable(description = "Whether these texts are a subscription or marketing list, the service they come from, and which link unsubscribes")
data class SubscriptionCheck(
    @Guide(description = "True if these are automated subscription, alert or marketing texts the user could opt out of")
    val isSubscription: Boolean,
    @Guide(description = "Number of the link that unsubscribes or manages the subscription, from the numbered list; 0 if none fits", minimum = 0.0, maximum = 6.0)
    val unsubscribeLink: Int,
    @Guide(description = "Company or service these texts are from, like Cash App or Spotify. Empty if it cannot be told")
    val service: String = "",
) {
    companion object
}
