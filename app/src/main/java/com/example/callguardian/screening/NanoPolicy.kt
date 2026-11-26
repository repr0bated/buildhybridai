package com.example.callguardian.screening

/**
 * Decision helpers for Gemini Nano (AICore) vs cloud Firebase AI Logic.
 * AICore ErrorCode 9 is raised when Nano is invoked from a background process;
 * every on-device call must be gated on [AppForeground.isResumed].
 */
object NanoPolicy {
    const val WAITING_FOR_APP = "Waiting for app to be open"

    enum class Route { NANO, CLOUD, QUEUE }

    /**
     * @param resumed [ProcessLifecycleOwner] is at least [androidx.lifecycle.Lifecycle.State.RESUMED]
     * @param privateMode message content must stay on the phone
     */
    fun route(resumed: Boolean, privateMode: Boolean): Route = when {
        resumed -> Route.NANO
        privateMode -> Route.QUEUE
        else -> Route.CLOUD
    }

    fun canCallNano(resumed: Boolean): Boolean = resumed

    fun shouldPauseDrain(resumed: Boolean): Boolean = !resumed

    fun nextBackoffMs(current: Long, failed: Boolean, min: Long = MIN_BACKOFF_MS, max: Long = MAX_BACKOFF_MS): Long =
        if (failed) (current * 2).coerceIn(min, max) else min

    fun isError9(t: Throwable): Boolean {
        val m = (t.message ?: "") + " " + (t.cause?.message ?: "")
        return t is NanoRequiresForegroundException ||
            Regex("""ErrorCode\s*9\b""").containsMatchIn(m) ||
            m.contains("disallowed background", true) ||
            (m.contains("usage quota", true) && m.contains("model set", true)) ||
            (m.contains("usage quota", true) && m.contains("background", true))
    }

    fun isCloudRateLimited(t: Throwable): Boolean {
        if (isError9(t)) return false
        val m = (t.message ?: "") + " " + (t.cause?.message ?: "")
        return m.contains("429") ||
            m.contains("RESOURCE_EXHAUSTED", true) ||
            m.contains("Too Many Requests", true) ||
            m.contains("rate limit", true) ||
            (m.contains("quota", true) && !m.contains("disallowed background", true))
    }

    /** Quiet status for ErrorCode 9 / background Nano. Never an error dialog. */
    fun quietStatus(t: Throwable?): String = when {
        t == null -> WAITING_FOR_APP
        isError9(t) -> WAITING_FOR_APP
        isCloudRateLimited(t) -> WAITING_FOR_APP
        else -> WAITING_FOR_APP
    }

    const val MIN_BACKOFF_MS = 400L
    const val MAX_BACKOFF_MS = 8_000L
}

/** Thrown instead of calling AICore when the process is not RESUMED. */
class NanoRequiresForegroundException :
    IllegalStateException(NanoPolicy.WAITING_FOR_APP)
