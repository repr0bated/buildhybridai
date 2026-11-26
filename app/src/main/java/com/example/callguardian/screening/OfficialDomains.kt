package com.example.callguardian.screening

import com.google.common.net.InternetDomainName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Official customer-service domains for well-known brands, loaded from
 * `assets/schemas/official_domains.json`. Used to tell a real link from a look-alike —
 * never to whitelist a text just because it mentions the brand name.
 *
 * Registrable domains are resolved with the Public Suffix List (Guava [InternetDomainName]),
 * not substring matching. Official first-party shorteners (a.co, py.pl, g.co, …) count as
 * official for their brand.
 */
object OfficialDomains {

    @Serializable
    data class Catalog(
        val version: String = "",
        val publicShorteners: List<String> = emptyList(),
        val brands: List<Brand> = emptyList(),
    )

    @Serializable
    data class Brand(
        val key: String,
        val names: List<String> = emptyList(),
        val domains: List<String> = emptyList(),
        val shorteners: List<String> = emptyList(),
    )

    @Volatile private var catalog: Catalog = Catalog()
    @Volatile private var loaded: Boolean = false

    private val json = Json { ignoreUnknownKeys = true }

    private val aliases: Map<String, String> get() = _aliases
    private var _aliases: Map<String, String> = emptyMap()
    private var byBrand: Map<String, Brand> = emptyMap()
    private var officialRegs: Set<String> = emptySet()
    private var officialShorteners: Set<String> = emptySet()
    private var publicShorteners: Set<String> = emptySet()
    private var mentionNames: List<String> = emptyList()

    private val IP_RE = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")
    private val URL_RE = Regex(
        """(?i)\b((?:https?://|www\.)[^\s]+|[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|info|top|xyz|icu|click|live|cc|vip|shop|buzz|link|online|site|me|co|us|io|ly|gd|gl|gov|app|edu|dev)(?:/[^\s]*)?)"""
    )

    init {
        val files = listOf(
            File("src/main/assets/schemas/official_domains.json"),
            File("app/src/main/assets/schemas/official_domains.json"),
        )
        files.firstOrNull { it.isFile }?.let { load(it.readText()) }
    }

    fun load(text: String) {
        val c = json.decodeFromString(Catalog.serializer(), text)
        apply(c)
    }

    private fun apply(c: Catalog) {
        catalog = c
        byBrand = c.brands.associateBy { it.key }
        val alias = mutableMapOf<String, String>()
        for (b in c.brands) {
            alias[b.key] = b.key
            b.names.forEach { alias[it.lowercase()] = b.key; alias[it.lowercase().replace(" ", "")] = b.key }
        }
        _aliases = alias
        officialRegs = c.brands.flatMap { it.domains + it.shorteners }.map { registrableDomain(it) ?: hostOf(it) }.toSet()
        officialShorteners = c.brands.flatMap { it.shorteners }.map { hostOf(it) }.toSet()
        publicShorteners = c.publicShorteners.map { hostOf(it) }.toSet()
        mentionNames = c.brands.flatMap { it.names + it.key }.distinct().sortedByDescending { it.length }
        loaded = true
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
        val b = byBrand[key] ?: return emptySet()
        return (b.domains + b.shorteners).toSet()
    }

    fun allOfficialHosts(): Set<String> = officialRegs

    fun isPublicShortener(host: String): Boolean {
        val h = hostOf(host)
        val reg = registrableDomain(h) ?: h
        return reg in publicShorteners || h in publicShorteners
    }

    fun isOfficialShortener(host: String): Boolean {
        val h = hostOf(host)
        val reg = registrableDomain(h) ?: h
        return reg in officialShorteners || h in officialShorteners
    }

    fun hostOf(url: String): String =
        url.trim().lowercase()
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .substringBefore('/').substringBefore('?').substringBefore('#')
            .substringBefore(':')
            .trimEnd('.', ',', ')', ']', '>')

    fun isIpHost(host: String): Boolean = IP_RE.matches(hostOf(host))

    fun isPunycode(host: String): Boolean = hostOf(host).contains("xn--")

    /**
     * Registrable (eTLD+1) domain via the Public Suffix List. `cash.app` and `a.co` stay
     * intact because `app` and `co` are public suffixes.
     */
    fun registrableDomain(host: String): String? {
        val h = hostOf(host)
        if (h.isBlank()) return null
        if (isIpHost(h)) return h
        return try {
            val idn = InternetDomainName.from(h)
            when {
                idn.isTopPrivateDomain -> idn.toString()
                idn.hasPublicSuffix() && idn.isUnderRegistrySuffix -> idn.topPrivateDomain().toString()
                else -> h
            }
        } catch (_: IllegalArgumentException) {
            h
        } catch (_: IllegalStateException) {
            h
        }
    }

