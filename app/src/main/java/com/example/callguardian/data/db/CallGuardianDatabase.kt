package com.example.callguardian.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ScreeningEventEntity::class, PhoneProfileEntity::class, ReceiptEntity::class], version = 4, exportSchema = true)
abstract class CallGuardianDatabase : RoomDatabase() {
    abstract fun events(): ScreeningEventDao
    abstract fun profiles(): PhoneProfileDao
    abstract fun receipts(): ReceiptDao
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
