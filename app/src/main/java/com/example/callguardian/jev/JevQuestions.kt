package com.example.callguardian.jev

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The TypeSafe SystemOne request Jev sees: state + the nine Noul scam-signal
 * questions. Kept in lockstep with functions/src/questions.ts (QUESTIONS_VERSION).
 * The truth table that turns answers into a verdict lives in [JevTruthTable].
 */
object JevQuestions {
    const val QUESTIONS_VERSION = "2026.11.2"
    const val MODEL = "jev-latest"

    val SIGNAL_IDS: List<String> = JevTruthTable.Signal.entries.map { it.id }

    private val senderLabel = mapOf(
        "saved_contact" to "a saved contact",
        "unknown_number" to "a number that is not in the reader's contacts",
        "short_code" to "a short code (an automated business sender)",
        "hidden" to "a hidden sender",
    )

    fun buildRequest(redactedText: String, sender: String, fromOwnedAccount: Boolean = false, officialDomain: Boolean = false): JsonObject = buildJsonObject {
        put("model", MODEL)
        putJsonObject("state") {
            put("message", redactedText)
            put("sender", senderLabel[sender] ?: senderLabel.getValue("unknown_number"))
            put("note", "Personal details in `message` were masked as [name], [number], [digits], [email] and [path] before it was sent.")
            put("fromOwnedAccount", if (fromOwnedAccount) "yes — sender and official domain match an account the user holds" else "no")
            put("officialDomain", if (officialDomain) "yes — link domain is official for the claimed brand" else "no or no link")
        }
        putJsonObject("questions") {
            noul("asks_payment", "Does `message` ask the reader to pay money?",
                "The reader is told to pay, settle a balance, or send money",
                "The message only mentions an amount, or asks for nothing")
            noul("deadline_pressure", "Does `message` give the reader a short deadline with a penalty for missing it?",
                "Act within hours or today, or an account, package or fee is lost",
                "A date is mentioned for information only, with nothing lost by waiting")
            noul("claims_organization", "Does `message` say it comes from a named company, bank, or government agency?")
            noul("asks_open_link", "Does `message` ask the reader to open a link?")
            noul("asks_share_code", "Does `message` ask the reader to send back a verification code?",
                "The reader is asked to reply with or read out a code",
                "The message gives a code and says not to share it, or mentions no code")
            noul("asks_login_details", "Does `message` ask the reader to enter or send a password or login details?")
            noul("offers_money", "Does `message` offer the reader an unexpected way to make or win money?",
                "A prize, a high-paying job, or an investment tip the reader did not ask for",
                "A normal receipt, refund, or account notice")
            noul("moves_to_other_app", "Does `message` ask the reader to continue the conversation on another app?",
                "The reader is asked to move to WhatsApp, Telegram, Signal or a similar app",
                "The reader is asked to reply by text, or nothing is asked")
            noul("stranger_opener", "Is the sender of `message` starting a chat with someone they don't seem to know?",
                "A 'wrong number' or 'is this still your number?' opener from a stranger",
                "A message that refers to an existing order, appointment or account")
            noul("reputable_official",
                "Is the sender a reputable, well-known organization (e.g. Google, Apple, a major bank, a government toll agency) AND does the message match how that organization normally communicates (official domain, short code, no unusual ask)?",
                "A well-known organization, and the message looks like a normal notice from them",
                "An unknown sender, a look-alike domain, or an unusual ask (share a code, log in, pay urgently)")
        }
    }

    private fun JsonObjectBuilder.noul(id: String, instructions: String, trueCrit: String? = null, falseCrit: String? = null) {
        putJsonObject(id) {
            put("type", "noul")
            put("instructions", instructions)
            if (trueCrit != null && falseCrit != null) {
                putJsonObject("criteria") {
                    put("true", trueCrit)
                    put("false", falseCrit)
                }
            }
        }
    }
}
