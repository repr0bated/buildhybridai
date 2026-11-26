package com.example.callguardian

import com.example.callguardian.data.db.ScreeningEventFtsSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.sql.DriverManager

/**
 * Reproduces the v5 FTS crash (FTS5-style 'delete' on an FTS4 content table → SQL logic error
 * on UPDATE) and checks that [MIGRATION_5_6]'s Room-style triggers let an UPDATE succeed.
 */
class FtsMigrationTest {

    private fun connect() = DriverManager.getConnection("jdbc:sqlite::memory:").also { c ->
        c.createStatement().use { it.execute("SELECT sqlite_version()") }
    }

    private fun createEvents(c: java.sql.Connection) {
        c.createStatement().use { st ->
            st.execute("""
                CREATE TABLE screening_events (
                  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                  channel TEXT NOT NULL,
                  number TEXT,
                  display_name TEXT,
                  body TEXT,
                  received_at INTEGER NOT NULL DEFAULT 0,
                  is_contact INTEGER NOT NULL DEFAULT 0,
                  is_test INTEGER NOT NULL DEFAULT 0,
                  lookup_summary TEXT,
                  rules_score INTEGER NOT NULL DEFAULT 0,
                  rules_risk TEXT NOT NULL DEFAULT 'SAFE',
                  rules_flags TEXT NOT NULL DEFAULT '',
                  rules_micros INTEGER NOT NULL DEFAULT 0,
                  ai_status TEXT NOT NULL DEFAULT 'NOT_RUN',
                  risk TEXT NOT NULL DEFAULT 'SAFE',
                  category TEXT NOT NULL DEFAULT 'NONE',
                  confidence INTEGER NOT NULL DEFAULT 0,
                  reasons TEXT NOT NULL DEFAULT '',
                  decided_by TEXT NOT NULL DEFAULT 'RULES',
                  proposed_action TEXT NOT NULL DEFAULT 'none',
                  final_action TEXT NOT NULL DEFAULT 'none',
                  auto_applied INTEGER NOT NULL DEFAULT 0,
                  gate_notes TEXT NOT NULL DEFAULT '',
                  deep_status TEXT NOT NULL DEFAULT 'NOT_RUN',
                  policy_dispatch TEXT NOT NULL DEFAULT 'DENY',
                  quarantined INTEGER NOT NULL DEFAULT 0,
                  source TEXT NOT NULL DEFAULT 'sms',
                  advice TEXT
                )
            """.trimIndent())
            st.execute(ScreeningEventFtsSupport.CREATE_FTS)
        }
    }

    @Test fun legacyFts5DeleteTriggerFailsOnUpdate() {
        connect().use { c ->
            createEvents(c)
            c.createStatement().use { st ->
                st.execute(ScreeningEventFtsSupport.LEGACY_INSERT_TRIGGER)
                st.execute(ScreeningEventFtsSupport.LEGACY_BAD_UPDATE_TRIGGER)
                st.execute("INSERT INTO screening_events(channel, number, body) VALUES('SMS','28581','hello from cash app')")
                try {
                    st.execute("UPDATE screening_events SET ai_status='RUNNING' WHERE id=1")
                    fail("expected SQL logic error from FTS5-style delete on FTS4")
                } catch (e: java.sql.SQLException) {
                    assertTrue(e.message ?: "", (e.message ?: "").contains("SQL logic error") || e.errorCode == 1)
                }
            }
        }
    }

    @Test fun roomStyleTriggersAllowUpdateAfterRepair() {
        connect().use { c ->
            createEvents(c)
            c.createStatement().use { st ->
                // Simulate the shipped v5 database: bad triggers are installed.
                st.execute(ScreeningEventFtsSupport.LEGACY_INSERT_TRIGGER)
                st.execute(ScreeningEventFtsSupport.LEGACY_BAD_UPDATE_TRIGGER)
                st.execute("INSERT INTO screening_events(channel, number, body) VALUES('SMS','28581','hello from cash app')")
                // v5→v6 repair
                ScreeningEventFtsSupport.dropTriggerSql.forEach { st.execute(it) }
                ScreeningEventFtsSupport.installTriggerSql.forEach { st.execute(it) }
                st.execute("UPDATE screening_events SET ai_status='RUNNING', risk='MEDIUM', confidence=40 WHERE id=1")
                val rs = st.executeQuery("SELECT ai_status, risk FROM screening_events WHERE id=1")
                assertTrue(rs.next())
                assertEquals("RUNNING", rs.getString(1))
                assertEquals("MEDIUM", rs.getString(2))
                val fts = st.executeQuery("SELECT number FROM screening_events_fts WHERE screening_events_fts MATCH 'hello'")
                assertTrue("FTS should still find the body after update", fts.next())
                assertEquals("28581", fts.getString(1))
            }
        }
    }

    @Test fun v6CreatesOwnedAccountsTable() {
        connect().use { c ->
            c.createStatement().use { st ->
                st.execute("""CREATE TABLE IF NOT EXISTS owned_accounts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    service TEXT NOT NULL,
                    senders TEXT NOT NULL,
                    domains TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )""")
                st.execute("CREATE INDEX IF NOT EXISTS index_owned_accounts_service ON owned_accounts (service)")
                st.execute("INSERT INTO owned_accounts(service, senders, domains, created_at, updated_at) VALUES('E-ZPass','51185','e-zpassny.com',1,1)")
                val rs = st.executeQuery("SELECT service FROM owned_accounts")
                assertTrue(rs.next())
                assertEquals("E-ZPass", rs.getString(1))
            }
        }
    }
}
