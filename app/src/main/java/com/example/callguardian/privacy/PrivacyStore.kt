package com.example.callguardian.privacy

import com.example.callguardian.data.db.PrivacyMarkDao
import com.example.callguardian.data.db.PrivacyMarkEntity
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

@Singleton
class PrivacyStore @Inject constructor(private val dao: PrivacyMarkDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var snapshot: Set<String> = emptySet()
        private set

    val keys: Flow<Set<String>> = dao.observeAll()
        .map { rows -> rows.map { it.key }.toSet() }
        .onEach { snapshot = it }
        .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    suspend fun set(kind: String, eventId: Long?, address: String?, callLogId: Long?): String {
        val key = PrivacyKeys.key(kind, eventId, address, callLogId)
        dao.upsert(PrivacyMarkEntity(key, kind, address?.let(PrivacyKeys::normalize), eventId, System.currentTimeMillis()))
        return key
    }

    suspend fun clear(kind: String, eventId: Long?, address: String?, callLogId: Long?): String {
        val key = PrivacyKeys.key(kind, eventId, address, callLogId)
        dao.delete(key)
        return key
    }
}
