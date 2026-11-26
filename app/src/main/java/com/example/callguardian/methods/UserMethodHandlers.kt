package com.example.callguardian.methods

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.example.callguardian.data.db.AiStatus
import com.example.callguardian.data.db.ScreeningEventDao
import com.example.callguardian.data.db.ScreeningEventEntity
import com.example.callguardian.integrations.IntegrationPrefs
import com.example.callguardian.jev.JevClient
import com.example.callguardian.methods.gen.GuardianCallReviewV1Input
import com.example.callguardian.methods.gen.GuardianCallReviewV1Output
import com.example.callguardian.methods.gen.GuardianIntegrationSetV1Input
import com.example.callguardian.methods.gen.GuardianIntegrationSetV1Output
import com.example.callguardian.methods.gen.GuardianJevCheckV1Input
import com.example.callguardian.methods.gen.GuardianJevCheckV1Output
import com.example.callguardian.methods.gen.GuardianSmsManage_subscriptionV1Input
import com.example.callguardian.methods.gen.GuardianSmsManage_subscriptionV1Output
import com.example.callguardian.methods.gen.GuardianSmsMuteV1Input
import com.example.callguardian.methods.gen.GuardianSmsMuteV1Output
import com.example.callguardian.methods.gen.GuardianSmsUnsubscribeV1Input
import com.example.callguardian.methods.gen.GuardianSmsUnsubscribeV1Output
import com.example.callguardian.model.Channel
import com.example.callguardian.model.MessageSource
import com.example.callguardian.screening.HeuristicScanner
import com.example.callguardian.screening.ScanInput
import com.example.callguardian.subscriptions.SubscriptionPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bodies of the user-only methods (registry `"proposable": false`). Each takes the generated input
 * type and returns the generated output type; reachable only through [MethodDispatcher.dispatchUser],
 * i.e. after the user confirmed a schema-generated sheet.
 */
