package com.example.callguardian.integrations

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.callguardian.methods.MethodRegistry
import com.example.callguardian.methods.gen.Integrations
import com.example.callguardian.model.MessageSource
import com.example.callguardian.policy.SchemaValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

typealias Integration = Integrations.Integration

/**
 * The schema-driven source config (assets/schemas/integrations.json, checked against
 * integrations.schema.json; the Kotlin type is generated from that schema). Settings →
 * Integrations, the notification listener and the Lab tabs all read this one list.
 */
class IntegrationCatalog(read: (String) -> String) {
    val config: Integrations
    init {
        val text = read("integrations.json")
        val report = SchemaValidator(read("integrations.schema.json"), "integrations.schema.json").validate(text)
        require(report.valid) { "integrations.json is invalid: ${report.errors}" }
        config = MethodRegistry.json.decodeFromString(Integrations.serializer(), text)
    }
    val all: List<Integration> get() = config.integrations
    fun byId(id: String) = all.firstOrNull { it.id.name == id }
    fun forPackage(pkg: String) = all.firstOrNull { it.kind == Integrations.Kind.notification_listener && it.packages?.contains(pkg) == true }
    val listenerPackages: Set<String> get() = all.filter { it.kind == Integrations.Kind.notification_listener }.flatMap { it.packages.orEmpty() }.toSet()
    fun source(i: Integration): MessageSource = MessageSource.fromWire(i.source?.name)
    fun ignores(i: Integration, text: String) = i.ignoreTextPatterns.orEmpty().any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(text.trim()) }
}

private val Context.integrationsStore: DataStore<Preferences> by preferencesDataStore(name = "integrations")

data class IntegrationState(val enabled: Map<String, Boolean>, val lastSeen: Map<String, Long>)

/** On/off per integration (written only by guardian.integration.set@v1) and last-message-seen times. */
@Singleton
class IntegrationPrefs @Inject constructor(@ApplicationContext context: Context, private val catalog: IntegrationCatalog) {
    private val store = context.integrationsStore
    val state: Flow<IntegrationState> = store.data.map { p ->
        IntegrationState(
            catalog.all.associate { it.id.name to (p[booleanPreferencesKey("on_${it.id.name}")] ?: it.defaultEnabled) },
            catalog.all.associate { it.id.name to (p[longPreferencesKey("seen_${it.id.name}")] ?: 0L) },
        )
    }
    suspend fun current() = state.first()
    suspend fun enabled(id: String) = current().enabled[id] ?: false
    suspend fun set(id: String, on: Boolean) = store.edit { it[booleanPreferencesKey("on_$id")] = on }
    suspend fun seen(id: String, at: Long = System.currentTimeMillis()) = store.edit { it[longPreferencesKey("seen_$id")] = at }
}

data class IntegrationStatus(val available: Boolean, val accessGranted: Boolean, val missingPermissions: List<String>, val line: String)
