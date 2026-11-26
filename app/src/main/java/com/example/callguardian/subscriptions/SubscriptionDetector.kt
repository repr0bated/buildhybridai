package com.example.callguardian.subscriptions

import com.example.callguardian.model.Channel
import com.example.callguardian.model.MessageSource
import com.example.callguardian.model.RiskLevel
import com.example.callguardian.screening.HeuristicScanner
import com.example.callguardian.screening.ScanInput

/** One text from the SMS inbox or a watched messaging app. */
data class InboxSms(val id: Long, val address: String, val body: String, val date: Long, val source: MessageSource = MessageSource.SMS)

enum class LinkKind { UNSUBSCRIBE, MANAGE_SUBSCRIPTION, OTHER }

data class LinkCandidate(val url: String, val domain: String, val kind: LinkKind)

/**
 * Subscription texts grouped by service (e.g. every Cash App short code in one row).
 * One confirmation sends STOP once to each distinct textable sender and can also open a
 * manage-subscription page when a link is present.
 */
data class SubscriptionGroup(
    val service: String,
    val senders: List<String>,
    val count: Int,
    val lastBody: String,
    val lastDate: Long,
    val signals: List<String>,
    /** Opt-out keyword to text. STOP when the messages do not name a carrier keyword. */
    val keyword: String,
    val links: List<LinkCandidate>,
    val bestLink: LinkCandidate?,
    val brand: String?,
    val scamRisk: RiskLevel,
    val source: MessageSource = MessageSource.SMS,
    /** "rules" (brand/sender match) or "on_device" (Gemini Nano 4). */
    val serviceBy: String = "rules",
    val messageIds: List<Long> = emptyList(),
) {
    val sender: String get() = senders.first()
    val textableSenders: List<String> get() = senders.filter { SubscriptionDetector.textable(it) }.distinct()
    /** Our app can only text from the SIM, so Google Voice / WhatsApp get an in-app hint instead. */
    val canReplyStop: Boolean get() = source == MessageSource.SMS && textableSenders.isNotEmpty()
    val replyStopInVoice: Boolean get() = source == MessageSource.GOOGLE_VOICE
    val serviceKey: String get() = SubscriptionDetector.serviceKey(service, source, senders)
}

data class LinkSafety(val suspicious: Boolean, val warning: String?)

/**
 * Rule-engine pass for subscription / marketing texts. Pure Kotlin (unit tested).
 * Gemini Nano 4 names the service and may merge more groups afterwards.
 */
object SubscriptionDetector {
    val OPT_OUT_KEYWORDS = listOf("STOP", "STOPALL", "UNSUBSCRIBE", "CANCEL", "END", "QUIT")

    private val replyKeyword = Regex("""(?i)\b(?:reply|text|txt|send|respond)\s+["']?([A-Za-z]{3,11})["']?\s+(?:to\s+)?(?:stop|opt[\s-]?out|unsubscribe|cancel|end|quit|be removed|unsub)""")
    private val bareStop = Regex("""(?i)\b(?:reply|text|txt)\s+["']?(STOP|STOPALL|UNSUBSCRIBE|CANCEL|END|QUIT)\b""")
    private val patterns = listOf(
        "reply_stop" to Regex("""(?i)\b(reply|text|txt)\s+stop\b"""),
        "rates" to Regex("""(?i)msg\s*(&|and)\s*data\s*rates"""),
        "opt_out" to Regex("""(?i)\bopt[\s-]?out\b"""),
        "unsubscribe" to Regex("""(?i)\bunsubscribe\b|\bunsub\b"""),
        "manage" to Regex("""(?i)manage\s+(your\s+)?(texts|alerts|subscriptions?|notifications|preferences)"""),
        "help" to Regex("""(?i)\breply\s+help\b"""),
        "freq" to Regex("""(?i)\b(recurring|autodialed)\s+(msgs?|messages)\b|\bmsg\s+freq"""),
    )
    private val url = Regex("""(?i)\b((?:https?://)?(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/[^\s]*)?)""")
    private val brandPrefix = Regex("""^\s*([A-Z][A-Za-z0-9&'.-]{1,24}(?:\s[A-Z][A-Za-z0-9&'-]{1,20})?)\s*[:\-]""")
    private val shortener = setOf("bit.ly", "tinyurl.com", "t.co", "ow.ly", "is.gd", "rb.gy", "cutt.ly", "t.ly", "shorturl.at")
    private val riskyTlds = setOf("top", "xyz", "info", "click", "live", "shop", "icu", "buzz", "rest", "cfd", "sbs", "online", "site")

