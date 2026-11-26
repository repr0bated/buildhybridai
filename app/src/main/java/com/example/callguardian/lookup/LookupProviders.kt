package com.example.callguardian.lookup

import java.util.Locale

/**
 * Catalog of reverse-lookup providers, published list prices used for estimates,
 * and default run order. Prices are estimates based on each vendor's published
 * rates and are not a quote; an invoice may differ (volume tiers, geography,
 * which data packages a number actually bills).
 */
enum class LookupProvider(
    val id: String,
    val displayName: String,
    val getKeyUrl: String,
    /** Published list-price estimate, USD per number, for the packages we request. */
    val usdPerLookup: Double,
    val priceNote: String,
    val priceSourceUrl: String,
    /** Minimum delay between requests during a bulk run. */
    val minIntervalMs: Long,
    val supportsBulk: Boolean = true,
) {
    NUMLOOKUP(
        id = "numlookup",
        displayName = "NumLookupAPI",
        getKeyUrl = "https://numlookup.com",
        usdPerLookup = 0.003,
        priceNote = "NumLookupAPI sells monthly plans, not a single list price per lookup. " +
            "This estimate uses the published Basic monthly overage of \$3.00 per 1,000 requests " +
            "(\$0.003 each). Included monthly allowances (for example 5,000 requests on Basic at " +
            "\$9.99/month) may make the effective cost lower. The free plan is 250 requests per month " +
            "at 10 requests/minute.",
        priceSourceUrl = "https://numlookupapi.com/pricing/",
        minIntervalMs = 1_000L,
    ),
    ABSTRACT(
        id = "abstractapi",
        displayName = "Abstract API",
        getKeyUrl = "https://www.abstractapi.com/api/phone-validation-api",
        usdPerLookup = 0.0034,
        priceNote = "Estimate uses Abstract's published Phone Validation rate of \$3.40 per 1,000 " +
            "requests (\$0.0034 each) at the Starter entry volume billed annually. " +
            "Monthly Starter is \$19 for 5,000 requests. Plans include an allowance; " +
            "published rate limits are 3 requests/second.",
        priceSourceUrl = "https://www.abstractapi.com/pricing",
        minIntervalMs = 400L,
    ),
    TWILIO(
        id = "twilio",
        displayName = "Twilio Lookup v2",
        getKeyUrl = "https://console.twilio.com",
        usdPerLookup = 0.018,
        priceNote = "Estimate uses Twilio's published Lookup prices as of October 2026: " +
            "Caller Name (inbound caller identification) \$0.01 per request plus " +
            "Line Type Intelligence \$0.008 per request, the two packages this app requests " +
            "(\$0.018 total). Caller Name is US-only; Twilio states requests outside the US " +
            "return no name and are not billed for that package. Formatting and validation are free.",
        priceSourceUrl = "https://www.twilio.com/en-us/user-authentication-identity/pricing/lookup",
        minIntervalMs = 500L,
    ),
    TELNYX(
        id = "telnyx",
        displayName = "Telnyx Number Lookup",
        getKeyUrl = "https://portal.telnyx.com",
        usdPerLookup = 0.0045,
        priceNote = "Estimate uses Telnyx's published Number Lookup prices: " +
            "Local Routing Number (LRN) \$0.0015 plus CNAM (caller-name) \$0.003, " +
            "the packages this app requests with type=carrier and type=caller-name (\$0.0045). " +
            "Mobile lookups may also include MCC/MNC at a published \$0.0025 when that data is enabled. " +
            "Enhanced caller name and caller identity are not requested.",
        priceSourceUrl = "https://telnyx.com/pricing/number-lookup",
        minIntervalMs = 500L,
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom endpoint",
        getKeyUrl = "",
        usdPerLookup = 0.0,
        priceNote = "A custom endpoint has no published price in this app.",
        priceSourceUrl = "",
        minIntervalMs = 1_000L,
        supportsBulk = false,
    ),
    ;

    companion object {
        val DEFAULT_ORDER: List<String> = entries.map { it.id }

        fun fromId(id: String): LookupProvider? = entries.firstOrNull { it.id == id }

        fun ordered(ids: List<String>): List<LookupProvider> {
            val known = entries.associateBy { it.id }
            val out = LinkedHashSet<LookupProvider>()
            ids.forEach { known[it]?.let { p -> out.add(p) } }
            entries.forEach { out.add(it) }
            return out.toList()
        }
    }
}

data class LookupField(
    val value: String?,
    val sourceId: String? = null,
    val sourceLabel: String? = null,
)

data class MergedLookup(
    val phoneNumber: String,
    val displayName: LookupField = LookupField(null),
    val carrier: LookupField = LookupField(null),
    val lineType: LookupField = LookupField(null),
    val region: LookupField = LookupField(null),
    val valid: LookupField = LookupField(null),
    val contributions: List<com.example.callguardian.model.LookupResult> = emptyList(),
) {
    val isEmpty: Boolean
        get() = displayName.value == null && carrier.value == null && lineType.value == null &&
            region.value == null && valid.value == null && contributions.isEmpty()
}

data class LookupCostEstimate(
    val providerId: String,
    val providerLabel: String,
    val uniqueCount: Int,
    val cachedCount: Int,
    val toQueryCount: Int,
    val usdPerLookup: Double,
    val estimatedUsd: Double,
    val priceNote: String,
    val priceSourceUrl: String,
    val missingContactsPermission: Boolean = false,
    val missingSmsPermission: Boolean = false,
) {
    fun confirmMessage(): String {
        val money = String.format(Locale.US, "\$%.2f", estimatedUsd)
        val unit = String.format(Locale.US, "\$%.4f", usdPerLookup).trimEnd('0').trimEnd('.')
        val skip = if (cachedCount > 0) " Skipped $cachedCount already cached for $providerLabel." else ""
        val perms = buildList {
            if (missingContactsPermission) add("contacts")
            if (missingSmsPermission) add("SMS")
        }.takeIf { it.isNotEmpty() }?.let {
            " Missing ${it.joinToString(" and ")} permission, so that set of numbers is not included."
        } ?: ""
        return if (toQueryCount == 0) {
            "All $uniqueCount unique contact and SMS/MMS numbers are already cached for $providerLabel. Nothing new will be queried.$perms"
        } else {
            "Look up $toQueryCount numbers with $providerLabel ($uniqueCount unique contacts and senders).$skip$perms\n\n" +
                "Estimated cost: $money ($toQueryCount × $unit). This is an estimate based on $providerLabel published list prices. " +
                "Your actual invoice may differ.\n\n$priceNote"
        }
    }

}

class LookupRateLimited(val retryAfterMs: Long) : RuntimeException("Lookup rate limited")
