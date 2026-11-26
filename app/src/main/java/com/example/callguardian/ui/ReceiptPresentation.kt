package com.example.callguardian.ui

import com.example.callguardian.data.db.ReceiptEntity
import com.example.callguardian.data.db.ScreeningEventEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class ReceiptTone { ALLOW, BLOCK, WARN, NEUTRAL, BAD }

data class ReceiptView(
    /** Primary line: who was acted on, plus a message snippet or call description. */
    val subject: String,
    /** Human method title, shown small under the subject. Method ids belong in the detail view. */
    val methodTitle: String,
    val outcome: String,
    val outcomeTone: ReceiptTone,
    val status: String,
    val statusTone: ReceiptTone,
)

/** Pure presentation for receipt list rows. Unit-tested. */
object ReceiptPresentation {
    fun present(r: ReceiptEntity, methodTitle: String?, event: ScreeningEventEntity?, input: JsonObject?, output: JsonObject?): ReceiptView {
        val title = methodTitle?.takeIf { it.isNotBlank() }
            ?: r.methodId.substringAfter('.').substringBefore('@').replace('_', ' ')
        return ReceiptView(
            subject = subject(r, event, input, output),
            methodTitle = title,
            outcome = outcome(r, input, output),
            outcomeTone = outcomeTone(r, input, output),
            status = statusLabel(r.status),
            statusTone = statusTone(r.status),
        )
    }

    fun statusLabel(status: String): String = when {
        status == "OK" -> "OK"
        status == "DENIED" -> "Denied"
        status == "FAILED" -> "Failed"
        status.startsWith("INVALID") -> "Invalid"
        else -> status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }

    fun statusTone(status: String): ReceiptTone = when (status) {
        "OK" -> ReceiptTone.ALLOW
        "DENIED" -> ReceiptTone.WARN
        else -> ReceiptTone.BAD
    }

    fun outcome(r: ReceiptEntity, input: JsonObject?, output: JsonObject?): String {
        val id = r.methodId
        val alias = id.substringAfter('.').substringBefore('@')
        val mode = str(input, "mode")?.uppercase()
        val enabled = bool(output, "enabled") ?: bool(input, "enabled")
        val verdict = str(output, "verdict")
        return when {
            alias.contains("block") || id.contains(".block") -> "Blocked"
            alias.contains("allow") || id.contains(".allow") -> "Allowed"
            alias.contains("reject") -> "Stopped"
            alias.contains("quarantine") -> "Quarantined"
            alias.contains("unsubscribe") -> "Unsubscribed"
            alias.contains("manage_subscription") -> "Opened"
            alias == "mute" || alias.contains("mute") -> when (mode) {
                "KEEP" -> "Kept"
                "CLEAR" -> "Cleared"
                else -> "Muted"
            }
            alias.contains("set_private") -> "Hidden"
            alias.contains("clear_private") -> "Visible"
            alias.contains("review") -> "Reviewed"
            alias.contains("jev") -> verdict?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Checked"
            alias.contains("explain") -> "Explained"
            alias.contains("save") || alias.contains("contact") -> "Saved"
            alias.contains("report") -> "Reported"
            alias.contains("integration") || alias.contains("set_integration") -> if (enabled == false) "Disabled" else if (enabled == true) "Enabled" else "Updated"
            else -> methodWord(alias)
        }
    }

    fun outcomeTone(r: ReceiptEntity, input: JsonObject?, output: JsonObject?): ReceiptTone {
        val o = outcome(r, input, output)
        return when (o) {
            "Blocked", "Stopped", "Quarantined" -> ReceiptTone.BLOCK
            "Allowed", "Kept", "Reviewed", "Saved", "Visible", "Enabled", "Checked", "Cleared" -> ReceiptTone.ALLOW
            "Failed", "Invalid" -> ReceiptTone.BAD
            else -> ReceiptTone.NEUTRAL
        }
    }

    fun subject(r: ReceiptEntity, event: ScreeningEventEntity?, input: JsonObject?, output: JsonObject?): String {
        val who = firstNonBlank(
            event?.displayName,
            str(input, "cachedName"), str(output, "cachedName"),
            str(input, "displayName"), str(output, "displayName"),
            str(input, "service"), str(output, "service"),
            event?.number,
            str(input, "label"),
            str(input, "sender"), str(output, "sender"),
            str(input, "number"), str(output, "number"),
            str(input, "address"), str(output, "address"),
            firstListItem(input, "recipients"), firstListItem(output, "recipients"),
            str(input, "recipient"), str(output, "recipient"),
            str(input, "integration"), str(output, "integration"),
        )
        val snippet = firstNonBlank(
            event?.body?.replace('\n', ' ')?.trim()?.take(80),
            str(input, "body")?.replace('\n', ' ')?.take(80),
            callSnippet(input, event),
            event?.lookupSummary?.replace('\n', ' ')?.take(80),
            str(input, "reason")?.take(80),
        )
        return when {
            !who.isNullOrBlank() && !snippet.isNullOrBlank() && !snippet.contains(who) -> "$who · $snippet"
            !who.isNullOrBlank() -> who
            !snippet.isNullOrBlank() -> snippet
            else -> r.methodId.substringAfter("guardian.").substringBefore('@').replace('.', ' ')
        }
    }

    private fun callSnippet(input: JsonObject?, event: ScreeningEventEntity?): String? {
        if (event?.channel == "CALL" || input?.let { "callType" in it || "durationSec" in it } == true) {
            val type = str(input, "callType")?.lowercase()?.replace('_', ' ')
                ?: if (event?.channel == "CALL") "call" else null
            val dur = (input?.get("durationSec") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: event?.lookupSummary?.let { Regex("""(\d+) s""").find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }
            return listOfNotNull(type, dur?.let { "${it}s" }).joinToString(", ").ifBlank { "Call" }
        }
        return null
    }

    private fun methodWord(alias: String) = alias.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

    private fun str(obj: JsonObject?, key: String): String? {
        val v = obj?.get(key) ?: return null
        return when (v) {
            is JsonPrimitive -> v.contentOrNull?.trim()?.takeIf { it.isNotBlank() && it != "hidden" }
            else -> null
        }
    }

    private fun bool(obj: JsonObject?, key: String): Boolean? =
        (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private fun firstListItem(obj: JsonObject?, key: String): String? {
        val v = obj?.get(key) as? JsonArray ?: return null
        return (v.firstOrNull() as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }
}