    fun isShortCode(sender: String) = sender.all { it.isDigit() } && sender.length in 4..6
    fun textable(sender: String) = sender.trimStart('+').all { it.isDigit() } && sender.trimStart('+').length in 3..15

    fun signals(sender: String, body: String): List<String> = buildList {
        if (isShortCode(sender)) add("short_code")
        patterns.forEach { (id, r) -> if (r.containsMatchIn(body)) add(id) }
    }

    /** A sender is a subscription when at least one opt-out/marketing pattern matches (a short code alone isn't enough: banks send codes from them). */
    fun isSubscription(sender: String, body: String): Boolean = signals(sender, body).any { it != "short_code" }

    fun keyword(body: String): String? {
        replyKeyword.find(body)?.groupValues?.get(1)?.uppercase()?.let { if (it in OPT_OUT_KEYWORDS) return it }
        bareStop.find(body)?.groupValues?.get(1)?.uppercase()?.let { return it }
        return if (Regex("""(?i)\bstop\b""").containsMatchIn(body)) "STOP" else null
    }

    fun links(body: String): List<LinkCandidate> = url.findAll(body).map { m ->
        val raw = m.groupValues[1].trimEnd('.', ',', ')', '!', '?', ';')
        val full = if (raw.startsWith("http", true)) raw else "https://$raw"
        val domain = full.substringAfter("://").substringBefore('/').lowercase().removePrefix("www.")
        val ctx = body.substring(maxOf(0, m.range.first - 40), m.range.first).lowercase() + full.lowercase()
        val kind = when {
            Regex("unsub|opt-?out|optout|stop").containsMatchIn(ctx) -> LinkKind.UNSUBSCRIBE
            Regex("manage|preferences|notification|settings|subscription|alerts").containsMatchIn(ctx) -> LinkKind.MANAGE_SUBSCRIPTION
            else -> LinkKind.OTHER
        }
        LinkCandidate(full, domain, kind)
    }.filter { it.domain.contains('.') }.distinctBy { it.url }.toList()

    fun bestLink(links: List<LinkCandidate>): LinkCandidate? =
        links.firstOrNull { it.kind == LinkKind.UNSUBSCRIBE } ?: links.firstOrNull { it.kind == LinkKind.MANAGE_SUBSCRIPTION }

    fun brand(body: String): String? = brandPrefix.find(body)?.groupValues?.get(1)?.trim()

    private fun registrable(domain: String) = domain.split('.').takeLast(2).joinToString(".")
    fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Suspicious when the sender looks like a scam, or the domain doesn't belong to the brand that signed the text. */
    fun linkSafety(link: LinkCandidate, brand: String?, scamRisk: RiskLevel): LinkSafety {
        val reasons = mutableListOf<String>()
        if (scamRisk >= RiskLevel.MEDIUM) reasons += "The sender's texts look like a scam (${scamRisk.name.lowercase()} risk)"
        val reg = registrable(link.domain)
        if (reg in shortener) reasons += "Shortened link hides the real site"
        if (link.domain.substringAfterLast('.') in riskyTlds) reasons += "Unusual domain ending .${link.domain.substringAfterLast('.')}"
        if (!link.url.startsWith("https://", true)) reasons += "Not an https link"
        if (brand != null) {
            val b = squash(brand); val label = squash(reg.substringBefore('.'))
            if (b.length >= 3 && !label.contains(b) && !b.contains(label)) reasons += "Domain $reg doesn't match \"$brand\""
            else if (b.length >= 3 && label != b && label.contains(b)) reasons += "Look-alike domain $reg for \"$brand\""
        }
        return LinkSafety(reasons.isNotEmpty(), reasons.joinToString("; ").take(200).ifBlank { null })
    }

