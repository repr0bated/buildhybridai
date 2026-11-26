package com.example.callguardian.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ScreeningEventDao {
    @Insert abstract suspend fun insertOrThrow(e: ScreeningEventEntity): Long
    @Update abstract suspend fun updateOrThrow(e: ScreeningEventEntity)
    suspend fun insert(e: ScreeningEventEntity): Long = SafeDb.write("screening_events.insert") { insertOrThrow(e) } ?: 0L
    suspend fun update(e: ScreeningEventEntity) { SafeDb.write("screening_events.update") { updateOrThrow(e) } }
    @Query("SELECT * FROM screening_events WHERE id = :id") abstract suspend fun get(id: Long): ScreeningEventEntity?
    @Query("SELECT * FROM screening_events WHERE id = :id") abstract fun observe(id: Long): Flow<ScreeningEventEntity?>
    @Query("SELECT * FROM screening_events ORDER BY received_at DESC LIMIT :limit") abstract fun observeRecent(limit: Int = 200): Flow<List<ScreeningEventEntity>>
    @Query("SELECT COUNT(*) FROM screening_events WHERE channel = 'CALL' AND number = :number AND received_at > :since")
    abstract suspend fun countCallsSince(number: String, since: Long): Int
    @Query("SELECT * FROM screening_events WHERE ai_status IN ('PENDING', 'UNPROCESSED') ORDER BY received_at ASC LIMIT 100")
    abstract suspend fun pendingAi(): List<ScreeningEventEntity>
    @Query("SELECT * FROM screening_events WHERE source IN (:sources) AND channel = 'SMS' ORDER BY received_at DESC LIMIT :limit")
    abstract suspend fun bySource(sources: List<String>, limit: Int): List<ScreeningEventEntity>
    @Query("SELECT * FROM screening_events WHERE integration = :integration AND is_test = 0 ORDER BY received_at DESC LIMIT :limit")
    abstract fun observeIntegration(integration: String, limit: Int = 300): Flow<List<ScreeningEventEntity>>
    @Query("DELETE FROM screening_events WHERE id = :id") abstract suspend fun deleteOrThrow(id: Long)
    suspend fun delete(id: Long) { SafeDb.write("screening_events.delete") { deleteOrThrow(id) } }
    @Query("DELETE FROM screening_events") abstract suspend fun clearOrThrow()
    suspend fun clear() { SafeDb.write("screening_events.clear") { clearOrThrow() } }
    @Query("SELECT COUNT(*) FROM screening_events WHERE number = :number")
    abstract suspend fun countByNumber(number: String): Int
    @Query("SELECT * FROM screening_events WHERE number = :number ORDER BY received_at DESC LIMIT :limit")
    abstract suspend fun byNumber(number: String, limit: Int = 50): List<ScreeningEventEntity>
    @Query("""
        SELECT * FROM screening_events
        WHERE id IN (SELECT rowid FROM screening_events_fts WHERE screening_events_fts MATCH :match)
        ORDER BY received_at DESC LIMIT :limit
    """)
    abstract suspend fun searchFts(match: String, limit: Int = 200): List<ScreeningEventEntity>
    @Query("""
        SELECT * FROM screening_events
        WHERE number LIKE '%' || :digits || '%' OR display_name LIKE '%' || :raw || '%'
        ORDER BY received_at DESC LIMIT :limit
    """)
    abstract suspend fun searchNumberOrName(digits: String, raw: String, limit: Int = 200): List<ScreeningEventEntity>
}


@Dao
interface PhoneProfileDao {
    @Query("SELECT * FROM phone_profiles WHERE phone_number = :n") suspend fun get(n: String): PhoneProfileEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(p: PhoneProfileEntity)
    @Query("SELECT * FROM phone_profiles WHERE block_mode != 'NONE' OR trusted = 1 OR watched = 1 ORDER BY updated_at DESC")
    fun observeListed(): Flow<List<PhoneProfileEntity>>
}

