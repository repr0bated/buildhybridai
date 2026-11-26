package com.example.callguardian.ui

import com.example.callguardian.data.db.QuarantinedSmsEntity
import com.example.callguardian.data.db.ReceiptEntity
import com.example.callguardian.data.db.ScreeningEventEntity
import com.example.callguardian.methods.CallLogItem
import com.example.callguardian.privacy.PrivacyKeys
import com.example.callguardian.subscriptions.InboxSms
import com.example.callguardian.subscriptions.SubscriptionGroup

enum class SearchChip { CALLS, TEXTS, SCAM, SUBSCRIPTION, QUARANTINED, PRIVATE }

enum class SearchKind { CALL, TEXT, RECEIPT, SUBSCRIPTION, QUARANTINED }

data class SearchHit(
    val kind: SearchKind,
    val id: Long,
    val title: String,
    val snippet: String,
    val time: Long,
    val eventId: Long? = null,
    val receiptId: Long? = null,
    val address: String? = null,
    val private: Boolean = false,
)

/** Pure search / FTS query helpers. Unit-tested. */
object SearchMatcher {
    private val split = Regex("[\\s,;:/]+")

    fun tokens(query: String): List<String> =
        query.trim().split(split).map { it.trim() }.filter { it.length >= 1 }

    fun digits(s: String?) = s?.filter { it.isDigit() }.orEmpty()

    /**
     * FTS4 MATCH expression: prefix tokens, quoted to avoid operators. Null when the query
     * has nothing FTS can use (empty or digits-only — those go through the number path).
     */
    fun ftsQuery(query: String): String? {
        val words = tokens(query).map { tok ->
            buildString { for (c in tok) if (c != '"' && c != '*' && c != ':' && c != '(' && c != ')') append(c) }
        }.filter { it.length >= 2 && digits(it) != it }
        if (words.isEmpty()) return null
        return words.joinToString(" OR ") { "$it*" }
    }

    fun digitQuery(query: String): String = digits(query)

    fun matchesText(query: String, vararg fields: String?): Boolean {
        if (query.isBlank()) return true
        val toks = tokens(query.lowercase())
        if (toks.isEmpty()) return true
        val hay = fields.filterNotNull().joinToString("\n").lowercase()
        val hayDigits = digits(hay)
        return toks.all { t ->
            val d = digits(t)
            hay.contains(t) || (d.length >= 3 && hayDigits.contains(d))
        }
    }

    fun eventPrivate(e: ScreeningEventEntity, marks: Set<String>) =
        PrivacyKeys.coversEvent(marks, e.id, e.number ?: e.displayName, e.channel == "CALL")

    fun addressPrivate(address: String?, marks: Set<String>) =
        !address.isNullOrBlank() && PrivacyKeys.coversAddress(marks, address)

    fun callPrivate(c: CallLogItem, marks: Set<String>) = PrivacyKeys.coversCall(marks, c.id, c.number)

    fun appliesChannel(chips: Set<SearchChip>, isCall: Boolean): Boolean {
        val channel = setOf(SearchChip.CALLS, SearchChip.TEXTS)
        val selected = chips.intersect(channel)
        if (selected.isEmpty()) return true
        return if (isCall) SearchChip.CALLS in selected else SearchChip.TEXTS in selected
    }

    fun appliesAttrs(chips: Set<SearchChip>, scam: Boolean, subscription: Boolean, quarantined: Boolean, privateItem: Boolean): Boolean {
        if (SearchChip.SCAM in chips && !scam) return false
        if (SearchChip.SUBSCRIPTION in chips && !subscription) return false
        if (SearchChip.QUARANTINED in chips && !quarantined) return false
        if (SearchChip.PRIVATE in chips && !privateItem) return false
        return true
    }

    fun isScam(e: ScreeningEventEntity) = HomeStats.isScamFlag(e)

    fun merge(
        query: String,
        chips: Set<SearchChip>,
        marks: Set<String>,
        events: List<ScreeningEventEntity>,
        receipts: List<ReceiptEntity>,
        subscriptions: List<SubscriptionGroup>,
        quarantined: List<QuarantinedSmsEntity>,
        inbox: List<InboxSms> = emptyList(),
        calls: List<CallLogItem> = emptyList(),
        methodTitle: (String) -> String = { it },
        subscriptionIds: Set<String> = emptySet(),
    ): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        val seenEvents = mutableSetOf<Long>()

        events.forEach { e ->
            val isCall = e.channel == "CALL"
            if (!appliesChannel(chips, isCall)) return@forEach
            val priv = eventPrivate(e, marks)
            val sub = (e.number?.let { it in subscriptionIds || digits(it) in subscriptionIds } == true) ||
                (e.displayName?.let { it in subscriptionIds } == true)
            if (!appliesAttrs(chips, isScam(e), sub, e.quarantined, priv)) return@forEach
            if (!matchesText(query, e.displayName, e.number, e.body, e.category, e.risk, e.reasons, e.advice, e.lookupSummary, e.source, e.finalAction)) return@forEach
            seenEvents += e.id
            hits += SearchHit(
                kind = if (isCall) SearchKind.CALL else SearchKind.TEXT,
                id = e.id,
                title = e.displayName ?: e.number ?: "Hidden number",
                snippet = e.body?.replace('\n', ' ') ?: (e.lookupSummary ?: e.risk.lowercase()),
                time = e.receivedAt,
                eventId = e.id,
                address = e.number,
                private = priv,
            )
        }

