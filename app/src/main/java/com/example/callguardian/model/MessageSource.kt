package com.example.callguardian.model

/**
 * Where screened content came from. Wire values match the `source` enum in
 * assets/schemas/message_source.schema.json (RegistryTest checks they agree).
 */
enum class MessageSource(val wire: String, val label: String) {
    SMS("sms", "SMS"),
    GOOGLE_VOICE("google_voice", "Google Voice"),
    WHATSAPP("whatsapp", "WhatsApp"),
    CALL_SCREENING("call_screening", "Call screening"),
    CALL_LOG("call_log", "Call log"),
    LAB_SAMPLE("lab_sample", "Lab sample");

    /** Read from another app's notifications (no inbox access; new messages only; we never send there). */
    val fromNotifications: Boolean get() = this == GOOGLE_VOICE || this == WHATSAPP

    companion object { fun fromWire(w: String?) = entries.firstOrNull { it.wire == w } ?: SMS }
}
