package com.example.callguardian.lookup

import com.example.callguardian.data.db.LookupCacheDao
import com.example.callguardian.data.settings.LookupPreferences
import com.example.callguardian.model.LookupResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queries enabled reverse-lookup providers in the user-configured order and
 * merges the first non-empty value for each field. Per-provider results are
 * cached in Room (and in memory for an hour). Sources without credentials
 * return null immediately.
 */
@Singleton
class ReverseLookupManager @Inject constructor(
    private val sources: Set<@JvmSuppressWildcards ReverseLookupSource>,
    private val ioDispatcher: CoroutineDispatcher,
    private val cacheDao: LookupCacheDao,
    private val prefs: LookupPreferences,
    private val secrets: LookupSecrets,
) {
    private data class Cached(val result: LookupResult, val at: Long)
    private val memory = ConcurrentHashMap<String, Cached>()

    suspend fun cached(phoneNumber: String): MergedLookup = withContext(ioDispatcher) {
        mergeResults(cachedResults(phoneNumber), order())
    }

    suspend fun cachedResults(phoneNumber: String): List<LookupResult> = withContext(ioDispatcher) {
        val keys = ReverseLookup.numberVariants(phoneNumber).map { ReverseLookup.cacheKey(it) }.distinct()
        if (keys.isEmpty()) return@withContext emptyList()
        val fromMem = keys.flatMap { k ->
            memory.entries.filter { it.key.startsWith("$k|") }.map { it.value.result }
        }
        val fromRoom = keys.flatMap { cacheDao.forNumber(it) }.map { ReverseLookup.toResult(it) }
        (fromMem + fromRoom).distinctBy { it.resolvedSourceId }
    }

    /**
     * Merge cached hits, then fill gaps from enabled providers in order.
     * @param forceNetwork still starts with cache but re-queries every enabled provider
     */
    suspend fun lookup(
        phoneNumber: String,
        budgetMs: Long = ReverseLookup.SCREENING_BUDGET_MS,
        forceNetwork: Boolean = false,
    ): LookupResult? {
        val merged = lookupMerged(phoneNumber, budgetMs, forceNetwork)
        return merged.toLegacy()
    }

    suspend fun lookupMerged(
        phoneNumber: String,
        budgetMs: Long = ReverseLookup.SCREENING_BUDGET_MS,
        forceNetwork: Boolean = false,
    ): MergedLookup = withContext(ioDispatcher) {
        val order = order()
        val existing = cachedResults(phoneNumber)
        if (!forceNetwork && existing.isNotEmpty()) {
            val merged = mergeResults(existing, order)
            if (!merged.isEmpty) return@withContext merged
        }
        val enabled = enabledSources(order)
        val fetched = existing.toMutableList()
        withTimeoutOrNull(budgetMs) {
            for (source in enabled) {
                if (!forceNetwork && fetched.any { it.resolvedSourceId == source.id }) continue
                val r = runSource(source, phoneNumber)
                if (r != null) {
                    persist(r)
                    fetched.removeAll { it.resolvedSourceId == source.id }
                    fetched.add(r)
                }
            }
        }
        mergeResults(fetched, order)
    }

    /** Query a single provider (bulk lookup). Writes through to Room. */
    suspend fun lookupOne(phoneNumber: String, sourceId: String): LookupResult? = withContext(ioDispatcher) {
        val source = sources.firstOrNull { it.id == sourceId } ?: return@withContext null
        val r = runSource(source, phoneNumber) ?: return@withContext null
        persist(r)
        r
    }

    suspend fun hasCached(phoneNumber: String, sourceId: String): Boolean = withContext(ioDispatcher) {
        val key = ReverseLookup.cacheKey(phoneNumber)
        memory["$key|$sourceId"] != null || cacheDao.get(key, sourceId) != null
    }

    private suspend fun runSource(source: ReverseLookupSource, phoneNumber: String): LookupResult? {
        return runCatching { source.lookup(phoneNumber) }
            .onFailure {
                if (it is LookupRateLimited) throw it
                Timber.w("Lookup via %s failed: %s", source.id, it.message)
            }
            .getOrNull()
    }

    private suspend fun persist(result: LookupResult) {
        val key = ReverseLookup.cacheKey(result.phoneNumber)
        val at = System.currentTimeMillis()
        memory["$key|${result.resolvedSourceId}"] = Cached(result, at)
        runCatching { cacheDao.upsert(ReverseLookup.toEntity(result, key, at)) }
            .onFailure { Timber.w("Failed to persist lookup cache") }
    }

    private fun mergeResults(results: List<LookupResult>, order: List<String>): MergedLookup =
        ReverseLookup.merge(results, order)

    private suspend fun order(): List<String> {
        val stored = prefs.current().lookupProviderOrder
        return LookupProvider.ordered(stored).map { it.id }
    }

    private suspend fun enabledSources(order: List<String>): List<ReverseLookupSource> {
        val settings = prefs.current()
        val byId = sources.associateBy { it.id }
        return order.mapNotNull { id ->
            val src = byId[id] ?: return@mapNotNull null
            val on = when (id) {
                LookupProvider.TWILIO.id -> secrets.hasTwilio.value
                LookupProvider.TELNYX.id -> secrets.hasTelnyx.value
                else -> settings.providerConfigured(id)
            }
            src.takeIf { on }
        }
    }

    companion object { private const val MEMORY_MS = 60 * 60 * 1000L }
}

private fun MergedLookup.toLegacy(): LookupResult? {
    if (isEmpty) return null
    return LookupResult(
        phoneNumber = phoneNumber,
        displayName = displayName.value,
        carrier = carrier.value,
        region = region.value,
        lineType = lineType.value,
        valid = when (valid.value) {
            "Valid" -> true
            "Invalid" -> false
            else -> null
        },
        source = listOfNotNull(displayName.sourceLabel, carrier.sourceLabel, lineType.sourceLabel).firstOrNull() ?: "merged",
        sourceId = displayName.sourceId ?: carrier.sourceId ?: lineType.sourceId ?: "",
        rawPayload = mapOf("merged" to true),
    )
}
