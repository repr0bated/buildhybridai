package com.example.callguardian.lookup

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
 * Twilio Account SID + Auth Token and Telnyx API key, stored with the Android
 * Keystore (EncryptedSharedPreferences). Never written to logs, never synced,
 * never put in Backup / DataStore / receipts.
 */
@Singleton
class LookupSecrets @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences? = runCatching { open(context) }.getOrElse {
        Timber.w("Encrypted lookup key store is unavailable")
        null
    }

    private val _hasTwilio = MutableStateFlow(read(TWILIO_SID).isNotBlank() && read(TWILIO_TOKEN).isNotBlank())
    val hasTwilio: StateFlow<Boolean> = _hasTwilio.asStateFlow()

    private val _hasTelnyx = MutableStateFlow(read(TELNYX_KEY).isNotBlank())
    val hasTelnyx: StateFlow<Boolean> = _hasTelnyx.asStateFlow()

    /** For a Twilio request only. Do not log or copy this value. */
    fun twilioSid(): String = read(TWILIO_SID)

    /** For a Twilio request only. Do not log or copy this value. */
    fun twilioToken(): String = read(TWILIO_TOKEN)

    /** For a Telnyx request only. Do not log or copy this value. */
    fun telnyxKey(): String = read(TELNYX_KEY)

    fun setTwilio(sid: String, token: String) {
        val s = sid.trim()
        val t = token.trim()
        runCatching {
            val e = prefs?.edit() ?: return@runCatching
            if (s.isEmpty() || t.isEmpty()) {
                e.remove(TWILIO_SID).remove(TWILIO_TOKEN)
            } else {
                e.putString(TWILIO_SID, s).putString(TWILIO_TOKEN, t)
            }
            e.apply()
        }.onFailure { Timber.w("Could not save Twilio credentials") }
        _hasTwilio.value = s.isNotEmpty() && t.isNotEmpty() && prefs != null
    }

    fun clearTwilio() = setTwilio("", "")

    fun setTelnyx(key: String) {
        val trimmed = key.trim()
        runCatching {
            if (trimmed.isEmpty()) prefs?.edit()?.remove(TELNYX_KEY)?.apply()
            else prefs?.edit()?.putString(TELNYX_KEY, trimmed)?.apply()
        }.onFailure { Timber.w("Could not save the Telnyx key") }
        _hasTelnyx.value = trimmed.isNotEmpty() && prefs != null
    }

    fun clearTelnyx() = setTelnyx("")

    fun configured(providerId: String): Boolean = when (providerId) {
        LookupProvider.TWILIO.id -> _hasTwilio.value
        LookupProvider.TELNYX.id -> _hasTelnyx.value
        else -> false
    }

    private fun read(key: String): String {
        val store = prefs ?: return ""
        return runCatching { store.getString(key, "").orEmpty() }.getOrDefault("")
    }

    companion object {
        const val PREFS_NAME = "lookup_secrets"
        private const val TWILIO_SID = "twilio_account_sid"
        private const val TWILIO_TOKEN = "twilio_auth_token"
        private const val TELNYX_KEY = "telnyx_api_key"

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
