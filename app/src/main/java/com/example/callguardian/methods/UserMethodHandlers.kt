package com.example.callguardian.methods

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.example.callguardian.data.db.AiStatus
import com.example.callguardian.accounts.OwnedStore
import com.example.callguardian.accounts.SenderIds
import com.example.callguardian.data.db.PhoneProfileDao
import com.example.callguardian.data.db.PhoneProfileEntity
import com.example.callguardian.methods.gen.GuardianAccountsMark_ownedV1Input
import com.example.callguardian.methods.gen.GuardianAccountsMark_ownedV1Output
import com.example.callguardian.methods.gen.GuardianAccountsUnmark_ownedV1Input
import com.example.callguardian.methods.gen.GuardianAccountsUnmark_ownedV1Output
import com.example.callguardian.methods.gen.GuardianFeedbackNot_scamV1Input
import com.example.callguardian.methods.gen.GuardianFeedbackNot_scamV1Output
import com.example.callguardian.screening.OfficialDomains
import com.example.callguardian.data.db.ScreeningEventDao
import com.example.callguardian.data.db.ScreeningEventEntity
import com.example.callguardian.integrations.IntegrationPrefs
import com.example.callguardian.privacy.PrivacyStore
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
import com.example.callguardian.methods.gen.GuardianPrivacySet_privateV1Input
import com.example.callguardian.methods.gen.GuardianPrivacySet_privateV1Output
import com.example.callguardian.methods.gen.GuardianPrivacyClear_privateV1Input
import com.example.callguardian.methods.gen.GuardianPrivacyClear_privateV1Output
import com.example.callguardian.model.Channel
import com.example.callguardian.model.MessageSource
import com.example.callguardian.screening.HeuristicScanner
import com.example.callguardian.screening.ScanInput
import com.example.callguardian.subscriptions.SubscriptionPrefs
import com.example.callguardian.subscriptions.SubscriptionStore
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
    private val privacy: PrivacyStore,
    private val owned: OwnedStore,
    private val profiles: PhoneProfileDao,
    private val subStore: SubscriptionStore,
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
            val targets = i.senders?.takeIf { it.isNotEmpty() } ?: listOf(i.sender)
            subs.setMode(targets, i.mode.name)
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
        UserMethod.SET_PRIVATE -> {
            val i = decode(GuardianPrivacySet_privateV1Input.serializer(), input)
            val key = privacy.set(i.kind.name, i.eventId, i.address, i.callLogId)
            HandlerOutcome(out(GuardianPrivacySet_privateV1Output.serializer(), GuardianPrivacySet_privateV1Output(
                GuardianPrivacySet_privateV1Output.Kind.valueOf(i.kind.name), key, true, System.currentTimeMillis())),
                notes = listOf("Hidden on this phone. Unlock with fingerprint, face or your device PIN to see it."))
        }
        UserMethod.CLEAR_PRIVATE -> {
            val i = decode(GuardianPrivacyClear_privateV1Input.serializer(), input)
            val key = privacy.clear(i.kind.name, i.eventId, i.address, i.callLogId)
            HandlerOutcome(out(GuardianPrivacyClear_privateV1Output.serializer(), GuardianPrivacyClear_privateV1Output(
                GuardianPrivacyClear_privateV1Output.Kind.valueOf(i.kind.name), key, false, System.currentTimeMillis())),
                notes = listOf("This item is visible again."))
        }
        UserMethod.MARK_OWNED -> markOwned(decode(GuardianAccountsMark_ownedV1Input.serializer(), input))
        UserMethod.UNMARK_OWNED -> unmarkOwned(decode(GuardianAccountsUnmark_ownedV1Input.serializer(), input))
        UserMethod.NOT_SCAM -> notScam(decode(GuardianFeedbackNot_scamV1Input.serializer(), input))
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
        val now = System.currentTimeMillis()
        val results = i.recipients.map { recipient ->
            val queued = runCatching { sms.sendTextMessage(recipient, null, i.keyword.name, null, null) }.isSuccess
            GuardianSmsUnsubscribeV1Output.Result(recipient, queued)
        }
        val allQueued = results.all { it.queued }
        return HandlerOutcome(out(GuardianSmsUnsubscribeV1Output.serializer(), GuardianSmsUnsubscribeV1Output(
            i.service, i.recipients.toList(), results, GuardianSmsUnsubscribeV1Output.Keyword.valueOf(i.keyword.name), allQueued, now)),
            notes = listOf("Sent \"${i.keyword.name}\" to ${i.service} (${results.size} number(s)) after your confirmation. Marked STOP_SENT until a confirmation text arrives.") +
                results.map { "${it.recipient}: ${if (it.queued) "queued" else "failed"}" },
            afterReceipt = { rid -> subStore.markStopSent(i.recipients.toList(), i.service, rid, i.keyword.name) })
    }

    private suspend fun manage(i: GuardianSmsManage_subscriptionV1Input): HandlerOutcome {
        if (i.suspicious && !i.acknowledgeWarning) error("This link looks suspicious. Tick \"I understand the warning\" to open it anyway.")
        val uri = Uri.parse(i.url)
        require(uri.scheme == "https" || uri.scheme == "http") { "Only web links can be opened" }
        require(uri.host?.removePrefix("www.")?.equals(i.domain, true) == true) { "Link domain changed" }
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return HandlerOutcome(out(GuardianSmsManage_subscriptionV1Output.serializer(), GuardianSmsManage_subscriptionV1Output(i.url, i.domain, true, System.currentTimeMillis())),
            launch = intent,
            notes = listOf("Opened in the browser. You unsubscribe on the page yourself; nothing is automated. We'll ask if you canceled when you come back. Canceling does not remove an Owned account unless you choose to.") + listOfNotNull(i.warning?.let { "Warning shown: $it" }),
            afterReceipt = { subStore.markManageOpened(i.sender, i.domain) })
    }

    private suspend fun markOwned(i: GuardianAccountsMark_ownedV1Input): HandlerOutcome {
        val acct = owned.upsert(i.service, i.senders.toList(), i.domains?.toList().orEmpty())
        return HandlerOutcome(out(GuardianAccountsMark_ownedV1Output.serializer(), GuardianAccountsMark_ownedV1Output(
            acct.id.coerceAtLeast(1), acct.service, acct.senders, acct.domains, acct.updatedAt)),
            notes = listOf("Saved ${acct.service} as an owned account on this phone."))
    }

    private suspend fun unmarkOwned(i: GuardianAccountsUnmark_ownedV1Input): HandlerOutcome {
        owned.delete(i.accountId)
        return HandlerOutcome(out(GuardianAccountsUnmark_ownedV1Output.serializer(), GuardianAccountsUnmark_ownedV1Output(
            i.accountId, i.service, true, System.currentTimeMillis())),
            notes = listOf("Removed ${i.service} from Owned accounts."))
    }

    private suspend fun notScam(i: GuardianFeedbackNot_scamV1Input): HandlerOutcome {
        val now = System.currentTimeMillis()
        val prev = profiles.get(i.number)
        profiles.upsert((prev ?: PhoneProfileEntity(phoneNumber = i.number)).copy(trusted = true, blockMode = com.example.callguardian.model.BlockMode.NONE.name, updatedAt = now))
        val ev = events.get(i.eventId)
        ev?.let { events.update(it.copy(userAction = "not_a_scam", risk = "SAFE", category = "NONE", confidence = 20,
            advice = "You marked this as not a scam. The sender is now trusted.",
            reasons = (listOf("User feedback: not a scam.") + it.reasons.lines()).distinct().joinToString("\n"))) }
        var ownedMarked = i.markOwned == true
        var service = i.service
        if (ownedMarked) {
            val hosts = i.domains?.toList().orEmpty().ifEmpty {
                OfficialDomains.hostsIn(ev?.body.orEmpty()).filter { OfficialDomains.isAnyOfficialHost(it) }
            }
            val name = service?.takeIf { it.isNotBlank() }
                ?: OfficialDomains.mentionedBrands(ev?.body.orEmpty()).firstOrNull()?.let { prettyBrand(it) }
                ?: ev?.displayName
                ?: "Account"
            service = name
            owned.upsert(name, listOf(i.number), hosts)
        }
        return HandlerOutcome(out(GuardianFeedbackNot_scamV1Output.serializer(), GuardianFeedbackNot_scamV1Output(
            i.eventId, true, true, ownedMarked, service, now)),
            notes = listOf("Recorded as not a scam. ${i.number} is now trusted.") +
                listOfNotNull(if (ownedMarked) "Also marked ${service ?: "the service"} as an owned account." else "You can also mark it as an owned account."))
    }

    private fun prettyBrand(key: String) = when (com.example.callguardian.screening.OfficialDomains.brandKey(key)) {
        "ezpass", "e-zpass" -> "E-ZPass"
        "cashapp", "cash-app" -> "Cash App"
        else -> key.replaceFirstChar { it.uppercase() }
    }
}
