package com.example.callguardian.accounts

import com.example.callguardian.data.db.OwnedAccountDao
import com.example.callguardian.data.db.OwnedAccountEntity
import com.example.callguardian.data.db.SafeDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/** Held accounts persist in Room permanently. They are never auto-pruned and are only removed by an explicit unmark ([delete]). Canceling a subscription does not remove the Owned account. */
@Singleton
class OwnedStore @Inject constructor(private val dao: OwnedAccountDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var snapshot: List<OwnedAccount> = emptyList()
        private set

    val accounts: Flow<List<OwnedAccount>> = dao.observeAll()
        .map { rows -> rows.map { it.toModel() } }
        .onEach { snapshot = it }
        .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    fun matchSender(number: String?): OwnedAccount? = number?.let { n -> snapshot.firstOrNull { it.matchesSender(n) } }

    fun claimedBrand(body: String?): OwnedAccount? {
        if (body.isNullOrBlank()) return null
        return snapshot.firstOrNull { acct ->
            body.contains(acct.service, ignoreCase = true) ||
                com.example.callguardian.screening.OfficialDomains.mentionedBrands(body).any { m ->
                    com.example.callguardian.screening.OfficialDomains.brandKey(acct.service) ==
                        com.example.callguardian.screening.OfficialDomains.brandKey(m)
                }
        }
    }

    suspend fun upsert(service: String, senders: List<String>, domains: List<String>, existingId: Long? = null): OwnedAccount {
        val now = System.currentTimeMillis()
        val cleanSenders = senders.map(SenderIds::normalize).filter { it.isNotBlank() }.distinct()
        val cleanDomains = domains.map { it.lowercase().removePrefix("www.") }.filter { it.contains('.') }.distinct()
        val name = service.trim().take(80).ifBlank { "Account" }
        val prior = existingId?.let { id -> snapshot.firstOrNull { it.id == id } }
            ?: snapshot.firstOrNull { it.service.equals(name, true) }
        val entity = OwnedAccountEntity(
            id = prior?.id ?: 0,
            service = name,
            senders = (prior?.senders.orEmpty() + cleanSenders).distinct().joinToString(","),
            domains = (prior?.domains.orEmpty() + cleanDomains).distinct().joinToString(","),
            createdAt = prior?.createdAt ?: now,
            updatedAt = now,
        )
        val id = SafeDb.write("owned.upsert") { if (entity.id == 0L) dao.insert(entity) else { dao.update(entity); entity.id } } ?: entity.id
        return entity.toModel().copy(id = id)
    }

    suspend fun delete(id: Long) { SafeDb.write("owned.delete") { dao.delete(id) } }

    suspend fun get(id: Long): OwnedAccount? = dao.get(id)?.toModel()
}

private fun OwnedAccountEntity.toModel() = OwnedAccount(
    id = id, service = service,
    senders = SenderIds.parseList(senders),
    domains = SenderIds.parseDomains(domains),
    createdAt = createdAt, updatedAt = updatedAt,
)
