package com.example.callguardian.screening

/**
 * Login / verification / OTP texts.
 *
 * A message that only contains a code, with no link and no request to call, reply,
 * or share the code, is CLEAR (Code) — never a scam. Especially from a short code.
 * "Never share this code" is a standard warning, not a request to hand the code over.
 * Flag only when the text asks the user to share the code or includes a link.
 */
object VerificationCode {

    data class Signals(
        val isCodeMessage: Boolean,
        val hasLink: Boolean,
        val asksToShare: Boolean,
        val asksToCallOrReply: Boolean,
        /** Pure login/OTP text: a code, no link, no request to share, call, or reply. */
        val clearCode: Boolean,
    )

    /** 4–8 digits, allowing the common "123-456" / "123 456" OTP formatting. */
    private val fourToEightDigits = Regex("""(?<!\d)(?:\d[\- ]?){3,7}\d(?!\d)""")

    private val codeLanguage = Regex(
        """(?i)((verification|login|security|authentication|auth|one[ -]?time|access)\s+(code|password|pin|passcode)|\botp\b|\bpasscode\b|\bpin code\b|\bcode is\b|\bcode:)""",
    )

    private val codeNearDigits = Regex(
        """(?i)(\b(code|otp|passcode|pin)\b[:\s#-]{0,8}(?:\d[\- ]?){3,7}\d)|((?:\d[\- ]?){3,7}\d\s+is your\s+(code|otp|pin|passcode))|(use\s+(?:\d[\- ]?){3,7}\d\s+to\s+(log|sign)\s?in)""",
    )

    /** Applied after negation verbs ("never share", "don't send") are masked. */
    private val shareAfterMask = Regex(
        """(?i)(\b(send|share|forward|reply with|text (us |back )?|tell (me|us)|give (us|me)|read (us |me )|call (us )?with)\b.{0,40}\b(code|otp|pin|password|passcode)\b)|(\b(code|otp|pin|passcode)\b.{0,40}\b((send|share|forward) (it|this|the code)|reply with (it|this|the code)|tell (me|us)))""",
    )

    private val negationVerb = Regex(
        """(?i)\b(never|do not|don't|dont|not)\s+(share|send|forward|give|tell|read)\b""",
    )

    private val callOrReply = Regex(
        """(?i)\b((please )?call (us |this |the )?(number|now|immediately|at|back)\b|call \+?\d|tap to call|reply (yes|now|to confirm|with)|text (us )?back|press \d)\b""",
    )

    private val stopOnly = Regex("""(?i)\breply stop\b|\btext stop\b|\bmsg&data rates\b""")

    fun of(body: String?): Signals {
        val text = body.orEmpty()
        if (text.isBlank()) return Signals(false, false, false, false, false)
        val hasLink = OfficialDomains.hostsIn(text).isNotEmpty()
        val isCode = looksLikeCode(text)
        val asksShare = isCode && asksToShare(text)
        val asksCall = isCode && asksToCallOrReply(text)
        return Signals(
            isCodeMessage = isCode,
            hasLink = hasLink,
            asksToShare = asksShare,
            asksToCallOrReply = asksCall,
            clearCode = isCode && !hasLink && !asksShare && !asksCall,
        )
    }

    fun looksLikeCode(body: String): Boolean {
        if (!fourToEightDigits.containsMatchIn(body)) return false
        return codeLanguage.containsMatchIn(body) || codeNearDigits.containsMatchIn(body)
    }

    fun asksToShare(body: String): Boolean {
        val masked = negationVerb.replace(body, "SAFEVERB")
        return shareAfterMask.containsMatchIn(masked)
    }

    fun asksToCallOrReply(body: String): Boolean {
        val withoutStop = stopOnly.replace(body, " ")
        return callOrReply.containsMatchIn(withoutStop)
    }
}
