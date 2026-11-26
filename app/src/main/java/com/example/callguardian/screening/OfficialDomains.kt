package com.example.callguardian.screening

/**
 * Official customer-service domains for well-known brands that scammers impersonate.
 * Used only to tell a real link from a look-alike — never to whitelist a text just
 * because it mentions the brand name.
 *
 * E-ZPass: verified e-zpassny.com (NY), ezpassnj.com (NJ), e-zpassiag.com (legacy IAG)
 * and e-zpassgroup.org (current group site). Sister agency CSCs use the same naming
 * pattern (ezpasspa.com, ezpassmd.com, …). PANYNJ also cites ezpassny.com and
 * tollsbymailny.com.
 */
object OfficialDomains {

    val EZPASS: Set<String> = setOf(
        "e-zpassny.com", "ezpassny.com",
        "ezpassnj.com",
        "e-zpassiag.com", "e-zpassgroup.org",
        "tollsbymailny.com",
        "ezpasspa.com",
        "ezpassmd.com",
        "ezpassde.com",
        "ezpassma.com",
        "ezpassva.com",
        "ezpasswv.com",
        "ezpassnh.com",
        "ezpassri.com",
        "ezpassmaine.com",
        "ezpassoh.com",
        "getipass.com", "illinoistollway.com",
        "ncquickpass.com",
    )

    /** Brand key (no spaces, lowercased, hyphens kept) → official registrable hosts. */
    private val byBrand: Map<String, Set<String>> = mapOf(
        "usps" to setOf("usps.com"),
        "fedex" to setOf("fedex.com"),
        "ups" to setOf("ups.com"),
        "dhl" to setOf("dhl.com"),
        "amazon" to setOf("amazon.com", "amazon.co.uk", "amzn.to"),
        "apple" to setOf("apple.com"),
        "icloud" to setOf("icloud.com", "apple.com"),
        "paypal" to setOf("paypal.com"),
        "netflix" to setOf("netflix.com"),
        "chase" to setOf("chase.com"),
        "wellsfargo" to setOf("wellsfargo.com"),
        "bankofamerica" to setOf("bankofamerica.com"),
        "citi" to setOf("citi.com", "citibank.com"),
        "irs" to setOf("irs.gov"),
        "medicare" to setOf("medicare.gov"),
        "socialsecurity" to setOf("ssa.gov", "socialsecurity.gov"),
        "dmv" to setOf("dmv.org"), // weak; state DMVs vary — not used as a blanket allow
        "ezpass" to EZPASS,
        "e-zpass" to EZPASS,
        "fastrak" to setOf("bayareafastrak.org", "thefastrak.org", "fastrak.org"),
        "sunpass" to setOf("sunpass.com", "sunpass.org"),
        "coinbase" to setOf("coinbase.com"),
        "venmo" to setOf("venmo.com"),
        "zelle" to setOf("zellepay.com"),
        "microsoft" to setOf("microsoft.com", "office.com", "live.com"),
        "cashapp" to setOf("cash.app", "cashapp.com", "square.com"),
        "cash-app" to setOf("cash.app", "cashapp.com", "square.com"),
    )

    private val aliases: Map<String, String> = buildMap {
        fun alias(key: String, vararg names: String) { names.forEach { put(it, key) } }
        alias("ezpass", "ezpass", "e-zpass", "e-z pass", "e z pass", "ez pass")
        alias("cashapp", "cash app", "cashapp", "cash-app")
        alias("wellsfargo", "wells fargo", "wellsfargo")
        alias("bankofamerica", "bank of america", "bankofamerica")
        alias("socialsecurity", "social security", "socialsecurity")
        byBrand.keys.forEach { put(it, if (it in setOf("e-zpass")) "ezpass" else it) }
    }

    fun brandKey(name: String): String? {
        val n = name.lowercase().trim()
        aliases[n]?.let { return it }
        val compact = n.replace(" ", "")
        aliases[compact]?.let { return it }
        return byBrand.keys.firstOrNull { it == compact }
    }

    fun officialHosts(brandOrName: String): Set<String> {
        val key = brandKey(brandOrName) ?: return emptySet()
        return byBrand[key] ?: byBrand[if (key == "e-zpass") "ezpass" else key].orEmpty()
    }

    fun allOfficialHosts(): Set<String> = byBrand.values.flatten().toSet()

    fun hostOf(url: String): String =
        url.trim().lowercase()
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .substringBefore('/').substringBefore('?').substringBefore(':')
            .trimEnd('.', ',', ')', ']', '>')

    fun hostsIn(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        return URL_RE.findAll(body).map { hostOf(it.value) }.filter { it.contains('.') }.distinct().toList()
    }

    fun isOfficialHost(host: String, official: Set<String>): Boolean {
        val h = hostOf(host)
        return official.any { d -> h == d || h.endsWith(".$d") }
    }

    fun isOfficialHost(host: String, brandOrName: String): Boolean =
        isOfficialHost(host, officialHosts(brandOrName))

    fun isAnyOfficialHost(host: String): Boolean = isOfficialHost(host, allOfficialHosts())

    /** Host mentions a brand token but is not that brand's official domain. */
    fun isLookalike(host: String, brandOrName: String): Boolean {
        val key = brandKey(brandOrName) ?: return false
        val h = hostOf(host)
        if (isOfficialHost(h, key)) return false
        val tokens = tokensFor(key)
        return tokens.any { t -> h.contains(t) }
    }

    fun lookalikeIn(hosts: List<String>, brandOrName: String): String? =
        hosts.firstOrNull { isLookalike(it, brandOrName) }

    fun officialIn(hosts: List<String>, brandOrName: String): String? =
        hosts.firstOrNull { isOfficialHost(it, brandOrName) }

    fun mentionedBrands(body: String): List<String> {
        val lower = body.lowercase()
        val found = mutableListOf<String>()
        // Longer tokens first so "e-zpass" wins over stray matches.
        val names = listOf(
            "e-zpass", "ezpass", "e-z pass", "cash app", "cashapp", "wells fargo", "bank of america",
            "social security", "usps", "fedex", "ups", "dhl", "amazon", "apple", "icloud", "paypal",
            "netflix", "chase", "citi", "irs", "medicare", "dmv", "fastrak", "sunpass", "coinbase",
            "venmo", "zelle", "microsoft",
        )
        for (n in names) {
            val ok = if (n.contains(' ') || n.contains('-')) lower.contains(n)
            else Regex("""\b${Regex.escape(n)}\b""").containsMatchIn(lower)
            if (ok) {
                val key = brandKey(n) ?: n
                if (key !in found) found += key
            }
        }
        return found
    }

    fun tokensFor(brandKey: String): List<String> = when (brandKey) {
        "ezpass", "e-zpass" -> listOf("e-zpass", "ezpass")
        "cashapp", "cash-app" -> listOf("cashapp", "cash-app")
        "wellsfargo" -> listOf("wellsfargo", "wells-fargo")
        "bankofamerica" -> listOf("bankofamerica", "bank-of-america")
        "socialsecurity" -> listOf("socialsecurity", "social-security")
        else -> listOf(brandKey)
    }

    private val URL_RE = Regex(
        """(?i)\b((?:https?://|www\.)[^\s]+|[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|info|top|xyz|icu|click|live|cc|vip|shop|buzz|link|online|site|me|co|us|io|ly|gd|gl|gov|xin)(?:/[^\s]*)?)"""
    )
}