@Dao
interface ReceiptDao {
    @Insert suspend fun insert(r: ReceiptEntity): Long
    @Query("SELECT * FROM method_receipts ORDER BY created_at DESC LIMIT :limit") fun observeRecent(limit: Int = 300): Flow<List<ReceiptEntity>>
    @Query("SELECT * FROM method_receipts WHERE event_id = :eventId ORDER BY created_at DESC") fun observeForEvent(eventId: Long): Flow<List<ReceiptEntity>>
    @Query("SELECT * FROM method_receipts WHERE id = :id") fun observe(id: Long): Flow<ReceiptEntity?>
    @Query("DELETE FROM method_receipts") suspend fun clear()
}

@Dao
interface QuarantinedSmsDao {
    @Insert suspend fun insert(row: QuarantinedSmsEntity): Long
    @Query("SELECT * FROM quarantined_sms ORDER BY hidden_at DESC") fun observeAll(): kotlinx.coroutines.flow.Flow<List<QuarantinedSmsEntity>>
    @Query("SELECT * FROM quarantined_sms WHERE id = :id") suspend fun get(id: Long): QuarantinedSmsEntity?
    @Query("DELETE FROM quarantined_sms WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT COUNT(*) FROM quarantined_sms") fun observeCount(): kotlinx.coroutines.flow.Flow<Int>
}


@Dao
interface PrivacyMarkDao {
    @Query("SELECT * FROM privacy_marks")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<PrivacyMarkEntity>>
    @Query("SELECT * FROM privacy_marks")
    suspend fun all(): List<PrivacyMarkEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PrivacyMarkEntity)
    @Query("DELETE FROM privacy_marks WHERE `key` = :key")
    suspend fun delete(key: String)
}


@Dao
interface OwnedAccountDao {
    @Query("SELECT * FROM owned_accounts ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<OwnedAccountEntity>>
    @Query("SELECT * FROM owned_accounts ORDER BY updated_at DESC")
    suspend fun all(): List<OwnedAccountEntity>
    @Query("SELECT * FROM owned_accounts WHERE id = :id")
    suspend fun get(id: Long): OwnedAccountEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: OwnedAccountEntity): Long
    @Update
    suspend fun update(row: OwnedAccountEntity)
    @Query("DELETE FROM owned_accounts WHERE id = :id")
    suspend fun delete(id: Long)
}


@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<SubscriptionEntity>>
    @Query("SELECT * FROM subscriptions ORDER BY updated_at DESC")
    suspend fun all(): List<SubscriptionEntity>
    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun get(id: Long): SubscriptionEntity?
    @Query("SELECT * FROM subscriptions WHERE sender = :sender LIMIT 1")
    suspend fun bySender(sender: String): SubscriptionEntity?
    @Query("SELECT * FROM subscriptions WHERE pending_cancel_ask = 1")
    suspend fun pendingCancelAsks(): List<SubscriptionEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: SubscriptionEntity): Long
    @Update
    suspend fun update(row: SubscriptionEntity)
}

@Dao
interface SubscriptionHistoryDao {
    @Query("SELECT * FROM subscription_status_history WHERE subscription_id = :id ORDER BY at DESC")
    suspend fun forSubscription(id: Long): List<SubscriptionStatusHistoryEntity>
    @Insert
    suspend fun insert(row: SubscriptionStatusHistoryEntity): Long
}

@Dao
interface LookupCacheDao {
    @Query("SELECT * FROM lookup_cache WHERE phone_number = :number")
    suspend fun forNumber(number: String): List<LookupCacheEntity>

    @Query("SELECT * FROM lookup_cache WHERE phone_number = :number AND source = :source")
    suspend fun get(number: String, source: String): LookupCacheEntity?

    @Query("SELECT phone_number FROM lookup_cache WHERE source = :source")
    suspend fun numbersForSource(source: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: LookupCacheEntity)

    @Query("DELETE FROM lookup_cache WHERE fetched_at < :before")
    suspend fun evictOlderThan(before: Long)
}
