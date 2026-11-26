package com.example.callguardian

import com.example.callguardian.privacy.PrivacyKeys
import com.example.callguardian.privacy.PrivacyUnlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyKeysTest {
    @Test fun normalizeKeepsDigitsWhenLongEnough() {
        assertEquals("15550100199", PrivacyKeys.normalize("+1 (555) 010-0199"))
        assertEquals("ann", PrivacyKeys.normalize("Ann"))
    }

    @Test fun eventKeyAndCoverage() {
        val marks = setOf(PrivacyKeys.event(3), PrivacyKeys.thread("5550100"), PrivacyKeys.contact("5550199"), PrivacyKeys.call(8))
        assertTrue(PrivacyKeys.coversEvent(marks, 3, "999", false))
        assertTrue(PrivacyKeys.coversEvent(marks, 1, "(555) 0100", false)) // thread
        assertFalse(PrivacyKeys.coversEvent(marks, 1, "(555) 0100", true)) // thread does not cover calls
        assertTrue(PrivacyKeys.coversEvent(marks, 1, "5550199", true)) // contact covers calls
        assertTrue(PrivacyKeys.coversCall(marks, 8, "000"))
        assertTrue(PrivacyKeys.coversCall(marks, 1, "5550199"))
        assertFalse(PrivacyKeys.coversCall(marks, 1, "5550100"))
    }

    @Test fun sessionTimesOutAndLocks() {
        val s = PrivacyUnlock(timeoutMs = 1_000)
        assertFalse(s.isUnlocked(0))
        s.unlock(10_000)
        assertTrue(s.isUnlocked(10_500))
        assertFalse(s.isUnlocked(11_000))
        s.unlock(20_000)
        s.lock()
        assertFalse(s.isUnlocked(20_100))
    }
}

class SmsRoleTest {
    @Test fun rememberPreviousKeepsOtherAppAndIgnoresSelf() {
        assertEquals("com.google.android.apps.messaging", com.example.callguardian.sms.SmsRole.rememberPrevious("com.google.android.apps.messaging", "io.github.repr0bated.callguardian.debug", null))
        assertEquals("stored", com.example.callguardian.sms.SmsRole.rememberPrevious("io.github.repr0bated.callguardian.debug", "io.github.repr0bated.callguardian.debug", "stored"))
        assertEquals("stored", com.example.callguardian.sms.SmsRole.rememberPrevious(null, "self", "stored"))
        assertEquals(null, com.example.callguardian.sms.SmsRole.rememberPrevious(null, "self", null))
    }
}

class InboxMatcherTest {
    @Test fun pickPrefersUriThenClosestSameBody() {
        val rows = listOf(
            com.example.callguardian.sms.InboxMatch(1, "5550100", "hi", 100),
            com.example.callguardian.sms.InboxMatch(2, "5550100", "hi", 190),
            com.example.callguardian.sms.InboxMatch(3, "999", "hi", 200),
        )
        assertEquals(2L, com.example.callguardian.sms.InboxMatcher.pick(rows, "5550100", "hi", 200, uriId = 2)!!.id)
        assertEquals(2L, com.example.callguardian.sms.InboxMatcher.pick(rows, "(555) 0100", "hi", 200)!!.id)
    }
}
