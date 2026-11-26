package com.example.callguardian.ai

import com.example.callguardian.BuildConfig
import com.example.callguardian.data.settings.OnDeviceModelChoice
import com.example.callguardian.model.VerdictSource
import com.google.firebase.Firebase
import com.google.firebase.ai.DownloadStatus
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.OnDeviceModelOption
import com.google.firebase.ai.OnDeviceModelStatus
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Raw output of one model call, before schema validation. */
data class ModelRun(
    val rawText: String?,
    val source: VerdictSource,
    val modelName: String?,
    val latencyMs: Long,
    /** Set when on-device failed and cloud Gemini was used instead. Shown in the pipeline step. */
    val fallbackNote: String? = null,
)

enum class DeviceModelState { UNKNOWN, UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

/**
 * Every generative call in the app goes through Firebase AI Logic (`com.google.firebase:firebase-ai`
 * + `firebase-ai-ondevice`).
 *
 * On-device (Gemini Nano 4 / Gemma 4 via AICore) is strict about the request: `maxOutputTokens`
 * must be ≤ 256, and `generateObject()` / `responseSchema` can be rejected (ErrorCode -110) when
 * the generated schema is invalid (for example a fake "Companion" enum value). So triage on
 * device uses `generateContent()` with a clipped JSON prompt, then the app validates the text
 * against the published JSON Schema. Cloud Gemini can still use `generateObject()`.
 *
 * If the on-device call fails and private mode is off, we automatically retry with cloud Gemini
 * and surface that as a cloud fallback, not a hard failure.
 */
@Singleton
class HybridScamAnalyzer @Inject constructor() {

    private val cloudModelName = BuildConfig.CLOUD_MODEL

    private fun option(choice: OnDeviceModelChoice): OnDeviceModelOption = when (choice) {
        OnDeviceModelChoice.PREVIEW_FAST -> OnDeviceModelOption.PREVIEW_FAST
        OnDeviceModelChoice.PREVIEW -> OnDeviceModelOption.PREVIEW
        OnDeviceModelChoice.STABLE -> OnDeviceModelOption.STABLE
    }

    private val triageModels = HashMap<Pair<OnDeviceModelChoice, Boolean>, GenerativeModel>()

    @Synchronized
    fun triageModel(choice: OnDeviceModelChoice, privateMode: Boolean): GenerativeModel =
        triageModels.getOrPut(choice to privateMode) {
            Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                modelName = cloudModelName,
                // Keep generationConfig inside the on-device limit; 400 was rejected by AICore (-110).
                generationConfig = generationConfig { temperature = 0.1f; maxOutputTokens = ON_DEVICE_MAX_OUTPUT },
                onDeviceConfig = OnDeviceConfig(
                    mode = if (privateMode) InferenceMode.ONLY_ON_DEVICE else InferenceMode.PREFER_ON_DEVICE,
                    maxOutputTokens = ON_DEVICE_MAX_OUTPUT,
                    temperature = 0.1f,
                    candidateCount = 1,
                    modelOption = option(choice),
                ),
            )
        }

