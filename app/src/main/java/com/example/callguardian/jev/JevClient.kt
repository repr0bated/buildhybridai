package com.example.callguardian.jev

import com.example.callguardian.BuildConfig
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class JevAnswers(
    val signals: Map<String, Double>,
    val model: String,
    val questionsVersion: String,
    val inputTokens: Int,
    val latencyMs: Long,
    val route: JevRoute = JevRoute.WORKER,
)

/**
 * Talks to Jev when the user has turned the integration on (off by default).
 *
 * Default path is the Cloudflare Worker at [JevEndpoints.DEFAULT_WORKER_URL]:
 * `POST /v1/check` or `POST /v1/check:batch`, with `X-Firebase-AppCheck`.
 * A TypeSafe API key in Settings, if the user has saved one, is an optional
 * direct path; the key is never logged. Unreachable endpoints fail softly.
 */
@Singleton
class JevClient @Inject constructor(private val secrets: JevSecrets) {
    private val http = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun route(): JevRoute = JevEndpoints.resolve(secrets.isKeySet, BuildConfig.JEV_WORKER_URL)

    suspend fun check(redactedText: String, sender: String): JevAnswers = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        when (val r = route()) {
            JevRoute.DIRECT -> parseAnswers(postDirect(redactedText, sender), r, t0)
            JevRoute.WORKER -> parseAnswers(postWorker(redactedText, sender), r, t0)
            JevRoute.UNAVAILABLE -> error("Jev Worker URL is not set. Add a TypeSafe Jev API key in Settings, or set JEV_WORKER_URL.")
        }
    }

    /**
     * `POST /v1/check:batch` (max [JevEndpoints.BATCH_MAX] items). Used when Jev is
     * turned on; the default (Firebase AI Logic) pipeline does not call this.
     * Chunks larger queues. A TypeSafe failure on one item does not fail the batch.
     */
    suspend fun checkBatch(items: List<Pair<String, String>>): List<Result<JevAnswers>> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()
        if (route() != JevRoute.WORKER) return@withContext items.map { runCatching { check(it.first, it.second) } }
        val url = JevEndpoints.workerBatchUrl(BuildConfig.JEV_WORKER_URL)
        if (url.isEmpty()) return@withContext items.map { runCatching { check(it.first, it.second) } }
        items.chunked(JevEndpoints.BATCH_MAX).flatMap { chunk -> postBatchChunk(url, chunk) }
    }

    private suspend fun postBatchChunk(url: String, items: List<Pair<String, String>>): List<Result<JevAnswers>> {
        val t0 = System.currentTimeMillis()
        val body = buildJsonObject {
            put("items", JsonArray(items.map { (text, sender) ->
                buildJsonObject { put("redactedText", text); put("sender", sender) }
            }))
        }.toString()
        return runCatching { post(url, body, appCheck = true) }.fold(
            onSuccess = { text ->
                val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                val results = root?.get("results") as? JsonArray
                if (results == null || results.size != items.size) {
                    Timber.w("Jev Worker batch returned an unexpected payload")
                    items.map { Result.failure(IllegalStateException("Jev Worker batch returned an unexpected payload")) }
                } else results.map { el ->
                    runCatching { parseAnswers(el.jsonObject.toString(), JevRoute.WORKER, t0) }
                }
            },
            onFailure = { err ->
                Timber.w("Jev Worker batch is unreachable; not retrying as a hard failure")
                items.map { Result.failure(err) }
            },
        )
    }

    private fun postDirect(redactedText: String, sender: String): String {
        val key = secrets.apiKey()
        if (key.isBlank()) error("No TypeSafe Jev API key is saved")
        val body = JevQuestions.buildRequest(redactedText, sender).toString()
        val req = Request.Builder().url(JevEndpoints.TYPESAFE_URL)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        return execute(req, "TypeSafe")
    }

    private suspend fun postWorker(redactedText: String, sender: String): String {
        val url = JevEndpoints.workerCheckUrl(BuildConfig.JEV_WORKER_URL)
        if (url.isEmpty()) error("Jev Worker URL is not set yet")
        val body = buildJsonObject { put("redactedText", redactedText); put("sender", sender) }.toString()
        return post(url, body, appCheck = true)
    }

    private suspend fun post(url: String, body: String, appCheck: Boolean): String {
        val b = Request.Builder().url(url).post(body.toRequestBody(JSON))
        if (appCheck) b.header("X-Firebase-AppCheck", appCheckToken())
        return execute(b.build(), "Jev Worker")
    }

    private fun execute(req: Request, label: String): String {
        return try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) error(httpError(label, r.code, text))
                text
            }
        } catch (t: Throwable) {
            if (t is IllegalStateException && t.message?.startsWith(label) == true) throw t
            throw IllegalStateException("$label is unreachable: ${t.message?.take(160) ?: t.javaClass.simpleName}", t)
        }
    }

    private fun httpError(label: String, code: Int, body: String): String {
        // Never include the request or Authorization material in the message.
        val hint = body.take(180).replace(Regex("(?i)(bearer\\s+)\\S+"), "$1[redacted]")
        return when (code) {
            404 -> "$label isn't deployed yet (HTTP 404)"
            401 -> "$label unauthorized (App Check token rejected)"
            403 -> "$label forbidden (this app is not on the Worker's allow list)"
            429 -> "$label rate-limited (HTTP 429)"
            else -> "$label HTTP $code: $hint"
        }
    }

    private fun parseAnswers(text: String, route: JevRoute, t0: Long): JevAnswers {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            error("Jev returned a response that was not JSON")
        }
        // Worker: { "error": "<code>", "message": "…" } or callable { "error": { "status", "message" } }.
        root["error"]?.let { err ->
            val msg = when (err) {
                is JsonObject -> "${err["status"]?.jsonPrimitive?.content ?: "error"}: ${err["message"]?.jsonPrimitive?.content}"
                is JsonPrimitive -> "${err.content}${root["message"]?.jsonPrimitive?.content?.let { ": $it" } ?: ""}"
                else -> err.toString()
            }
            error("Jev $msg")
        }
        // Worker or callable wrappers may nest under "result".
        val res: JsonObject = root["result"]?.jsonObject ?: root
        if (res["ok"]?.jsonPrimitive?.content == "false") {
            error("Jev ${res["error"]?.jsonPrimitive?.content ?: "item failed"}")
        }
        val signalsObj = res["signals"]?.jsonObject
            ?: res["answers"]?.jsonObject?.let { answers ->
                buildJsonObject {
                    JevQuestions.SIGNAL_IDS.forEach { id ->
                        val n = answers[id]?.jsonObject?.get("noul")?.jsonPrimitive?.double
                        if (n != null) put(id, n)
                    }
                }
            }
            ?: error("Jev returned no signals")
        val signals = JevQuestions.SIGNAL_IDS.associateWith { id ->
            signalsObj[id]?.jsonPrimitive?.double ?: error("Jev returned no Noul answer for $id")
        }
        return JevAnswers(
            signals = signals,
            model = res["model"]?.jsonPrimitive?.content ?: JevQuestions.MODEL,
            questionsVersion = res["questionsVersion"]?.jsonPrimitive?.content ?: JevQuestions.QUESTIONS_VERSION,
            inputTokens = res["inputTokens"]?.jsonPrimitive?.int
                ?: res["usage"]?.jsonObject?.get("input_tokens")?.jsonPrimitive?.int
                ?: 0,
            latencyMs = System.currentTimeMillis() - t0,
            route = route,
        )
    }

    private suspend fun appCheckToken(): String = suspendCancellableCoroutine { c ->
        FirebaseAppCheck.getInstance().getAppCheckToken(false)
            .addOnSuccessListener { c.resume(it.token) }
            .addOnFailureListener {
                c.resumeWithException(IllegalStateException("App Check token unavailable: ${it.message}", it))
            }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
