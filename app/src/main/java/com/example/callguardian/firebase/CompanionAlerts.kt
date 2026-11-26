package com.example.callguardian.firebase

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Free-plan web companion alerts. When this phone blocks a scam and the user is
 * signed in, a PII-free document is written to Firestore. The web app listens
 * with onSnapshot. Server-side FCM send would need Cloud Functions (Blaze only),
 * so this path never sends FCM from the server.
 */
@Singleton
class CompanionAlerts @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val devicePrefs: SharedPreferences =
        context.getSharedPreferences("companion_alerts", Context.MODE_PRIVATE)

    private val _signedIn = MutableStateFlow(Firebase.auth.currentUser != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    fun email(): String? = Firebase.auth.currentUser?.email
    fun uid(): String? = Firebase.auth.currentUser?.uid
    fun webClientConfigured(): Boolean = !webClientId().isNullOrBlank()

    fun deviceId(): String {
        val existing = devicePrefs.getString(DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val id = UUID.randomUUID().toString()
        devicePrefs.edit().putString(DEVICE_ID, id).apply()
        return id
    }

    fun publish(category: String, source: String) {
        val uid = uid() ?: return
        val cat = InferenceTelemetry.safeParam(category, InferenceTelemetry.CATEGORIES) ?: return
        val src = InferenceTelemetry.safeParam(source, setOf(InferenceTelemetry.SOURCE_NANO, InferenceTelemetry.SOURCE_CLOUD, InferenceTelemetry.SOURCE_LOCAL)) ?: return
        val id = "${deviceId()}-${System.currentTimeMillis()}"
        val doc = hashMapOf(
            "id" to id,
            "category" to cat,
            "source" to src,
            "action" to "block",
            "createdAt" to System.currentTimeMillis(),
            "deviceId" to deviceId(),
            "read" to false,
            "deleted" to false,
        )
        scope.launch {
            runCatching {
                Firebase.firestore.collection("users").document(uid)
                    .collection("companionAlerts").document(id)
                    .set(doc)
                    .await()
            }.onFailure { Timber.w("Companion alert was not written") }
        }
    }

    suspend fun signIn(activity: Activity) {
        val clientId = webClientId() ?: error("Google sign-in is not configured in this build")
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(clientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = CredentialManager.create(activity).getCredential(activity, request)
        val cred = result.credential
        val idToken = when {
            cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ->
                GoogleIdTokenCredential.createFrom(cred.data).idToken
            else -> error("Google sign-in did not return an ID token")
        }
        val authCred = GoogleAuthProvider.getCredential(idToken, null)
        Firebase.auth.signInWithCredential(authCred).await()
        _signedIn.value = Firebase.auth.currentUser != null
    }

    suspend fun signOut(activity: Activity? = null) {
        Firebase.auth.signOut()
        if (activity != null) {
            runCatching { CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest()) }
        }
        _signedIn.value = false
    }

    private fun webClientId(): String? {
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (resId == 0) return null
        return runCatching { context.getString(resId) }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("YOUR_") }
    }

    companion object {
        private const val DEVICE_ID = "device_id"
    }
}
