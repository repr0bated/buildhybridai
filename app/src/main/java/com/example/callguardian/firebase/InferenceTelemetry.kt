package com.example.callguardian.firebase

import com.example.callguardian.model.ScamCategory
import com.example.callguardian.model.VerdictSource
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import com.google.firebase.perf.metrics.Trace
import com.google.firebase.perf.performance
import timber.log.Timber

/**
 * Performance traces and Analytics events for triage. Event parameters never include
 * message text, phone numbers, or any other personal content — only a closed set of
 * source / category / fallback tokens.
 */
object InferenceTelemetry {
    const val SOURCE_NANO = "nano"
    const val SOURCE_CLOUD = "cloud"
    const val SOURCE_LOCAL = "local"

    const val FALLBACK_NONE = "none"
    const val FALLBACK_ON_DEVICE_FAILED = "on_device_failed"
    const val FALLBACK_BACKGROUND = "background"
    const val FALLBACK_RATE_LIMITED = "rate_limited"
    const val FALLBACK_ERROR9 = "error9"
    const val FALLBACK_OWN_KEY = "own_key"
    const val FALLBACK_NANO_UNAVAILABLE = "nano_unavailable"

    const val EVENT_VERDICT = "verdict"
    const val PARAM_SOURCE = "source"
    const val PARAM_CATEGORY = "category"
    const val PARAM_FALLBACK = "fallback_reason"

    const val TRACE_NANO = "inference_nano"
    const val TRACE_CLOUD = "inference_cloud"

    private val SOURCES = setOf(SOURCE_NANO, SOURCE_CLOUD, SOURCE_LOCAL)
    private val FALLBACKS = setOf(
        FALLBACK_NONE, FALLBACK_ON_DEVICE_FAILED, FALLBACK_BACKGROUND,
        FALLBACK_RATE_LIMITED, FALLBACK_ERROR9, FALLBACK_OWN_KEY, FALLBACK_NANO_UNAVAILABLE,
    )
    private val PHONE = Regex("""^\+?\d[\d\s().-]{6,}$""")

    fun sourceOf(source: VerdictSource): String = when (source) {
        VerdictSource.ON_DEVICE -> SOURCE_NANO
        VerdictSource.IN_CLOUD -> SOURCE_CLOUD
        VerdictSource.RULES, VerdictSource.USER -> SOURCE_LOCAL
    }

    fun fallbackOf(token: String?): String {
        val t = token?.trim()?.lowercase().orEmpty()
        return if (t in FALLBACKS) t else FALLBACK_NONE
    }

    /**
     * Allow only short, closed-set tokens. Rejects anything that looks like a phone
     * number or a message body so it can never be sent as an Analytics parameter.
     */
    fun safeParam(value: String?, allowed: Set<String>? = null): String? {
        val v = value?.trim() ?: return null
        if (v.isEmpty() || v.length > 80) return null
        if ('\n' in v || '\r' in v || ' ' in v || '\t' in v) return null
        if (PHONE.matches(v)) return null
        if (v.any { it.isLetter() } && v.count { it.isDigit() } >= 7) return null
        if (allowed != null && v !in allowed) return null
        return v
    }

    fun startTrace(kind: String): Trace? {
        val name = when (kind) {
            SOURCE_NANO -> TRACE_NANO
            SOURCE_CLOUD -> TRACE_CLOUD
            else -> return null
        }
        return runCatching {
            val trace = Firebase.performance.newTrace(name)
            trace.start()
            trace
        }.onFailure { Timber.w("Performance trace %s could not start", name) }.getOrNull()
    }

    fun stopTrace(trace: Trace?, source: String) {
        if (trace == null) return
        runCatching {
            safeParam(source, SOURCES)?.let { trace.putAttribute(PARAM_SOURCE, it) }
            trace.stop()
        }.onFailure { Timber.w("Performance trace could not stop") }
    }

    fun logVerdict(source: String, category: String, fallbackReason: String = FALLBACK_NONE) {
        val src = safeParam(source, SOURCES) ?: return
        val cat = safeParam(category, CATEGORIES) ?: safeParam(category) ?: return
        if (cat !in CATEGORIES) return
        val fb = safeParam(fallbackReason, FALLBACKS) ?: FALLBACK_NONE
        runCatching {
            Firebase.analytics.logEvent(EVENT_VERDICT) {
                param(PARAM_SOURCE, src)
                param(PARAM_CATEGORY, cat)
                param(PARAM_FALLBACK, fb)
            }
        }.onFailure { Timber.w("Analytics verdict event dropped") }
    }

    fun logVerdict(source: VerdictSource, category: ScamCategory, fallbackReason: String? = null) {
        logVerdict(sourceOf(source), category.name, fallbackOf(fallbackReason))
    }

    val CATEGORIES: Set<String> = ScamCategory.entries.map { it.name }.toSet()
}
