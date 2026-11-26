package com.example.callguardian

import com.example.callguardian.screening.NanoPolicy
import com.example.callguardian.screening.NanoRequiresForegroundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NanoPolicyTest {

    @Test fun nanoOnlyWhenResumed() {
        assertTrue(NanoPolicy.canCallNano(resumed = true))
        assertFalse(NanoPolicy.canCallNano(resumed = false))
        assertEquals(NanoPolicy.Route.NANO, NanoPolicy.route(resumed = true, privateMode = false))
        assertEquals(NanoPolicy.Route.NANO, NanoPolicy.route(resumed = true, privateMode = true))
    }

    @Test fun backgroundRoutesToCloudUnlessPrivate() {
        assertEquals(NanoPolicy.Route.CLOUD, NanoPolicy.route(resumed = false, privateMode = false))
        assertEquals(NanoPolicy.Route.QUEUE, NanoPolicy.route(resumed = false, privateMode = true))
    }

    @Test fun drainPausesWhenNotResumed() {
        assertFalse(NanoPolicy.shouldPauseDrain(resumed = true))
        assertTrue(NanoPolicy.shouldPauseDrain(resumed = false))
    }

    @Test fun error9MapsToQuietWaitingStatus() {
        val t = RuntimeException("model set usage quota; request is from disallowed background usage; use the API while the app is in the foreground")
        assertTrue(NanoPolicy.isError9(t))
        assertTrue(NanoPolicy.isError9(RuntimeException("AICore ErrorCode 9")))
        assertTrue(NanoPolicy.isError9(NanoRequiresForegroundException()))
        assertEquals(NanoPolicy.WAITING_FOR_APP, NanoPolicy.quietStatus(t))
        assertEquals("Waiting for app to be open", NanoPolicy.WAITING_FOR_APP)
    }

    @Test fun cloud429IsNotError9AndStillQueuesQuietly() {
        val t = RuntimeException("HTTP 429 RESOURCE_EXHAUSTED")
        assertFalse(NanoPolicy.isError9(t))
        assertTrue(NanoPolicy.isCloudRateLimited(t))
        assertEquals(NanoPolicy.WAITING_FOR_APP, NanoPolicy.quietStatus(t))
    }

    @Test fun backoffDoublesOnFailureAndResetsOnSuccess() {
        assertEquals(800L, NanoPolicy.nextBackoffMs(400L, failed = true))
        assertEquals(1600L, NanoPolicy.nextBackoffMs(800L, failed = true))
        assertEquals(NanoPolicy.MAX_BACKOFF_MS, NanoPolicy.nextBackoffMs(8_000L, failed = true))
        assertEquals(NanoPolicy.MIN_BACKOFF_MS, NanoPolicy.nextBackoffMs(8_000L, failed = false))
    }
}
