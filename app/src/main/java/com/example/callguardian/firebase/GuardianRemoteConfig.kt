package com.example.callguardian.firebase

import com.example.callguardian.BuildConfig
import com.example.callguardian.data.settings.OnDeviceModelChoice
import com.example.callguardian.screening.VerdictCalibrator
import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase Remote Config (free on the Spark plan) for calibrator thresholds, model names,
 * the startup auto-process toggle, and the cloud triage prompt.
 *
 * Server prompt templates ([KEY_CLOUD_PROMPT_TEMPLATE]) are tried first when a template
 * id is set. If the Developer API / Spark project does not serve templates, the app
 * falls back to [KEY_CLOUD_PROMPT] (or the in-app [com.example.callguardian.ai.Prompts]).
 * App Check stays enabled for all Firebase traffic.
 */
@Singleton
class GuardianRemoteConfig @Inject constructor() {

    private val rc: FirebaseRemoteConfig? = runCatching { Firebase.remoteConfig }.getOrNull()

    val scamThreshold: Double get() = doubleOr(KEY_SCAM_THRESHOLD, VerdictCalibrator.SCAM_THRESHOLD)
    val clearThreshold: Double get() = doubleOr(KEY_CLEAR_THRESHOLD, VerdictCalibrator.CLEAR_THRESHOLD)
    val cloudModel: String get() = stringOr(KEY_CLOUD_MODEL, BuildConfig.CLOUD_MODEL).ifBlank { BuildConfig.CLOUD_MODEL }
    val startupAutoProcess: Boolean get() = rc?.getBoolean(KEY_STARTUP_AUTO_PROCESS) ?: DEFAULT_STARTUP_AUTO_PROCESS
    /** Server prompt-template id, or blank if unused. */
    val cloudPromptTemplate: String get() = stringOr(KEY_CLOUD_PROMPT_TEMPLATE, "")
    /** Full cloud triage prompt, or blank to use the in-app Prompts.triage text. */
    val cloudPrompt: String get() = stringOr(KEY_CLOUD_PROMPT, "")

    fun onDeviceChoice(fallback: OnDeviceModelChoice): OnDeviceModelChoice {
        val raw = stringOr(KEY_ON_DEVICE_MODEL, "")
        if (raw.isBlank()) return fallback
        return runCatching { OnDeviceModelChoice.valueOf(raw.trim().uppercase()) }.getOrDefault(fallback)
    }

    suspend fun fetch() {
        val config = rc ?: return
        runCatching {
            config.setDefaultsAsync(DEFAULTS).await()
            config.setConfigSettingsAsync(
                remoteConfigSettings {
                    minimumFetchIntervalInSeconds = if (BuildConfig.DEBUG) 0 else 3_600
                },
            ).await()
            config.fetchAndActivate().await()
        }.onFailure { Timber.w(it, "Remote Config fetch failed; using defaults") }
    }

    private fun doubleOr(key: String, fallback: Double): Double {
        val v = rc?.getDouble(key) ?: return fallback
        return if (v > 0.0) v else fallback
    }

    private fun stringOr(key: String, fallback: String): String =
        rc?.getString(key)?.takeIf { it.isNotBlank() } ?: fallback

    companion object {
        const val KEY_SCAM_THRESHOLD = "scam_threshold"
        const val KEY_CLEAR_THRESHOLD = "clear_threshold"
        const val KEY_CLOUD_MODEL = "cloud_model"
        const val KEY_ON_DEVICE_MODEL = "on_device_model"
        const val KEY_STARTUP_AUTO_PROCESS = "startup_auto_process"
        const val KEY_CLOUD_PROMPT = "cloud_prompt"
        const val KEY_CLOUD_PROMPT_TEMPLATE = "cloud_prompt_template"
        const val DEFAULT_STARTUP_AUTO_PROCESS = true

        val DEFAULTS: Map<String, Any> = mapOf(
            KEY_SCAM_THRESHOLD to VerdictCalibrator.SCAM_THRESHOLD,
            KEY_CLEAR_THRESHOLD to VerdictCalibrator.CLEAR_THRESHOLD,
            KEY_CLOUD_MODEL to BuildConfig.CLOUD_MODEL,
            KEY_ON_DEVICE_MODEL to "",
            KEY_STARTUP_AUTO_PROCESS to DEFAULT_STARTUP_AUTO_PROCESS,
            KEY_CLOUD_PROMPT to "",
            KEY_CLOUD_PROMPT_TEMPLATE to "",
        )
    }
}
