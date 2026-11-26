package com.example.callguardian.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * FTS4 content-sync triggers that match what Room generates for [ScreeningEventFts].
 *
 * The original v4→v5 migration installed FTS5-style
 * `INSERT INTO fts(fts, docid) VALUES('delete', …)` statements on an FTS4 table.
 * That throws `SQLiteException: SQL logic error (code 1)` on every UPDATE of
 * `screening_events`. These helpers drop those leftover triggers and install
 * Room's BEFORE UPDATE / AFTER UPDATE form, which is valid FTS4.
 */
object ScreeningEventFtsSupport {
    const val TABLE = "screening_events_fts"
    const val CONTENT = "screening_events"

    val legacyTriggerNames = listOf(
        "room_fts_content_sync_screening_events_fts_INSERT",
        "room_fts_content_sync_screening_events_fts_UPDATE",
        "room_fts_content_sync_screening_events_fts_DELETE",
    )

    val roomTriggerNames = listOf(
        "room_fts_content_sync_screening_events_fts_BEFORE_UPDATE",
        "room_fts_content_sync_screening_events_fts_BEFORE_DELETE",
        "room_fts_content_sync_screening_events_fts_AFTER_UPDATE",
        "room_fts_content_sync_screening_events_fts_AFTER_INSERT",
    )

    val allTriggerNames = legacyTriggerNames + roomTriggerNames

    val dropTriggerSql: List<String> = allTriggerNames.map { "DROP TRIGGER IF EXISTS `$it`" }

    val installTriggerSql: List<String> = listOf(
        """CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_screening_events_fts_BEFORE_UPDATE BEFORE UPDATE ON `$CONTENT` BEGIN DELETE FROM `$TABLE` WHERE `docid`=OLD.`rowid`; END""",
        """CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_screening_events_fts_BEFORE_DELETE BEFORE DELETE ON `$CONTENT` BEGIN DELETE FROM `$TABLE` WHERE `docid`=OLD.`rowid`; END""",
        """CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_screening_events_fts_AFTER_UPDATE AFTER UPDATE ON `$CONTENT` BEGIN INSERT INTO `$TABLE`(`docid`, `body`, `number`, `display_name`, `category`, `risk`, `source`, `reasons`, `advice`, `lookup_summary`) VALUES (NEW.`rowid`, NEW.`body`, NEW.`number`, NEW.`display_name`, NEW.`category`, NEW.`risk`, NEW.`source`, NEW.`reasons`, NEW.`advice`, NEW.`lookup_summary`); END""",
        """CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_screening_events_fts_AFTER_INSERT AFTER INSERT ON `$CONTENT` BEGIN INSERT INTO `$TABLE`(`docid`, `body`, `number`, `display_name`, `category`, `risk`, `source`, `reasons`, `advice`, `lookup_summary`) VALUES (NEW.`rowid`, NEW.`body`, NEW.`number`, NEW.`display_name`, NEW.`category`, NEW.`risk`, NEW.`source`, NEW.`reasons`, NEW.`advice`, NEW.`lookup_summary`); END""",
    )

    /** FTS5-style delete used by the buggy v4→v5 migration — kept for regression tests only. */
    val LEGACY_BAD_UPDATE_TRIGGER = """
        CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_screening_events_fts_UPDATE AFTER UPDATE ON `$CONTENT` BEGIN
            INSERT INTO `$TABLE`(`$TABLE`, `docid`) VALUES('delete', old.`id`);
            INSERT INTO `$TABLE`(`docid`, `body`, `number`, `display_name`, `category`, `risk`, `source`, `reasons`, `advice`, `lookup_summary`)
            VALUES (new.`id`, new.`body`, new.`number`, new.`display_name`, new.`category`, new.`risk`, new.`source`, new.`reasons`, new.`advice`, new.`lookup_summary`);
        END
    """.trimIndent()

    val LEGACY_INSERT_TRIGGER = """
        CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_screening_events_fts_INSERT AFTER INSERT ON `$CONTENT` BEGIN
            INSERT INTO `$TABLE`(`docid`, `body`, `number`, `display_name`, `category`, `risk`, `source`, `reasons`, `advice`, `lookup_summary`)
            VALUES (new.`id`, new.`body`, new.`number`, new.`display_name`, new.`category`, new.`risk`, new.`source`, new.`reasons`, new.`advice`, new.`lookup_summary`);
        END
    """.trimIndent()

    const val CREATE_FTS = "CREATE VIRTUAL TABLE IF NOT EXISTS `$TABLE` USING FTS4(`body`, `number`, `display_name`, `category`, `risk`, `source`, `reasons`, `advice`, `lookup_summary`, content=`$CONTENT`)"

    fun dropTriggers(db: SupportSQLiteDatabase) {
        dropTriggerSql.forEach { db.execSQL(it) }
    }

    fun installRoomTriggers(db: SupportSQLiteDatabase) {
        dropTriggers(db)
        installTriggerSql.forEach { db.execSQL(it) }
    }
}
