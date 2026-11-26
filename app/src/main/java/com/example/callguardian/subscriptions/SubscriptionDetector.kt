package com.example.callguardian.subscriptions

import com.example.callguardian.model.Channel
import com.example.callguardian.model.MessageSource
import com.example.callguardian.model.RiskLevel
import com.example.callguardian.screening.HeuristicScanner
import com.example.callguardian.screening.ScanInput

/** One text from the SMS inbox. */
data class InboxSms(val id: Long, val address: String, val body: String, val date: Long, val source: MessageSource = MessageSource.SMS)

enum class LinkKind { UNSUBSCRIBE, MANAGE_SUBSCRIPTION, OTHER }

data class LinkCandidate(val url: String, val domain: String, val kind: LinkKind)

/** A subscription sender, grouped from the inbox. */
data class SubscriptionGroup(
    val sender: String,
    val count: Int,
    val lastBody: String,
    val lastDate: Long,
    val signals: List<String>,
    /** Opt-out keyword found in the texts (STOP by default when the sender takes text replies). */
    val keyword: String?,
    val links: List<LinkCandidate>,
    /** The rule engine's pick; the on-device model may pick another candidate. */
    val bestLink: LinkCandidate?,
    val brand: String?,
    val scamRisk: RiskLevel,
    val source: MessageSource = MessageSource.SMS,
) {
    /** Our app can only text from the SIM, so Google Voice senders get "reply STOP in Voice" instead. */
    val canReplyStop: Boolean get() = source == MessageSource.SMS && keyword != null && SubscriptionDetector.textable(sender)
    val replyStopInVoice: Boolean get() = source == MessageSource.GOOGLE_VOICE && keyword != null
}

data class LinkSafety(val suspicious: Boolean, val warning: String?)

/**
 * Rule-engine pass for subscription / marketing texts. Pure Kotlin (SubscriptionDetectorTest).
 * Gemini Nano 4 confirms groups on the phone afterwards (SubscriptionReviewer).
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
    private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

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

    fun group(inbox: List<InboxSms>): List<SubscriptionGroup> =
        inbox.groupBy { it.source to it.address.trim() }
            .mapNotNull { (key, msgs) ->
                val (source, sender) = key
                val subs = msgs.filter { isSubscription(sender, it.body) }
                if (subs.isEmpty()) return@mapNotNull null
                val sorted = msgs.sortedByDescending { it.date }
                val last = sorted.first()
                val allLinks = subs.sortedByDescending { it.date }.flatMap { links(it.body) }.distinctBy { it.url }
                val kw = subs.firstNotNullOfOrNull { keyword(it.body) }
                val risk = sorted.take(5).maxOf { HeuristicScanner.scan(ScanInput(Channel.SMS, sender, it.body)).riskLevel }
                SubscriptionGroup(sender, msgs.size, last.body, last.date,
                    subs.flatMap { signals(sender, it.body) }.distinct(), kw, allLinks.take(6), bestLink(allLinks),
                    subs.firstNotNullOfOrNull { brand(it.body) }, risk, source)
            }.sortedByDescending { it.lastDate }
}
