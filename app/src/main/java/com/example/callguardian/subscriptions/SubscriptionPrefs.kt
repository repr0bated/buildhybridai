package com.example.callguardian.subscriptions

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.subsStore: DataStore<Preferences> by preferencesDataStore(name = "subscriptions")

data class SubscriptionMarks(val muted: Set<String> = emptySet(), val kept: Set<String> = emptySet(), val unsubscribed: Set<String> = emptySet())

/** Local-only marks set by guardian.sms.mute@v1 and guardian.sms.unsubscribe@v1. Never leaves the phone. */
@Singleton
class SubscriptionPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.subsStore
    private val muted = stringSetPreferencesKey("muted")
    private val kept = stringSetPreferencesKey("kept")
    private val unsub = stringSetPreferencesKey("unsubscribed")

    val marks: Flow<SubscriptionMarks> = store.data.map { SubscriptionMarks(it[muted].orEmpty(), it[kept].orEmpty(), it[unsub].orEmpty()) }
    suspend fun current() = marks.first()

    suspend fun setMode(sender: String, mode: String) = store.edit { p ->
        p[muted] = p[muted].orEmpty() - sender
        p[kept] = p[kept].orEmpty() - sender
        when (mode) { "MUTE" -> p[muted] = p[muted].orEmpty() + sender; "KEEP" -> p[kept] = p[kept].orEmpty() + sender }
    }
    suspend fun markUnsubscribed(sender: String) = store.edit { it[unsub] = it[unsub].orEmpty() + sender }
    suspend fun markUnsubscribed(senders: Collection<String>) = store.edit { it[unsub] = it[unsub].orEmpty() + senders }
    suspend fun setMode(senders: Collection<String>, mode: String) { senders.forEach { setMode(it, mode) } }
}

