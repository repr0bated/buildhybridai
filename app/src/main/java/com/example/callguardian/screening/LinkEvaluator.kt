package com.example.callguardian.screening

/**
 * Truth-table evaluation of links in a message. A link alone is never phishing; it has to
 * combine with a real phishing signal (look-alike, punycode, IP, brand-in-foreign-subdomain)
 * or with a public shortener plus urgency/payment/credentials.
 */
object LinkEvaluator {

    enum class HostKind {
        OFFICIAL, OFFICIAL_SHORTENER, PUBLIC_SHORTENER, LOOKALIKE, IP, PUNYCODE, BRAND_SUBDOMAIN, UNKNOWN
    }

    data class Assessment(
        val hosts: List<String>,
        val kinds: Map<String, HostKind>,
        val officialPositive: Boolean,
        val hostile: Boolean,
        val publicShortener: Boolean,
        val lookalikeHost: String?,
        val officialHost: String?,
    )

    fun assess(body: String, mentioned: List<String> = OfficialDomains.mentionedBrands(body)): Assessment {
        val hosts = OfficialDomains.hostsIn(body)
        val kinds = hosts.associateWith { kind(it, mentioned) }
        val officialPositive = hosts.isNotEmpty() && kinds.values.all { it == HostKind.OFFICIAL || it == HostKind.OFFICIAL_SHORTENER }
        val hostile = kinds.values.any { it in setOf(HostKind.LOOKALIKE, HostKind.IP, HostKind.PUNYCODE, HostKind.BRAND_SUBDOMAIN) }
        val publicShortener = kinds.values.any { it == HostKind.PUBLIC_SHORTENER }
        val lookalike = kinds.entries.firstOrNull { it.value == HostKind.LOOKALIKE || it.value == HostKind.BRAND_SUBDOMAIN }?.key
        val official = kinds.entries.firstOrNull { it.value == HostKind.OFFICIAL || it.value == HostKind.OFFICIAL_SHORTENER }?.key
        return Assessment(hosts, kinds, officialPositive, hostile, publicShortener, lookalike, official)
    }

    fun kind(host: String, mentioned: List<String>): HostKind {
        val h = OfficialDomains.hostOf(host)
        if (OfficialDomains.isIpHost(h)) return HostKind.IP
        if (OfficialDomains.isPunycode(h)) return HostKind.PUNYCODE
        if (OfficialDomains.isAnyOfficialHost(h) || OfficialDomains.isOfficialShortener(h)) {
            return if (OfficialDomains.isOfficialShortener(h)) HostKind.OFFICIAL_SHORTENER else HostKind.OFFICIAL
        }
        val look = mentioned.firstOrNull { OfficialDomains.isLookalike(h, it) }
            ?: OfficialDomains.mentionedBrands(h.replace('.', ' ')).firstOrNull { OfficialDomains.isLookalike(h, it) }
        if (look != null) {
            // Brand token as a label of a foreign registrable domain.
            val labels = h.split('.')
            val tokens = OfficialDomains.tokensFor(OfficialDomains.brandKey(look) ?: look)
            val foreign = tokens.any { t -> labels.dropLast(1).any { lab -> lab == t || lab.startsWith("$t-") } } &&
                OfficialDomains.registrableDomain(h)?.let { !OfficialDomains.isAnyOfficialHost(it) } == true
            return if (foreign) HostKind.BRAND_SUBDOMAIN else HostKind.LOOKALIKE
        }
        if (OfficialDomains.isPublicShortener(h)) return HostKind.PUBLIC_SHORTENER
        return HostKind.UNKNOWN
    }
}