    private val cloudTriageModel: GenerativeModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = cloudModelName,
            generationConfig = generationConfig { temperature = 0.1f; maxOutputTokens = 400 },
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_IN_CLOUD),
        )
    }

    private val deepModel: GenerativeModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = cloudModelName,
            generationConfig = generationConfig { temperature = 0.2f; maxOutputTokens = 900 },
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_IN_CLOUD),
        )
    }

    // ---- on-device model lifecycle (AICore) ----

    suspend fun deviceState(choice: OnDeviceModelChoice): DeviceModelState = try {
        when (triageModel(choice, true).onDeviceExtension?.checkStatus()) {
            OnDeviceModelStatus.AVAILABLE -> DeviceModelState.AVAILABLE
            OnDeviceModelStatus.DOWNLOADABLE -> DeviceModelState.DOWNLOADABLE
            OnDeviceModelStatus.DOWNLOADING -> DeviceModelState.DOWNLOADING
            OnDeviceModelStatus.UNAVAILABLE -> DeviceModelState.UNAVAILABLE
            else -> DeviceModelState.UNKNOWN
        }
    } catch (e: Throwable) {
        Timber.w(e, "On-device status check failed")
        DeviceModelState.UNAVAILABLE
    }

    fun download(choice: OnDeviceModelChoice): Flow<DownloadStatus> =
        triageModel(choice, true).onDeviceExtension?.download() ?: emptyFlow()

    suspend fun warmUp(choice: OnDeviceModelChoice) {
        runCatching { triageModel(choice, true).onDeviceExtension?.warmUp() }
            .onFailure { Timber.w(it, "warmUp failed") }
    }

    /** e.g. "nano-v4-fast" — shown in the UI so it's clear which Gemma 4 / Nano variant ran. */
    suspend fun deviceModelName(choice: OnDeviceModelChoice): String? =
        runCatching { triageModel(choice, true).onDeviceExtension?.getModelName() }.getOrNull()

    // ---- inference ----

    suspend fun triage(prompt: String, choice: OnDeviceModelChoice, privateMode: Boolean): ModelRun {
        val clipped = clipPrompt(prompt)
        return try {
            runOnDevice(clipped, choice, privateMode)
        } catch (t: Throwable) {
            if (privateMode) throw t
            Timber.w(t, "On-device triage failed; falling back to cloud Gemini")
            val note = "On-device request failed (${shortError(t)}); used cloud Gemini automatically."
            runCloudTriage(clipped).copy(fallbackNote = note)
        }
    }

    /** Subscription confirmation: always ONLY_ON_DEVICE (inbox content never leaves the phone). */
    suspend fun subscriptionCheck(prompt: String, choice: OnDeviceModelChoice): ModelRun =
        runOnDevice(clipPrompt(prompt), choice, privateMode = true)

    suspend fun deepAnalysis(prompt: String): ModelRun {
        val t0 = System.currentTimeMillis()
        val result = deepModel.generateObject(DeepAnalysis.firebaseAISchema(), prompt)
        return ModelRun(result.response.text, VerdictSource.IN_CLOUD, cloudModelName, System.currentTimeMillis() - t0)
    }

    /**
     * Plain text generation, no responseSchema / responseMimeType / system instruction — those
     * have been rejected by AICore on Nano with ErrorCode -110. The app already validates JSON.
     */
    private suspend fun runOnDevice(prompt: String, choice: OnDeviceModelChoice, privateMode: Boolean): ModelRun {
        require(prompt.isNotBlank()) { "On-device prompt is empty" }
        val model = triageModel(choice, privateMode)
        val t0 = System.currentTimeMillis()
        val result = model.generateContent(prompt)
        val latency = System.currentTimeMillis() - t0
        val source = if (result.inferenceSource == InferenceSource.ON_DEVICE) VerdictSource.ON_DEVICE else VerdictSource.IN_CLOUD
        val name = if (source == VerdictSource.ON_DEVICE) deviceModelName(choice) ?: choice.title else cloudModelName
        val text = result.text?.trim().orEmpty()
        if (text.isEmpty()) error("On-device model returned an empty response")
        return ModelRun(text, source, name, latency)
    }

    private suspend fun runCloudTriage(prompt: String): ModelRun {
        val t0 = System.currentTimeMillis()
        val result = cloudTriageModel.generateObject(ScamVerdict.firebaseAISchema(), prompt)
        return ModelRun(result.response.text, VerdictSource.IN_CLOUD, cloudModelName, System.currentTimeMillis() - t0)
    }

    companion object {
        /** AICore / Gemini Nano 4 rejects values above this (ErrorCode -110). */
        const val ON_DEVICE_MAX_OUTPUT = 256
        /** Keep the prompt well under the on-device input window (~4k tokens, often less). */
        const val ON_DEVICE_PROMPT_CHARS = 2400

        fun clipPrompt(text: String, maxChars: Int = ON_DEVICE_PROMPT_CHARS): String {
            val t = text.trim()
            require(t.isNotEmpty()) { "Prompt is empty" }
            if (t.length <= maxChars) return t
            return t.take(maxChars).trimEnd() + "\n[truncated]"
        }

        fun shortError(t: Throwable): String {
            val m = t.message.orEmpty()
            val code = Regex("""ErrorCode\s*-?\d+""").find(m)?.value
            return when {
                code != null && m.contains("Invalid arguments", true) -> "$code invalid request"
                code != null -> code
                m.contains("Invalid arguments", true) -> "invalid request"
                else -> (t.message ?: t.javaClass.simpleName).take(80)
            }
        }
    }
}
