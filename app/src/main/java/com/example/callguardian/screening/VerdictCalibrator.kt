package com.example.callguardian.screening

import com.example.callguardian.model.GuardianMethod
import com.example.callguardian.model.RiskLevel
import com.example.callguardian.model.ScamCategory

/**
 * Combines rules, the on-device/cloud model, and lookup into one calibrated verdict.
 *
 * Thresholds match the project convention: ≥ 0.7 is SCAM, ≤ 0.3 is CLEAR, and the
 * band in between is UNSETTLED. The model confidence is capped; the UI never shows 100%.
 *
 * Brand names are never a whitelist. An official domain only *lowers* suspicion.
 * A sender is treated as trusted when they are a saved contact, the user marked Trust,
 * or a known short code has prior history. An Owned account whose sender *and* official
 * domains match is CLEAR (Owned). Claiming an owned brand from an unknown sender or a
 * look-alike domain is flagged louder as impersonation.
 */
object VerdictCalibrator {
    const val SCAM_THRESHOLD = 0.7
    const val CLEAR_THRESHOLD = 0.3
    const val MAX_CONFIDENCE = 95
    const val MAX_MODEL_CONFIDENCE = 92

    enum class Band { CLEAR, UNSETTLED, SCAM }

    data class Features(
        val isContact: Boolean = false,
        val isTrusted: Boolean = false,
        val shortCodeHistory: Boolean = false,
        val officialDomain: Boolean = false,
        val officialHost: String? = null,
        val lookalike: Boolean = false,
        val lookalikeHost: String? = null,
        val ownedSender: Boolean = false,
        val ownedService: String? = null,
        val ownedOfficialDomain: Boolean = false,
        val ownedBrandClaimed: Boolean = false,
        val ownedLookalike: Boolean = false,
    ) {
        val trustedSender get() = isContact || isTrusted || shortCodeHistory
        /** Sender is on an owned account and every link (if any) is an official domain for it. */
        val ownedClear get() = ownedSender && ownedOfficialDomain && !lookalike && !ownedLookalike
        val ownedImpersonation get() = ownedBrandClaimed && (!ownedSender || lookalike || ownedLookalike)
    }

    data class Decision(
        val score: Double,
        val band: Band,
        val risk: RiskLevel,
        val category: ScamCategory,
        val confidence: Int,
        val method: GuardianMethod,
        val reasons: List<String>,
        val advice: String,
        val ownedLabel: String? = null,
    )

    fun capModelConfidence(raw: Int): Int = raw.coerceIn(0, MAX_MODEL_CONFIDENCE)

    fun displayConfidence(score: Double): Int =
        kotlin.math.round(score * 100.0).toInt().coerceIn(0, MAX_CONFIDENCE)

    fun bandOf(score: Double): Band = when {
        score >= SCAM_THRESHOLD -> Band.SCAM
        score <= CLEAR_THRESHOLD -> Band.CLEAR
        else -> Band.UNSETTLED
    }

    fun features(input: ScanInput): Features {
        val body = input.body.orEmpty()
        val hosts = OfficialDomains.hostsIn(body)
        val mentioned = OfficialDomains.mentionedBrands(body)
        val officialHost = mentioned.firstNotNullOfOrNull { b -> OfficialDomains.officialIn(hosts, b)?.let { it to b } }
            ?: hosts.firstOrNull { OfficialDomains.isAnyOfficialHost(it) }?.let { it to null }
        val lookalikeHost = mentioned.firstNotNullOfOrNull { b -> OfficialDomains.lookalikeIn(hosts, b) }
            ?: hosts.firstOrNull { h -> mentioned.any { OfficialDomains.isLookalike(h, it) } }

        val owned = input.owned
        val ownedBrand = input.allOwned.firstOrNull { acct ->
            mentioned.any { m -> OfficialDomains.brandKey(acct.service) == OfficialDomains.brandKey(m) ||
                acct.service.contains(m, true) || m.contains(acct.service.lowercase().replace(" ", "")) } ||
                body.contains(acct.service, ignoreCase = true)
        }
        val ownedOfficial = owned != null && (hosts.isEmpty() || hosts.all { OfficialDomains.isOfficialHost(it, owned.domains.toSet()) ||
            owned.domains.any { d -> OfficialDomains.isOfficialHost(it, setOf(d)) } })
        val ownedLookalike = ownedBrand != null && hosts.any { h ->
            !OfficialDomains.isOfficialHost(h, ownedBrand.domains.toSet()) &&
                (OfficialDomains.isLookalike(h, ownedBrand.service) || ownedBrand.domains.none { OfficialDomains.isOfficialHost(h, setOf(it)) } &&
                    OfficialDomains.tokensFor(OfficialDomains.brandKey(ownedBrand.service) ?: "").any { t -> h.contains(t) })
        }
        return Features(
            isContact = input.isContact,
            isTrusted = input.isTrusted,
            shortCodeHistory = input.shortCodeHistory,
            officialDomain = officialHost != null,
            officialHost = officialHost?.first,
            lookalike = lookalikeHost != null,
            lookalikeHost = lookalikeHost,
            ownedSender = owned != null,
            ownedService = owned?.service ?: ownedBrand?.service,
            ownedOfficialDomain = ownedOfficial && owned != null,
            ownedBrandClaimed = ownedBrand != null,
            ownedLookalike = ownedLookalike,
        )
    }

