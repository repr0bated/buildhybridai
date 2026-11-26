package com.example.callguardian.jev

import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class JevAnswers(val signals: Map<String, Double>, val model: String, val questionsVersion: String, val inputTokens: Int, val latencyMs: Long)

/**
 * Calls the `jevCheck` Cloud Function (functions/src/index.ts) with the Firebase callable protocol
 * and an App Check token. The TypeSafe API key lives only in the function's Secret Manager secret;
 * the app never sees it. Only Redactor output is sent.
 */
@Singleton
class JevClient @Inject constructor() {
    private val http = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    private fun url(): String {
        val project = FirebaseApp.getInstance().options.projectId ?: error("Firebase project not configured")
        return "https://us-central1-$project.cloudfunctions.net/jevCheck"
    }

    private suspend fun appCheckToken(): String = suspendCancellableCoroutine { c ->
        FirebaseAppCheck.getInstance().getAppCheckToken(false)
            .addOnSuccessListener { c.resume(it.token) }
            .addOnFailureListener { c.resumeWithException(IllegalStateException("App Check token unavailable: ${it.message}", it)) }
    }

    suspend fun check(redactedText: String, sender: String): JevAnswers = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val body = buildJsonObject { put("data", buildJsonObject { put("redactedText", redactedText); put("sender", sender) }) }.toString()
        val req = Request.Builder().url(url())
            .header("X-Firebase-AppCheck", appCheckToken())
            .post(body.toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            // A failed call is an error, never a "no".
            root?.get("error")?.jsonObject?.let { e -> error("jevCheck ${e["status"]?.jsonPrimitive?.content}: ${e["message"]?.jsonPrimitive?.content}") }
            if (!r.isSuccessful || root == null) error(if (r.code == 404) "Jev function isn't deployed yet (HTTP 404)" else "jevCheck HTTP ${r.code}")
            val res: JsonObject = root["result"]?.jsonObject ?: error("jevCheck returned no result")
            JevAnswers(
                signals = res["signals"]!!.jsonObject.mapValues { it.value.jsonPrimitive.double },
                model = res["model"]!!.jsonPrimitive.content,
                questionsVersion = res["questionsVersion"]!!.jsonPrimitive.content,
                inputTokens = res["inputTokens"]?.jsonPrimitive?.int ?: 0,
                latencyMs = System.currentTimeMillis() - t0,
            )
        }
    }
}
