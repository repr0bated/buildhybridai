package com.example.callguardian.ai

import com.example.callguardian.BuildConfig
import com.example.callguardian.screening.AppForeground
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.OnDeviceModelOption
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.generationConfig
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Short on-device smart replies (Gemini Nano via Firebase AI Logic) while
 * the app is in the foreground. Falls back to a cloud Gemini call when
 * on-device inference is unavailable. Never runs Nano in the background.
 */
@Singleton
class SmartReplies @Inject constructor() {

    private val onDevice: GenerativeModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = BuildConfig.CLOUD_MODEL,
            generationConfig = generationConfig { temperature = 0.4f; maxOutputTokens = 120 },
            onDeviceConfig = OnDeviceConfig(
                mode = InferenceMode.ONLY_ON_DEVICE,
                maxOutputTokens = 120,
                temperature = 0.4f,
                candidateCount = 1,
                modelOption = OnDeviceModelOption.STABLE,
            ),
        )
    }

    private val cloud: GenerativeModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = BuildConfig.CLOUD_MODEL,
            generationConfig = generationConfig { temperature = 0.4f; maxOutputTokens = 120 },
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_IN_CLOUD),
        )
    }

    suspend fun suggest(messages: List<String>, incomingLast: Boolean): List<String> {
        if (messages.isEmpty() || !incomingLast) return emptyList()
        val prompt = buildPrompt(messages.takeLast(8))
        if (AppForeground.isForeground) {
            runCatching {
                val text = onDevice.generateContent(prompt).text
                val parsed = parseReplies(text)
                if (parsed.isNotEmpty()) return parsed
            }.onFailure { Timber.i(it, "On-device smart replies unavailable; trying cloud") }
        }
        return runCatching {
            parseReplies(cloud.generateContent(prompt).text)
        }.onFailure { Timber.w(it, "Cloud smart replies failed") }.getOrDefault(emptyList())
    }

    companion object {
        fun buildPrompt(messages: List<String>): String {
            val transcript = messages.joinToString("\n") { "- $it" }
            return """
                Suggest up to 3 very short SMS replies the user might send next.
                Each reply must be a single line, at most 8 words, no quotes, no numbering
                once printed as a bare line. Be natural and polite. Do not invent facts.
                Conversation (oldest first):
                $transcript
            """.trimIndent()
        }

        fun parseReplies(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.lineSequence()
                .map { it.trim().trimStart('"', '\'', '-', '*', '•') }
                .map { it.replace(Regex("^\\d+[.)]\\s*"), "") }
                .map { it.trim().trim('"') }
                .filter { it.isNotBlank() && it.length <= 80 && !it.startsWith("Suggest", ignoreCase = true) }
                .distinct()
                .take(3)
                .toList()
        }
    }
}