    fun hostsIn(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        return URL_RE.findAll(body).map { hostOf(it.value) }.filter { it.contains('.') || isIpHost(it) }.distinct().toList()
    }

    fun isOfficialHost(host: String, official: Set<String>): Boolean {
        val h = hostOf(host)
        val reg = registrableDomain(h) ?: h
        return official.any { d ->
            val od = hostOf(d)
            val oreg = registrableDomain(od) ?: od
            h == od || h.endsWith(".$od") || reg == oreg || h == oreg || h.endsWith(".$oreg")
        }
    }

    fun isOfficialHost(host: String, brandOrName: String): Boolean =
        isOfficialHost(host, officialHosts(brandOrName))

    fun isAnyOfficialHost(host: String): Boolean {
        val h = hostOf(host)
        val reg = registrableDomain(h) ?: h
        if (reg in officialRegs || h in officialShorteners || reg in officialShorteners) return true
        return officialRegs.any { d -> h == d || h.endsWith(".$d") || reg == d }
    }

    /**
     * True look-alike: typo/edit-distance or homoglyph of an official registrable domain,
     * or a brand token used as a DNS label on a foreign registrable domain.
     * Raw substring matching is not used (so pineapple.com is not an Apple look-alike).
     */
    fun isLookalike(host: String, brandOrName: String): Boolean {
        val key = brandKey(brandOrName) ?: return false
        val h = hostOf(host)
        if (isOfficialHost(h, key)) return false
        val reg = registrableDomain(h) ?: h
        val official = officialHosts(key).map { registrableDomain(it) ?: hostOf(it) }
        if (reg in official) return false
        if (official.any { typoOf(reg, it) || typoOf(normalizeHomoglyphs(reg), normalizeHomoglyphs(it)) }) return true
        val labels = h.split('.')
        val tokens = tokensFor(key)
        // Brand as a whole label, or a hyphenated label starting/ending with the brand, on a foreign domain.
        return tokens.any { t ->
            t.length >= 3 && labels.any { lab ->
                lab == t || lab.startsWith("$t-") || lab.endsWith("-$t")
            } && official.none { h == it || h.endsWith(".$it") }
        }
    }

    fun lookalikeIn(hosts: List<String>, brandOrName: String): String? =
        hosts.firstOrNull { isLookalike(it, brandOrName) }

    fun officialIn(hosts: List<String>, brandOrName: String): String? =
        hosts.firstOrNull { isOfficialHost(it, brandOrName) }

    fun mentionedBrands(body: String): List<String> {
        val lower = body.lowercase()
        val found = mutableListOf<String>()
        for (n in mentionNames) {
            val ok = if (n.contains(' ') || n.contains('-')) lower.contains(n)
            else Regex("""\b${Regex.escape(n)}\b""").containsMatchIn(lower)
            if (ok) {
                val key = brandKey(n) ?: n
                if (key !in found) found += key
            }
        }
        return found
    }

    fun tokensFor(brandKey: String): List<String> {
        val b = byBrand[brandKey(brandKey) ?: brandKey]
        val names = (b?.names ?: listOf(brandKey)).map { it.lowercase().replace(" ", "") }
        val extra = when (brandKey(brandKey) ?: brandKey) {
            "ezpass" -> listOf("e-zpass", "ezpass")
            "cashapp" -> listOf("cashapp", "cash-app")
            "wellsfargo" -> listOf("wellsfargo", "wells-fargo")
            "bankofamerica" -> listOf("bankofamerica", "bank-of-america")
            "socialsecurity" -> listOf("socialsecurity", "social-security")
            else -> emptyList()
        }
        return (names + extra + listOf(brandKey)).distinct().filter { it.length >= 3 }
    }

    /** Levenshtein typo: distance 1, or 2 when both sides are at least 8 characters. */
    internal fun typoOf(a: String, b: String): Boolean {
        if (a == b) return false
        val d = levenshtein(a, b)
        return d == 1 || (d == 2 && minOf(a.length, b.length) >= 8)
    }

    internal fun normalizeHomoglyphs(s: String): String = buildString(s.length) {
        for (ch in s) append(
            when (ch) {
                '0' -> 'o'; '1' -> 'l'; '3' -> 'e'; '5' -> 's'; '7' -> 't'
                'а' -> 'a'; 'е' -> 'e'; 'о' -> 'o'; 'р' -> 'p'; 'с' -> 'c'; 'у' -> 'y'; 'х' -> 'x'
                'і' -> 'i'; 'ѕ' -> 's'
                else -> ch
            },
        )
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }
}
