package com.example.callguardian.jev

/**
 * How the app reaches TypeSafe Jev when the Settings toggle is on (off by default).
 *
 * Default transport is the Cloudflare Worker
 * ([DEFAULT_WORKER_URL], contract in docs/JEV_WORKER.md). If the user has pasted
 * a TypeSafe API key in Settings, the app can call TypeSafe directly instead.
 */
enum class JevRoute { DIRECT, WORKER, UNAVAILABLE }

object JevEndpoints {
    const val TYPESAFE_URL = "https://api.typesafe.ai/v1/systemone"
    /** Public site for "Get a key". */
    const val TYPESAFE_GET_KEY_URL = "https://typesafe.ai"

    /** Live Worker. Overridable via the JEV_WORKER_URL gradle/env property. */
    const val DEFAULT_WORKER_URL = "https://callguardian-jev.reprobate.workers.dev"

    const val CHECK_PATH = "/v1/check"
    const val BATCH_PATH = "/v1/check:batch"
    const val CALLABLE_PATH = "/jevCheck"
    const val BATCH_MAX = 20

    fun normalizeBase(workerUrl: String): String {
        var b = workerUrl.trim().trimEnd('/')
        b = b.removeSuffix(CALLABLE_PATH)
            .removeSuffix(BATCH_PATH)
            .removeSuffix(CHECK_PATH)
            .removeSuffix("/check:batch")
            .removeSuffix("/check")
            .removeSuffix("/batch")
            .removeSuffix("/v1")
        return b.trimEnd('/')
    }

    fun workerCheckUrl(workerUrl: String): String {
        val b = normalizeBase(workerUrl)
        return if (b.isEmpty()) "" else "$b$CHECK_PATH"
    }

    fun workerBatchUrl(workerUrl: String): String {
        val b = normalizeBase(workerUrl)
        return if (b.isEmpty()) "" else "$b$BATCH_PATH"
    }

    fun workerCallableUrl(workerUrl: String): String {
        val b = normalizeBase(workerUrl)
        return if (b.isEmpty()) "" else "$b$CALLABLE_PATH"
    }

    fun resolve(hasKey: Boolean, workerUrl: String): JevRoute = when {
        hasKey -> JevRoute.DIRECT
        workerUrl.trim().isNotEmpty() -> JevRoute.WORKER
        else -> JevRoute.UNAVAILABLE
    }
}
