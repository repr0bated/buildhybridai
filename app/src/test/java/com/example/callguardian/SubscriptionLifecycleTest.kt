package com.example.callguardian

import com.example.callguardian.data.db.SubscriptionSchema
import com.example.callguardian.subscriptions.SubscriptionStatus
import com.example.callguardian.subscriptions.UnsubscribeConfirmation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

class SubscriptionLifecycleTest {

    @Test fun confirmationPatterns() {
        assertTrue(UnsubscribeConfirmation.matches("You have been unsubscribed from alerts."))
        assertTrue(UnsubscribeConfirmation.matches("You will no longer receive messages from us."))
        assertTrue(UnsubscribeConfirmation.matches("You've been unsubscribed"))
        assertFalse(UnsubscribeConfirmation.matches("Reply STOP to unsubscribe."))
        assertFalse(UnsubscribeConfirmation.matches(null))
        assertFalse(UnsubscribeConfirmation.matches("Your package is on the way."))
    }

    @Test fun statusNames() {
        assertEquals(
            listOf("ACTIVE", "STOP_SENT", "UNSUBSCRIBED", "CANCELED"),
            SubscriptionStatus.entries.map { it.name },
        )
        assertEquals(SubscriptionStatus.ACTIVE, SubscriptionStatus.from("nope"))
        assertEquals(SubscriptionStatus.STOP_SENT, SubscriptionStatus.from("STOP_SENT"))
    }

    @Test fun migration6to7CreatesTables() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { st ->
                st.execute(SubscriptionSchema.CREATE_SUBSCRIPTIONS)
                st.execute(SubscriptionSchema.CREATE_HISTORY)
                st.execute(SubscriptionSchema.INDEX_SENDER)
                st.execute(SubscriptionSchema.INDEX_STATUS)
                st.execute(SubscriptionSchema.INDEX_HISTORY)
                st.execute("INSERT INTO subscriptions (sender, service, source, status, created_at, updated_at, pending_cancel_ask, muted) VALUES ('32123','Cash App','sms','ACTIVE',1,1,0,0)")
                st.execute("UPDATE subscriptions SET status='STOP_SENT', stop_sent_at=2, updated_at=2 WHERE sender='32123'")
                st.execute("INSERT INTO subscription_status_history (subscription_id, from_status, to_status, at, reason) VALUES (1,'ACTIVE','STOP_SENT',2,'STOP sent after confirmation')")
                // Canceling a subscription must not delete any owned_accounts row — table may not exist here;
                // the schema simply has no foreign key or trigger that would.
                val rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='trigger'")
                val triggers = mutableListOf<String>()
                while (rs.next()) triggers += rs.getString(1)
                rs.close()
                assertTrue(triggers.none { it.contains("owned", ignoreCase = true) })
                val status = st.executeQuery("SELECT status FROM subscriptions WHERE sender='32123'")
                assertTrue(status.next())
                assertEquals("STOP_SENT", status.getString(1))
                status.close()
            }
        }
    }
}
