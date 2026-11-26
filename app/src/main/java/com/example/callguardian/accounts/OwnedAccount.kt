package com.example.callguardian.accounts

/**
 * A service Jeremy has an account with or is subscribed to (E-ZPass, a bank, Cash App, …).
 * Stored locally: service name, sender numbers / short codes, and official domains.
 */
data class OwnedAccount(
    val id: Long = 0,
    val service: String,
    val senders: List<String>,
    val domains: List<String>,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    fun matchesSender(number: String?): Boolean = number != null && senders.any { SenderIds.same(it, number) }
    fun matchesDomain(host: String): Boolean = com.example.callguardian.screening.OfficialDomains.isOfficialHost(host, domains.toSet())
}

object SenderIds {
    fun normalize(raw: String): String {
        val t = raw.trim()
        if (t.contains('@')) return t.lowercase()
        val digits = t.filter { it.isDigit() }
        return if (digits.length == 11 && digits.startsWith('1')) digits.drop(1) else digits.ifBlank { t.lowercase() }
    }

    fun same(a: String, b: String): Boolean {
        val x = normalize(a); val y = normalize(b)
        if (x == y) return true
        if (x.length >= 10 && y.length >= 10) return x.takeLast(10) == y.takeLast(10)
        return false
    }

    fun isShortCode(number: String?): Boolean {
        if (number.isNullOrBlank() || number.contains('@')) return false
        val d = normalize(number)
        return d.all { it.isDigit() } && d.length in 4..6
    }

    fun parseList(raw: String): List<String> =
        raw.split(Regex("[,;\n]")).map { it.trim() }.filter { it.isNotBlank() }.map { normalize(it) }.distinct()

    fun parseDomains(raw: String): List<String> =
        raw.split(Regex("[,;\n ]+")).map { it.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/') }
            .filter { it.contains('.') }.distinct()
}
