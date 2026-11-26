package com.example.callguardian.notify

/** One MessagingStyle message from a notification's EXTRA_MESSAGES. */
data class StyledMessage(val sender: String?, val text: String, val time: Long)

/** What a messaging notification carries, already pulled out of the Bundle (pure, JVM-testable). */
data class NotificationFields(
    val title: String?,
    val text: String?,
    val bigText: String?,
    val conversationTitle: String?,
    val isGroupConversation: Boolean,
    val messages: List<StyledMessage>,
    val isGroupSummary: Boolean,
    val selfName: String? = null,
)

data class ParsedMessage(val sender: String, val conversation: String?, val text: String, val time: Long)

/**
 * Turns a Google Voice / WhatsApp notification into the new messages it announces. Group chats:
 * the conversation title (the group) is kept apart from the person who wrote each message.
 * Summary notifications ("3 new messages") are dropped, using the patterns from integrations.json.
 */
object NotificationParser {
    private val waGroupTitle = Regex("""^(.+?)\s*\(\d+ messages?\)\s*$""")

    fun parse(f: NotificationFields, ignore: (String) -> Boolean, since: Long = 0): List<ParsedMessage> {
        if (f.isGroupSummary) return emptyList()
        if (f.messages.isNotEmpty()) {
            val conv = (f.conversationTitle ?: f.title)?.let { t -> waGroupTitle.find(t)?.groupValues?.get(1) ?: t }?.trim()
            return f.messages.filter { it.time > since }
                .mapNotNull { m ->
                    val who = m.sender?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null // null sender = the user
                    if (f.selfName != null && who == f.selfName) return@mapNotNull null
                    val text = m.text.trim()
                    if (text.isEmpty() || ignore(text)) null
                    else ParsedMessage(who, conv.takeIf { f.isGroupConversation || (it != null && it != who) }, text, m.time)
                }
        }
        val text = (f.bigText ?: f.text)?.trim().orEmpty()
        val title = f.title?.trim().orEmpty()
        if (text.isEmpty() || title.isEmpty() || ignore(text) || ignore(title)) return emptyList()
        // WhatsApp without MessagingStyle: "Group: Sender" title or "Sender: text" body in groups.
        val colon = title.indexOf(": ")
        return if (colon > 0) listOf(ParsedMessage(title.substring(colon + 2), title.substring(0, colon), text, 0))
        else listOf(ParsedMessage(title, null, text, 0))
    }
}
