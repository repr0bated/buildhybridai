package com.example.callguardian

import com.example.callguardian.jev.JevTruthTable
import com.example.callguardian.methods.UserMethod
import com.example.callguardian.methods.UserMethodInputs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays the recorded live Jev answers for the built-in demo samples (functions/fixtures/jev-samples.json,
 * written by `npm run probe`) through the app's truth table and the output schema.
 */
class JevLiveFixtureTest {
    private val file = listOf(File("../functions/fixtures/jev-samples.json"), File("functions/fixtures/jev-samples.json")).firstOrNull { it.exists() }

    @Test fun recordedSamplesGiveExpectedVerdicts() {
        assumeTrue("no live fixture", file != null)
        val results = Json.parseToJsonElement(file!!.readText()).jsonObject["results"]!!.jsonArray
        val validator = TestAssets.registry.byUser(UserMethod.JEV_CHECK)!!.outputValidator
        val verdicts = results.associate { r ->
            val o = r.jsonObject
            val signals = o["signals"]!!.jsonObject.mapValues { it.value.jsonPrimitive.double }
            val out = UserMethodInputs.jevOutput(1, signals, o["sender"]!!.jsonPrimitive.content == "saved_contact",
                o["model"]!!.jsonPrimitive.content, o["questionsVersion"]!!.jsonPrimitive.content, o["inputTokens"]!!.jsonPrimitive.int, 0)
            val v = validator.validate(out.toString()); assertTrue(v.errors.toString(), v.valid)
            val title = o["title"]!!.jsonPrimitive.content
            println("JEV  %-32s %-10s %s".format(title, out["verdict"]!!.jsonPrimitive.content, out["matchedRule"]!!.jsonPrimitive.content))
            title to JevTruthTable.Verdict.valueOf(out["verdict"]!!.jsonPrimitive.content)
        }
        listOf("Package redelivery", "Unpaid toll", "Bank lock + code", "Wrong-number crypto", "Job offer").forEach { assertEquals(it, JevTruthTable.Verdict.SCAM, verdicts[it]) }
        listOf("Legit reminder", "Newsletter (unsubscribe link)").forEach { assertEquals(it, JevTruthTable.Verdict.CLEAR, verdicts[it]) }
    }
}
