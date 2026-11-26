package com.example.callguardian.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ScreeningEventEntity::class, PhoneProfileEntity::class, ReceiptEntity::class,
        QuarantinedSmsEntity::class, PrivacyMarkEntity::class, ScreeningEventFts::class,
        OwnedAccountEntity::class, SubscriptionEntity::class, SubscriptionStatusHistoryEntity::class,
        LookupCacheEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class CallGuardianDatabase : RoomDatabase() {
    abstract fun events(): ScreeningEventDao
    abstract fun profiles(): PhoneProfileDao
    abstract fun receipts(): ReceiptDao
    abstract fun quarantined(): QuarantinedSmsDao
    abstract fun privacyMarks(): PrivacyMarkDao
    abstract fun ownedAccounts(): OwnedAccountDao
    abstract fun subscriptions(): SubscriptionDao
    abstract fun subscriptionHistory(): SubscriptionHistoryDao
    abstract fun lookupCache(): LookupCacheDao
}

/** v5: quarantine folder, optional sms_uri on events, privacy marks, and FTS4 over event bodies. */
val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE screening_events ADD COLUMN sms_uri TEXT")
        db.execSQL("""CREATE TABLE IF NOT EXISTS quarantined_sms (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            address TEXT NOT NULL,
            body TEXT NOT NULL,
            date INTEGER NOT NULL,
            date_sent INTEGER NOT NULL,
            event_id INTEGER,
            hidden_at INTEGER NOT NULL,
            telephony_uri TEXT
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS privacy_marks (
            `key` TEXT NOT NULL PRIMARY KEY,
            kind TEXT NOT NULL,
            address TEXT,
            event_id INTEGER,
            created_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_privacy_marks_kind ON privacy_marks (kind)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_privacy_marks_address ON privacy_marks (address)")
        db.execSQL(ScreeningEventFtsSupport.CREATE_FTS)
        ScreeningEventFtsSupport.installRoomTriggers(db)
        db.execSQL("INSERT INTO `screening_events_fts`(`screening_events_fts`) VALUES('rebuild')")
    }
}

/**
 * v6: repair FTS4 content-sync triggers left by the original v4→v5 migration (FTS5 'delete'
 * command → SQL logic error on UPDATE), and add owned_accounts.
 */
val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        ScreeningEventFtsSupport.installRoomTriggers(db)
        db.execSQL("""CREATE TABLE IF NOT EXISTS owned_accounts (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            service TEXT NOT NULL,
            senders TEXT NOT NULL,
            domains TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_owned_accounts_service ON owned_accounts (service)")
    }
}

/** v4 adds `source` (Google Voice support). Existing rows came from SMS or calls. */
val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE screening_events ADD COLUMN source TEXT NOT NULL DEFAULT 'sms'")
        db.execSQL("ALTER TABLE screening_events ADD COLUMN integration TEXT")
        db.execSQL("UPDATE screening_events SET source = 'call_screening' WHERE channel = 'CALL'")
        db.execSQL("UPDATE screening_events SET source = 'lab_sample' WHERE is_test = 1")
    }
}


/**
 * v7: subscription lifecycle (ACTIVE / STOP_SENT / UNSUBSCRIBED / CANCELED) with timestamps
 * and a status history. Owned accounts are unchanged and are never auto-pruned.
 */
object SubscriptionSchema {
    val CREATE_SUBSCRIPTIONS = """
        CREATE TABLE IF NOT EXISTS subscriptions (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            sender TEXT NOT NULL,
            service TEXT NOT NULL,
            source TEXT NOT NULL,
            keyword TEXT,
            link_url TEXT,
            link_domain TEXT,
            link_kind TEXT,
            status TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            stop_sent_at INTEGER,
            unsubscribed_at INTEGER,
            canceled_at INTEGER,
            stop_receipt_id INTEGER,
            unsubscribed_event_id INTEGER,
            unsubscribed_receipt_id INTEGER,
            canceled_receipt_id INTEGER,
            pending_cancel_ask INTEGER NOT NULL,
            muted INTEGER NOT NULL
        )
    """.trimIndent()
    val CREATE_HISTORY = """
        CREATE TABLE IF NOT EXISTS subscription_status_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            subscription_id INTEGER NOT NULL,
            from_status TEXT NOT NULL,
            to_status TEXT NOT NULL,
            at INTEGER NOT NULL,
            reason TEXT NOT NULL,
            receipt_id INTEGER,
            event_id INTEGER
        )
    """.trimIndent()
    const val INDEX_SENDER = "CREATE INDEX IF NOT EXISTS index_subscriptions_sender ON subscriptions (sender)"
    const val INDEX_STATUS = "CREATE INDEX IF NOT EXISTS index_subscriptions_status ON subscriptions (status)"
    const val INDEX_HISTORY = "CREATE INDEX IF NOT EXISTS index_subscription_status_history_subscription_id ON subscription_status_history (subscription_id)"

    fun install(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(CREATE_SUBSCRIPTIONS)
        db.execSQL(CREATE_HISTORY)
        db.execSQL(INDEX_SENDER)
        db.execSQL(INDEX_STATUS)
        db.execSQL(INDEX_HISTORY)
    }
}

val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        SubscriptionSchema.install(db)
    }
}

val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE phone_profiles ADD COLUMN watched INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS lookup_cache (
                phone_number TEXT NOT NULL,
                source TEXT NOT NULL,
                display_name TEXT,
                carrier TEXT,
                line_type TEXT,
                region TEXT,
                valid INTEGER,
                raw_json TEXT,
                fetched_at INTEGER NOT NULL,
                PRIMARY KEY(phone_number, source)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_lookup_cache_phone_number ON lookup_cache (phone_number)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_lookup_cache_source ON lookup_cache (source)")
    }
}