    fun fuse(
        rules: RuleScan,
        features: Features,
        modelConfidence: Int? = null,
        modelRisk: RiskLevel? = null,
        modelCategory: ScamCategory? = null,
        modelMethod: GuardianMethod? = null,
        modelReasons: List<String> = emptyList(),
        modelAdvice: String? = null,
        lookupSpamScore: Int? = null,
        channel: com.example.callguardian.model.Channel,
    ): Decision {
        if (features.ownedClear) {
            val service = features.ownedService ?: "owned account"
            return Decision(
                score = 0.08,
                band = Band.CLEAR,
                risk = RiskLevel.SAFE,
                category = ScamCategory.NONE,
                confidence = 20,
                method = GuardianMethod.NONE,
                reasons = listOf("Owned account: $service (sender and official domain match)."),
                advice = "This looks like a message from $service, which you marked as an account you own.",
                ownedLabel = service,
            )
        }

        val rulesN = (rules.score.coerceIn(0, 100)) / 100.0
        val modelN = modelConfidence?.let { capModelConfidence(it) / 100.0 }
        val lookupN = lookupSpamScore?.coerceIn(0, 100)?.div(100.0)
        var s = when {
            modelN != null && lookupN != null -> 0.35 * rulesN + 0.50 * modelN + 0.15 * lookupN
            modelN != null -> 0.40 * rulesN + 0.60 * modelN
            lookupN != null -> 0.75 * rulesN + 0.25 * lookupN
            else -> rulesN
        }
        if (features.officialDomain && !features.lookalike) s -= 0.28
        if (features.trustedSender) s -= 0.32
        if (features.lookalike) s += 0.22
        if (features.ownedImpersonation) s += 0.30
        if (features.ownedLookalike || (features.ownedBrandClaimed && features.lookalike)) s = maxOf(s, 0.78)
        // Official domains lower suspicion; they do not, by themselves, clear an unknown sender.
        if (features.officialDomain && !features.trustedSender && !features.ownedClear && s <= CLEAR_THRESHOLD) {
            s = CLEAR_THRESHOLD + 0.05
        }
        s = s.coerceIn(0.0, MAX_CONFIDENCE / 100.0)

        val band = bandOf(s)
        val risk = when (band) {
            Band.CLEAR -> RiskLevel.SAFE
            Band.UNSETTLED -> RiskLevel.MEDIUM
            Band.SCAM -> if (s >= 0.85) RiskLevel.CRITICAL else RiskLevel.HIGH
        }
        val category = when {
            features.ownedImpersonation || features.lookalike -> ScamCategory.IMPERSONATION
            modelCategory != null && modelCategory != ScamCategory.NONE && band != Band.CLEAR -> modelCategory
            else -> rules.category
        }
        val method = when {
            band != Band.SCAM -> GuardianMethod.NONE
            modelMethod != null && modelMethod != GuardianMethod.ALLOW_NUMBER -> modelMethod
            risk == RiskLevel.CRITICAL -> GuardianMethod.BLOCK_NUMBER
            channel == com.example.callguardian.model.Channel.CALL -> GuardianMethod.REJECT_CALL
            else -> GuardianMethod.QUARANTINE_SMS
        }
        val reasons = mutableListOf<String>()
        when (band) {
            Band.UNSETTLED -> reasons += "Unsettled — not clear enough to treat as a scam or as safe."
            Band.CLEAR -> reasons += "Calibrated clear."
            Band.SCAM -> { /* flags below */ }
        }
        if (features.ownedImpersonation) {
            val svc = features.ownedService ?: "an owned brand"
            reasons += if (features.lookalike || features.ownedLookalike)
                "Impersonation: mentions $svc but the link domain looks fake."
            else
                "Impersonation: mentions $svc from a sender you have not marked as owned."
        }
        if (features.officialDomain && features.officialHost != null) {
            reasons += "Link domain ${features.officialHost} matches an official site."
        }
        if (features.trustedSender) {
            reasons += when {
                features.isTrusted -> "Sender is on your Trust list."
                features.isContact -> "Sender is in your contacts."
                else -> "Known short code with prior history."
            }
        }
        reasons += (modelReasons + rules.reasons).distinct().take(6)
        val advice = when {
            band == Band.SCAM && (features.lookalike || features.ownedImpersonation) ->
                "Don't tap the link. Open the official site or app yourself if you need to check an account."
            band == Band.UNSETTLED && features.officialDomain ->
                "The link domain looks official, but this sender isn't verified. Check in the official app or website — don't use the link in the text."
            !modelAdvice.isNullOrBlank() && band != Band.CLEAR -> modelAdvice
            band == Band.CLEAR -> "No action needed."
            else -> "If you're unsure, don't tap links or reply. Check through a channel you already trust."
        }
        return Decision(
            score = s,
            band = band,
            risk = risk,
            category = if (band == Band.CLEAR) ScamCategory.NONE else category,
            confidence = displayConfidence(s),
            method = method,
            reasons = reasons.distinct(),
            advice = advice,
            ownedLabel = features.ownedService.takeIf { features.ownedSender },
        )
    }
}
