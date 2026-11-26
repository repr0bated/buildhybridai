package com.example.callguardian.jev

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TypeSafe Jev API key, stored encrypted with the Android Keystore
 * (EncryptedSharedPreferences). Never written to logs, never synced, never
 * put in Backup / DataStore / receipts.
 */
@Singleton
class JevSecrets @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences? = runCatching { open(context) }.getOrElse {
        Timber.w("Encrypted Jev key store is unavailable")
        null
    }

    private val _hasKey = MutableStateFlow(readKey().isNotBlank())
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    val isKeySet: Boolean get() = _hasKey.value

    /** Returns the key for a TypeSafe request only. Do not log or copy this value. */
    fun apiKey(): String = readKey()

    fun setApiKey(value: String) {
        val trimmed = value.trim()
        runCatching {
            if (trimmed.isEmpty()) prefs?.edit()?.remove(KEY)?.apply()
            else prefs?.edit()?.putString(KEY, trimmed)?.apply()
        }.onFailure { Timber.w("Could not save the Jev key") }
        _hasKey.value = trimmed.isNotEmpty() && prefs != null
    }

    fun clear() = setApiKey("")

    private fun readKey(): String {
        val store = prefs ?: return ""
        return runCatching { store.getString(KEY, "").orEmpty() }.getOrDefault("")
    }

    companion object {
        const val PREFS_NAME = "jev_secrets"
        private const val KEY = "typesafe_api_key"

        private fun open(context: Context): SharedPreferences {
            val master = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            return EncryptedSharedPreferences.create(
                PREFS_NAME,
                master,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
