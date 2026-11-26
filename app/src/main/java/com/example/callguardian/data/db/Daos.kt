package com.example.callguardian.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScreeningEventDao {
    @Insert suspend fun insert(e: ScreeningEventEntity): Long
    @Update suspend fun update(e: ScreeningEventEntity)
    @Query("SELECT * FROM screening_events WHERE id = :id") suspend fun get(id: Long): ScreeningEventEntity?
    @Query("SELECT * FROM screening_events WHERE id = :id") fun observe(id: Long): Flow<ScreeningEventEntity?>
    @Query("SELECT * FROM screening_events ORDER BY received_at DESC LIMIT :limit") fun observeRecent(limit: Int = 200): Flow<List<ScreeningEventEntity>>
    @Query("SELECT COUNT(*) FROM screening_events WHERE channel = 'CALL' AND number = :number AND received_at > :since")
    suspend fun countCallsSince(number: String, since: Long): Int
    @Query("SELECT * FROM screening_events WHERE ai_status = 'PENDING' ORDER BY received_at DESC LIMIT 10")
    suspend fun pendingAi(): List<ScreeningEventEntity>
    @Query("SELECT * FROM screening_events WHERE source IN (:sources) AND channel = 'SMS' ORDER BY received_at DESC LIMIT :limit")
    suspend fun bySource(sources: List<String>, limit: Int): List<ScreeningEventEntity>
    @Query("SELECT * FROM screening_events WHERE integration = :integration AND is_test = 0 ORDER BY received_at DESC LIMIT :limit")
    fun observeIntegration(integration: String, limit: Int = 300): Flow<List<ScreeningEventEntity>>
    @Query("DELETE FROM screening_events WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM screening_events") suspend fun clear()
    @Query("""
        SELECT * FROM screening_events
        WHERE id IN (SELECT rowid FROM screening_events_fts WHERE screening_events_fts MATCH :match)
        ORDER BY received_at DESC LIMIT :limit
    """)
    suspend fun searchFts(match: String, limit: Int = 200): List<ScreeningEventEntity>
    @Query("""
        SELECT * FROM screening_events
        WHERE number LIKE '%' || :digits || '%' OR display_name LIKE '%' || :raw || '%'
        ORDER BY received_at DESC LIMIT :limit
    """)
    suspend fun searchNumberOrName(digits: String, raw: String, limit: Int = 200): List<ScreeningEventEntity>
}


@Dao
interface PhoneProfileDao {
    @Query("SELECT * FROM phone_profiles WHERE phone_number = :n") suspend fun get(n: String): PhoneProfileEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(p: PhoneProfileEntity)
    @Query("SELECT * FROM phone_profiles WHERE block_mode != 'NONE' OR trusted = 1 ORDER BY updated_at DESC")
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
