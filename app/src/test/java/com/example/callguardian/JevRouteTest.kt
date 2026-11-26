package com.example.callguardian

import com.example.callguardian.jev.JevEndpoints
import com.example.callguardian.jev.JevQuestions
import com.example.callguardian.jev.JevRoute
import com.example.callguardian.jev.JevTruthTable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JevRouteTest {

    @Test fun prefersDirectWhenAKeyIsSet() {
        assertEquals(JevRoute.DIRECT, JevEndpoints.resolve(hasKey = true, workerUrl = "https://jev.example.workers.dev"))
        assertEquals(JevRoute.DIRECT, JevEndpoints.resolve(hasKey = true, workerUrl = ""))
    }

    @Test fun usesWorkerWhenNoKeyButUrlIsSet() {
        val base = JevEndpoints.DEFAULT_WORKER_URL
        assertEquals(JevRoute.WORKER, JevEndpoints.resolve(hasKey = false, workerUrl = base))
        assertEquals("$base/v1/check", JevEndpoints.workerCheckUrl("$base/"))
        assertEquals("$base/v1/check:batch", JevEndpoints.workerBatchUrl("$base/v1/check"))
        assertEquals("$base/v1/check:batch", JevEndpoints.workerBatchUrl("$base/v1/check:batch"))
        assertEquals("$base/jevCheck", JevEndpoints.workerCallableUrl(base))
        assertEquals(base, JevEndpoints.normalizeBase("$base/jevCheck"))
    }

    @Test fun unavailableWhenNeitherKeyNorWorkerUrl() {
        assertEquals(JevRoute.UNAVAILABLE, JevEndpoints.resolve(hasKey = false, workerUrl = ""))
        assertEquals(JevRoute.UNAVAILABLE, JevEndpoints.resolve(hasKey = false, workerUrl = "   "))
        assertEquals("", JevEndpoints.workerCheckUrl(""))
    }

    @Test fun systemOneRequestHasModelStateAndAllSignals() {
        val req = JevQuestions.buildRequest("Your [digits] code is ready.", "short_code")
        assertEquals(JevQuestions.MODEL, req["model"]!!.jsonPrimitive.content)
        val state = req["state"]!!.jsonObject
        assertEquals("Your [digits] code is ready.", state["message"]!!.jsonPrimitive.content)
        assertTrue(state["sender"]!!.jsonPrimitive.content.contains("short code"))
        val questions = req["questions"]!!.jsonObject
        assertEquals(JevTruthTable.Signal.entries.size, questions.size)
        JevQuestions.SIGNAL_IDS.forEach { id ->
            assertEquals("noul", questions[id]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test fun jevStaysOffByDefaultInTheCatalog() {
        val cat = com.example.callguardian.integrations.IntegrationCatalog(TestAssets::read)
        val jev = cat.byId("jev")!!
        assertFalse(jev.defaultEnabled)
    }
}
