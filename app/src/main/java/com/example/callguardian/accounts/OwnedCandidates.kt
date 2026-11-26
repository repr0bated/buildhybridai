package com.example.callguardian.accounts

import com.example.callguardian.data.db.ScreeningEventEntity
import com.example.callguardian.screening.OfficialDomains

/** A repeat sender with consistent official domains, offered for one-tap confirmation — never auto-marked. */
data class OwnedCandidate(
    val service: String,
    val senders: List<String>,
    val domains: List<String>,
    val messageCount: Int,
    val sampleBody: String?,
)

object OwnedCandidates {
    /**
     * Suggest owned accounts from analyzed texts: the same sender appears more than once,
     * and every extracted link domain is official for one brand (or there is a single official
     * domain shared across the group). Already-owned senders are skipped.
     */
    fun suggest(events: List<ScreeningEventEntity>, already: List<OwnedAccount>, minCount: Int = 2): List<OwnedCandidate> {
        val sms = events.filter { it.channel == "SMS" && !it.number.isNullOrBlank() && !it.body.isNullOrBlank() }
        val groups = sms.groupBy { SenderIds.normalize(it.number!!) }
        val out = mutableListOf<OwnedCandidate>()
        for ((sender, rows) in groups) {
            if (rows.size < minCount) continue
            if (already.any { it.matchesSender(sender) }) continue
            val hosts = rows.flatMap { OfficialDomains.hostsIn(it.body.orEmpty()) }.distinct()
            if (hosts.isEmpty()) continue
            val mentioned = rows.flatMap { OfficialDomains.mentionedBrands(it.body.orEmpty()) }.distinct()
            val brand = mentioned.firstOrNull { b -> hosts.all { OfficialDomains.isOfficialHost(it, b) } }
                ?: mentioned.firstOrNull { b -> hosts.any { OfficialDomains.isOfficialHost(it, b) } && hosts.none { OfficialDomains.isLookalike(it, b) } }
                ?: OfficialDomains.allOfficialHosts().let { all ->
                    if (hosts.isNotEmpty() && hosts.all { OfficialDomains.isOfficialHost(it, all) }) {
                        OfficialDomains.mentionedBrands(rows.joinToString(" ") { it.body.orEmpty() }).firstOrNull()
                    } else null
                }
            if (brand == null) continue
            if (hosts.any { OfficialDomains.isLookalike(it, brand) }) continue
            val official = hosts.filter { OfficialDomains.isOfficialHost(it, brand) }.distinct()
            if (official.isEmpty() || official.size != hosts.size) continue
            val service = rows.firstNotNullOfOrNull { e ->
                // Prefer a human name already in the text (E-ZPass, Cash App).
                Regex("""(?i)(e-zpass|ezpass|cash app|cashapp)""").find(e.body.orEmpty())?.value
            }?.let { pretty(it) } ?: pretty(brand)
            val senders = rows.map { it.number!! }.distinctBy { SenderIds.normalize(it) }
            out += OwnedCandidate(service, senders, official, rows.size, rows.maxByOrNull { it.receivedAt }?.body)
        }
        return out.sortedByDescending { it.messageCount }
    }

    private fun pretty(brand: String): String = when (OfficialDomains.brandKey(brand)) {
        "ezpass", "e-zpass" -> "E-ZPass"
        "cashapp", "cash-app" -> "Cash App"
        "wellsfargo" -> "Wells Fargo"
        "bankofamerica" -> "Bank of America"
        "usps" -> "USPS"
        else -> brand.replaceFirstChar { it.uppercase() }
    }
}
