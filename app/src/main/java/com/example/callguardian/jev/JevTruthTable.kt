package com.example.callguardian.jev

/**
 * Turns TypeSafe Jev's answers into a verdict. Jev makes the judgments (one Noul per scam signal,
 * worded in functions/src/questions.ts); this table makes the decision, in code, where it can be
 * read and tested (JevTruthTableTest).
 *
 * Each Noul is read as YES (p ≥ 0.7), NO (p ≤ 0.3) or UNSETTLED (in between). The table is
 * evaluated twice: once reading every unsettled signal as yes, once as no. If both readings give
 * the same row, that is the verdict. If they differ, the outcome hinges on an unsettled answer and
 * the verdict is UNSETTLED. So every combination of answers has a defined outcome.
 */
object JevTruthTable {
    const val YES = 0.7
    const val NO = 0.3

    enum class Signal(val id: String) {
        ASKS_PAYMENT("asks_payment"),
        DEADLINE_PRESSURE("deadline_pressure"),
        CLAIMS_ORGANIZATION("claims_organization"),
        ASKS_OPEN_LINK("asks_open_link"),
        ASKS_SHARE_CODE("asks_share_code"),
        ASKS_LOGIN_DETAILS("asks_login_details"),
        OFFERS_MONEY("offers_money"),
        MOVES_TO_OTHER_APP("moves_to_other_app"),
        STRANGER_OPENER("stranger_opener");
        companion object { fun byId(id: String) = entries.firstOrNull { it.id == id } }
    }

    enum class Verdict { SCAM, SUSPICIOUS, UNSETTLED, CLEAR }
    enum class Reading { YES, NO, UNSETTLED }

    data class Result(val verdict: Verdict, val rule: String, val unsettled: List<Signal>)

    fun read(p: Double): Reading = when { p >= YES -> Reading.YES; p <= NO -> Reading.NO; else -> Reading.UNSETTLED }

    private data class Row(val verdict: Verdict, val rule: String, val test: (Set<Signal>, Boolean) -> Boolean)

    private val S = Signal.entries
    /** First matching row wins. `y` = signals read as yes; `contact` = sender is a saved contact (known to code). */
    private val rows = listOf(
        Row(Verdict.SCAM, "code request + company claim or deadline") { y, _ -> Signal.ASKS_SHARE_CODE in y && (Signal.CLAIMS_ORGANIZATION in y || Signal.DEADLINE_PRESSURE in y) },
        Row(Verdict.SCAM, "asks for login details") { y, _ -> Signal.ASKS_LOGIN_DETAILS in y },
        Row(Verdict.SCAM, "payment or link + deadline") { y, _ -> (Signal.ASKS_PAYMENT in y || Signal.ASKS_OPEN_LINK in y) && Signal.DEADLINE_PRESSURE in y },
        Row(Verdict.SCAM, "money offer + other app or stranger opener") { y, _ -> Signal.OFFERS_MONEY in y && (Signal.MOVES_TO_OTHER_APP in y || Signal.STRANGER_OPENER in y) },
        Row(Verdict.SCAM, "stranger opener + other app") { y, _ -> Signal.STRANGER_OPENER in y && Signal.MOVES_TO_OTHER_APP in y },
        Row(Verdict.SUSPICIOUS, "one strong signal from a non-contact") { y, contact ->
            !contact && listOf(Signal.ASKS_SHARE_CODE, Signal.ASKS_PAYMENT, Signal.OFFERS_MONEY, Signal.MOVES_TO_OTHER_APP, Signal.STRANGER_OPENER).any { it in y } },
        Row(Verdict.SUSPICIOUS, "company claim + deadline") { y, _ -> Signal.CLAIMS_ORGANIZATION in y && Signal.DEADLINE_PRESSURE in y },
        Row(Verdict.CLEAR, "no scam combination") { _, _ -> true },
    )

    private fun firstRow(yes: Set<Signal>, contact: Boolean) = rows.first { it.test(yes, contact) }

    fun decide(probabilities: Map<Signal, Double>, senderIsContact: Boolean): Result {
        require(probabilities.keys.containsAll(S)) { "Missing Jev answers: ${S - probabilities.keys}" }
        val readings = probabilities.mapValues { read(it.value) }
        val yes = readings.filterValues { it == Reading.YES }.keys
        val unsettled = readings.filterValues { it == Reading.UNSETTLED }.keys
        val strict = firstRow(yes, senderIsContact)
        val loose = firstRow(yes + unsettled, senderIsContact)
        val order = S.filter { it in unsettled }
        return if (strict.verdict == loose.verdict) Result(strict.verdict, strict.rule, order)
        else Result(Verdict.UNSETTLED, "hinges on unsettled: ${order.joinToString { it.id }}".take(80), order)
    }
}