        if (SearchChip.CALLS !in chips || SearchChip.TEXTS in chips || chips.intersect(setOf(SearchChip.CALLS, SearchChip.TEXTS)).isEmpty()) {
            // inbox texts not already represented as events
        }
        inbox.forEach { m ->
            if (!appliesChannel(chips, isCall = false)) return@forEach
            if (HomeStats.textAlreadyAnalyzed(m, events) && events.any { it.body == m.body && digits(it.number) == digits(m.address) }) return@forEach
            val priv = addressPrivate(m.address, marks)
            val sub = m.address in subscriptionIds || digits(m.address) in subscriptionIds
            if (!appliesAttrs(chips, false, sub, false, priv)) return@forEach
            if (!matchesText(query, m.address, m.body)) return@forEach
            hits += SearchHit(SearchKind.TEXT, m.id, m.address, m.body.replace('\n', ' '), m.date, address = m.address, private = priv)
        }
        calls.forEach { c ->
            if (!appliesChannel(chips, isCall = true)) return@forEach
            if (HomeStats.callAlreadyAnalyzed(c, events)) return@forEach
            val priv = callPrivate(c, marks)
            if (!appliesAttrs(chips, false, false, false, priv)) return@forEach
            if (!matchesText(query, c.cachedName, c.number, c.type.name)) return@forEach
            hits += SearchHit(
                SearchKind.CALL, c.id, c.cachedName ?: c.number ?: "Hidden number",
                listOfNotNull(c.number, c.type.name.lowercase()).joinToString(" · "),
                c.date, address = c.number, private = priv,
            )
        }

        if (chips.intersect(setOf(SearchChip.CALLS, SearchChip.TEXTS)).isEmpty() || SearchChip.SUBSCRIPTION in chips || chips.none { it == SearchChip.CALLS || it == SearchChip.TEXTS }) {
            subscriptions.forEach { g ->
                if (SearchChip.CALLS in chips && SearchChip.TEXTS !in chips && SearchChip.SUBSCRIPTION !in chips && chips.isNotEmpty() && chips == setOf(SearchChip.CALLS)) return@forEach
                if (!appliesChannel(chips, isCall = false) && SearchChip.SUBSCRIPTION !in chips && SearchChip.CALLS in chips) return@forEach
                val priv = g.senders.any { addressPrivate(it, marks) } || addressPrivate(g.service, marks)
                if (!appliesAttrs(chips, false, true, false, priv)) return@forEach
                if (!matchesText(query, g.service, g.brand, g.sender, *g.senders.toTypedArray(), g.lastBody)) return@forEach
                hits += SearchHit(SearchKind.SUBSCRIPTION, g.messageIds.firstOrNull() ?: g.serviceKey.hashCode().toLong(), g.service.ifBlank { g.sender }, "${g.count} texts", g.lastDate, address = g.sender, private = priv)
            }
        }

        quarantined.forEach { row ->
            if (!appliesChannel(chips, isCall = false) && SearchChip.QUARANTINED !in chips && SearchChip.CALLS in chips) return@forEach
            val priv = addressPrivate(row.address, marks)
            if (!appliesAttrs(chips, true, false, true, priv)) return@forEach
            if (!matchesText(query, row.address, row.body)) return@forEach
            hits += SearchHit(SearchKind.QUARANTINED, row.id, row.address, row.body.replace('\n', ' '), row.hiddenAt, eventId = row.eventId, address = row.address, private = priv)
        }

        val channelOnly = chips.intersect(setOf(SearchChip.CALLS, SearchChip.TEXTS))
        val allowReceipts = SearchChip.SUBSCRIPTION in chips || (channelOnly.isEmpty() && SearchChip.QUARANTINED !in chips) || chips.isEmpty()
        if (allowReceipts && SearchChip.CALLS !in chips || allowReceipts && channelOnly.isEmpty()) {
            receipts.forEach { r ->
                val title = methodTitle(r.methodId)
                val priv = r.eventId?.let { id -> events.find { it.id == id }?.let { eventPrivate(it, marks) } } == true
                val sub = r.methodId.contains("unsubscribe") || r.methodId.contains("subscription") || r.methodId.contains("mute")
                if (!appliesAttrs(chips, false, sub, false, priv)) return@forEach
                if (SearchChip.TEXTS in chips && SearchChip.CALLS !in chips && !r.methodId.contains("sms") && !sub) return@forEach
                if (SearchChip.CALLS in chips && SearchChip.TEXTS !in chips && !r.methodId.contains("call")) return@forEach
                if (!matchesText(query, title, r.methodId, r.status, r.inputJson, r.outputJson, r.notes)) return@forEach
                hits += SearchHit(SearchKind.RECEIPT, r.id, title, r.status.lowercase(), r.createdAt, eventId = r.eventId, receiptId = r.id, private = priv)
            }
        }

        return hits.sortedByDescending { it.time }
    }
}
