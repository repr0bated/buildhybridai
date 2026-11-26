package com.example.callguardian.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ScreeningEventEntity::class, PhoneProfileEntity::class, ReceiptEntity::class,
        QuarantinedSmsEntity::class, PrivacyMarkEntity::class, ScreeningEventFts::class,
        OwnedAccountEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class CallGuardianDatabase : RoomDatabase() {
    abstract fun events(): ScreeningEventDao
    abstract fun profiles(): PhoneProfileDao
    abstract fun receipts(): ReceiptDao
    abstract fun quarantined(): QuarantinedSmsDao
    abstract fun privacyMarks(): PrivacyMarkDao
    abstract fun ownedAccounts(): OwnedAccountDao
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