@Singleton
class UserMethodHandlers @Inject constructor(
    @ApplicationContext private val context: Context,
    private val events: ScreeningEventDao,
    private val jev: JevClient,
    private val subs: SubscriptionPrefs,
    private val integrations: IntegrationPrefs,
) {
    private val json = MethodRegistry.json
    private fun <I> decode(s: KSerializer<I>, input: JsonObject): I = json.decodeFromJsonElement(s, input)
    private fun <O> out(s: KSerializer<O>, o: O): JsonElement = json.encodeToJsonElement(s, o)

    suspend fun execute(m: UserMethod, input: JsonObject): HandlerOutcome = when (m) {
        UserMethod.REVIEW_CALL -> reviewCall(decode(GuardianCallReviewV1Input.serializer(), input))
        UserMethod.JEV_CHECK -> jevCheck(decode(GuardianJevCheckV1Input.serializer(), input))
        UserMethod.UNSUBSCRIBE_SMS -> unsubscribe(decode(GuardianSmsUnsubscribeV1Input.serializer(), input))
        UserMethod.MANAGE_SUBSCRIPTION -> manage(decode(GuardianSmsManage_subscriptionV1Input.serializer(), input))
        UserMethod.MUTE_SENDER -> {
            val i = decode(GuardianSmsMuteV1Input.serializer(), input)
            subs.setMode(i.sender, i.mode.name)
            HandlerOutcome(out(GuardianSmsMuteV1Output.serializer(), GuardianSmsMuteV1Output(i.sender, GuardianSmsMuteV1Output.Mode.valueOf(i.mode.name), System.currentTimeMillis())),
                notes = listOf("Saved on this phone only."))
        }
        UserMethod.SET_INTEGRATION -> {
            val i = decode(GuardianIntegrationSetV1Input.serializer(), input)
            val prev = integrations.enabled(i.integration.name)
            integrations.set(i.integration.name, i.enabled)
            HandlerOutcome(out(GuardianIntegrationSetV1Output.serializer(), GuardianIntegrationSetV1Output(
                GuardianIntegrationSetV1Output.Integration.valueOf(i.integration.name), i.enabled, prev, System.currentTimeMillis())))
        }
    }

    /** Call-log review: rules on the call's metadata now; Gemini Nano 4 runs next (ViewModel) if asked. No audio, nothing leaves the phone. */
    private suspend fun reviewCall(i: GuardianCallReviewV1Input): HandlerOutcome {
        val number = i.number.takeIf { it != "hidden" }
        val scan = HeuristicScanner.scan(ScanInput(Channel.CALL, number, isContact = i.isContact, callerIdHidden = number == null,
            recentCallsFromNumber = i.callsWithin15Min.toInt()))
        val facts = listOfNotNull(
            "call log: ${i.callType.name.lowercase()}", "${i.durationSec} s",
            "${i.callsFromNumber30d} call(s) from this number in 30 days",
            i.callsWithin15Min.takeIf { it > 0 }?.let { "$it other call(s) in the 15 minutes before" },
            i.cachedName?.let { "caller ID name \"$it\"" },
        ).joinToString(" · ")
        val id = events.insert(ScreeningEventEntity(
            channel = Channel.CALL.name, number = number, displayName = i.cachedName, isContact = i.isContact, isTest = true,
            source = MessageSource.CALL_LOG.wire, receivedAt = i.callAt, lookupSummary = facts,
            rulesScore = scan.score, rulesRisk = scan.riskLevel.name, rulesFlags = scan.reasons.joinToString("\n"), rulesMicros = scan.elapsedMicros,
            aiStatus = if (i.runOnDevice) AiStatus.PENDING else AiStatus.NOT_RUN,
            risk = scan.riskLevel.name, category = scan.category.name, confidence = scan.score, reasons = scan.reasons.joinToString("\n"),
            gateNotes = "Call-log review: nothing runs automatically. Actions below need your tap.",
        ))
        return HandlerOutcome(out(GuardianCallReviewV1Output.serializer(), GuardianCallReviewV1Output(
            id, GuardianCallReviewV1Output.RulesRisk.valueOf(scan.riskLevel.name), scan.score.coerceIn(0, 100),
            scan.reasons.take(6).map { it.take(120) }, i.runOnDevice)))
    }

    private suspend fun jevCheck(i: GuardianJevCheckV1Input): HandlerOutcome {
        if (!integrations.enabled("jev")) error("Jev second opinion is off (Settings → Integrations).")
        val e = events.get(i.eventId) ?: error("Event ${i.eventId} not found")
        val a = jev.check(i.redactedText, i.sender.name)
        val o = UserMethodInputs.jevOutput(i.eventId, a.signals, e.isContact, a.model, a.questionsVersion, a.inputTokens, a.latencyMs)
        val typed = json.decodeFromJsonElement(GuardianJevCheckV1Output.serializer(), o)
        return HandlerOutcome(out(GuardianJevCheckV1Output.serializer(), typed), notes = listOf("Verdict from JevTruthTable (yes ≥ ${com.example.callguardian.jev.JevTruthTable.YES}, no ≤ ${com.example.callguardian.jev.JevTruthTable.NO})."))
    }

    @Suppress("DEPRECATION")
    private suspend fun unsubscribe(i: GuardianSmsUnsubscribeV1Input): HandlerOutcome {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            error("SEND_SMS permission not granted")
        val sms = if (android.os.Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sms.sendTextMessage(i.recipient, null, i.keyword.name, null, null)
        subs.markUnsubscribed(i.recipient)
        return HandlerOutcome(out(GuardianSmsUnsubscribeV1Output.serializer(), GuardianSmsUnsubscribeV1Output(
            i.recipient, GuardianSmsUnsubscribeV1Output.Keyword.valueOf(i.keyword.name), true, System.currentTimeMillis())),
            notes = listOf("Sent \"${i.keyword.name}\" to ${i.recipient} after your confirmation."))
    }

    private fun manage(i: GuardianSmsManage_subscriptionV1Input): HandlerOutcome {
        if (i.suspicious && !i.acknowledgeWarning) error("This link looks suspicious. Tick \"I understand the warning\" to open it anyway.")
        val uri = Uri.parse(i.url)
        require(uri.scheme == "https" || uri.scheme == "http") { "Only web links can be opened" }
        require(uri.host?.removePrefix("www.")?.equals(i.domain, true) == true) { "Link domain changed" }
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return HandlerOutcome(out(GuardianSmsManage_subscriptionV1Output.serializer(), GuardianSmsManage_subscriptionV1Output(i.url, i.domain, true, System.currentTimeMillis())),
            launch = intent, notes = listOf("Opened in the browser. You unsubscribe on the page yourself; nothing is automated.") + listOfNotNull(i.warning?.let { "Warning shown: $it" }))
    }
}
