package com.example.callguardian.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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

/**
 * Direct Gemini Developer API (BYOK) fallback. The key is sent only as the
 * `x-goog-api-key` header, never in the URL, and is never logged.
 */
@Singleton
class GeminiDeveloperClient @Inject constructor() {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun generate(apiKey: String, model: String, prompt: String, temperature: Float, maxOutputTokens: Int): String {
        require(apiKey.isNotBlank()) { "Gemini API key is empty" }
        require(prompt.isNotBlank()) { "Prompt is empty" }
        val safeModel = model.ifBlank { "gemini-2.5-flash" }
        val url = "$ENDPOINT/models/$safeModel:generateContent"
        val body = buildJsonObject {
            put("contents", JsonArray(listOf(buildJsonObject {
                put("role", "user")
                put("parts", JsonArray(listOf(buildJsonObject { put("text", prompt) })))
            })))
            put("generationConfig", buildJsonObject {
                put("temperature", JsonPrimitive(temperature.toDouble()))
                put("maxOutputTokens", JsonPrimitive(maxOutputTokens))
                put("responseMimeType", "application/json")
            })
        }.toString()
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        val response = http.newCall(request).execute()
        response.use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Timber.w("Gemini Developer API request failed (%s)", resp.code)
                error("Gemini Developer API request failed (${resp.code})")
            }
            return textOf(payload).ifBlank { error("Gemini Developer API returned an empty response") }
        }
    }

    companion object {
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        internal fun textOf(payload: String): String {
            val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return ""
            val parts = root["candidates"]?.jsonArray
                ?.getOrNull(0)
                ?.jsonObject
                ?.get("content")
                ?.jsonObject
                ?.get("parts")
                ?.jsonArray ?: return ""
            return parts.mapNotNull { part ->
                part.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }.joinToString("\n").trim()
        }
    }
}
