package com.example.callguardian.privacy

/** Keys stored in Room for private events, threads, contacts and call-log rows. Pure Kotlin. */
object PrivacyKeys {
    const val EVENT = "EVENT"
    const val THREAD = "THREAD"
    const val CONTACT = "CONTACT"
    const val CALL = "CALL"

    fun normalize(address: String): String {
        val d = address.filter { it.isDigit() }
        return if (d.length >= 3) d else address.trim().lowercase()
    }

    fun event(id: Long) = "event:$id"
    fun thread(address: String) = "thread:${normalize(address)}"
    fun contact(address: String) = "contact:${normalize(address)}"
    fun call(id: Long) = "call:$id"

    fun key(kind: String, eventId: Long?, address: String?, callLogId: Long?): String = when (kind) {
        EVENT -> event(eventId ?: error("eventId required"))
        THREAD -> thread(address ?: error("address required"))
        CONTACT -> contact(address ?: error("address required"))
        CALL -> call(callLogId ?: eventId ?: error("callLogId required"))
        else -> error("unknown privacy kind $kind")
    }

    fun coversEvent(marks: Set<String>, eventId: Long, address: String?, channelIsCall: Boolean): Boolean {
        if (event(eventId) in marks) return true
        if (address.isNullOrBlank()) return false
        val n = normalize(address)
        if (contact(n) in marks) return true
        if (!channelIsCall && thread(n) in marks) return true
        return false
    }

    fun coversAddress(marks: Set<String>, address: String, includeCalls: Boolean = false): Boolean {
        val n = normalize(address)
        return contact(n) in marks || thread(n) in marks || (includeCalls && marks.any { it == contact(n) })
    }

    fun coversCall(marks: Set<String>, callLogId: Long?, address: String?): Boolean {
        if (callLogId != null && call(callLogId) in marks) return true
        if (address.isNullOrBlank()) return false
        return contact(normalize(address)) in marks
    }
}