    /**
     * Service key used to merge rows. A named service (brand or Nano name) is shared across
     * senders of the same source; a bare number stays unique so unrelated short codes don't merge.
     */
    fun serviceKey(service: String, source: MessageSource, senders: List<String>): String {
        val s = squash(service)
        val fallback = senders.size == 1 && squash(senders.first()) == s
        return if (s.length >= 3 && !fallback) "${s}:${source.wire}"
        else "sender:${source.wire}:${senders.sorted().joinToString(",")}"
    }

    fun withService(g: SubscriptionGroup, name: String, by: String = "on_device"): SubscriptionGroup {
        val cleaned = name.trim().take(80)
        if (cleaned.isBlank()) return g
        return g.copy(service = cleaned, brand = g.brand ?: cleaned, serviceBy = by)
    }

    /** Group by sender, then merge senders that share a brand (fallback before Nano). */
    fun group(inbox: List<InboxSms>): List<SubscriptionGroup> = mergeByService(groupBySender(inbox))

    fun groupBySender(inbox: List<InboxSms>): List<SubscriptionGroup> =
        inbox.groupBy { it.source to it.address.trim() }
            .mapNotNull { (key, msgs) ->
                val (source, sender) = key
                if (sender.isBlank()) return@mapNotNull null
                val subs = msgs.filter { isSubscription(sender, it.body) }
                if (subs.isEmpty()) return@mapNotNull null
                val sorted = msgs.sortedByDescending { it.date }
                val last = sorted.first()
                val allLinks = subs.sortedByDescending { it.date }.flatMap { links(it.body) }.distinctBy { it.url }
                val kw = subs.firstNotNullOfOrNull { keyword(it.body) } ?: "STOP"
                val risk = sorted.take(5).maxOf { HeuristicScanner.scan(ScanInput(Channel.SMS, sender, it.body)).riskLevel }
                val detectedBrand = subs.firstNotNullOfOrNull { brand(it.body) }
                SubscriptionGroup(
                    service = detectedBrand ?: sender,
                    senders = listOf(sender),
                    count = msgs.size,
                    lastBody = last.body,
                    lastDate = last.date,
                    signals = subs.flatMap { signals(sender, it.body) }.distinct(),
                    keyword = kw,
                    links = allLinks.take(6),
                    bestLink = bestLink(allLinks),
                    brand = detectedBrand,
                    scamRisk = risk,
                    source = source,
                    serviceBy = "rules",
                    messageIds = msgs.map { it.id },
                )
            }.sortedByDescending { it.lastDate }

    fun mergeByService(groups: List<SubscriptionGroup>): List<SubscriptionGroup> =
        groups.groupBy { it.serviceKey }.values.map { merge(it) }.sortedByDescending { it.lastDate }

    fun merge(parts: List<SubscriptionGroup>): SubscriptionGroup {
        if (parts.size == 1) return parts[0]
        val sorted = parts.sortedByDescending { it.lastDate }
        val latest = sorted.first()
        val links = sorted.flatMap { it.links }.distinctBy { it.url }.take(6)
        val named = sorted.firstOrNull { it.serviceBy == "on_device" && squash(it.service).length >= 3 }?.service
            ?: sorted.mapNotNull { it.brand }.maxByOrNull { it.length }
            ?: latest.service
        val serviceBy = if (sorted.any { it.serviceBy == "on_device" && squash(it.service) == squash(named) }) "on_device" else "rules"
        return SubscriptionGroup(
            service = named,
            senders = sorted.flatMap { it.senders }.distinct(),
            count = parts.sumOf { it.count },
            lastBody = latest.lastBody,
            lastDate = latest.lastDate,
            signals = parts.flatMap { it.signals }.distinct(),
            keyword = parts.map { it.keyword }.firstOrNull { it != "STOP" } ?: "STOP",
            links = links,
            bestLink = bestLink(links),
            brand = parts.mapNotNull { it.brand }.firstOrNull(),
            scamRisk = parts.maxOf { it.scamRisk },
            source = latest.source,
            serviceBy = serviceBy,
            messageIds = parts.flatMap { it.messageIds }.distinct(),
        )
    }
}
