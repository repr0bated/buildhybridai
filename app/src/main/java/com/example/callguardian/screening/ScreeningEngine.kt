package com.example.callguardian.screening

import com.example.callguardian.sms.QuarantineInbox
import com.example.callguardian.accounts.OwnedStore
import com.example.callguardian.accounts.SenderIds

import android.content.Context
import com.example.callguardian.ai.HybridScamAnalyzer
import com.example.callguardian.ai.Prompts
import com.example.callguardian.ai.ScamVerdict
import com.example.callguardian.data.db.AiStatus
import com.example.callguardian.data.db.PhoneProfileDao
import com.example.callguardian.data.db.ScreeningEventDao
import com.example.callguardian.data.db.ScreeningEventEntity
import com.example.callguardian.data.settings.LookupPreferences
import com.example.callguardian.firebase.FirebaseBootstrap
import com.example.callguardian.lookup.ReverseLookupManager
import com.example.callguardian.methods.BindingContext
import com.example.callguardian.methods.CapabilityPolicy
import com.example.callguardian.methods.Dispatch
import com.example.callguardian.methods.DispatchResult
import com.example.callguardian.methods.HandlerExtras
import com.example.callguardian.methods.MethodBinder
import com.example.callguardian.methods.MethodDispatcher
import com.example.callguardian.methods.MethodProposal
import com.example.callguardian.methods.PolicyContext
import com.example.callguardian.methods.PolicyDecision
import com.example.callguardian.model.Channel
import com.example.callguardian.model.GuardianMethod
import com.example.callguardian.model.MessageSource
import com.example.callguardian.model.RiskLevel
import com.example.callguardian.model.ScamCategory
import com.example.callguardian.model.VerdictSource
import com.example.callguardian.notifications.AlertNotifier
import com.example.callguardian.policy.SchemaValidator
import com.example.callguardian.service.ContactLookupService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class CallDecision(val reject: Boolean, val silence: Boolean, val reason: String)

/**
 * The three-tier pipeline:
 *  1. Rules (instant, offline, background-safe) — always.
 *  2. On-device model via Firebase AI Logic (Gemma 4 / Gemini Nano 4) — private, offline-capable.
 *  3. Cloud Gemini (`guardian.cloud.explain@v1`) — only when the user asks, with redacted input.
 *
 * Every tier ends the same way: it may only *propose* one registry method. The proposal passes
 * JSON Schema validation, the [CapabilityPolicy], and the method's input schema, and then runs
 * through [MethodDispatcher], which writes a receipt.
 */
@Singleton
class ScreeningEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val events: ScreeningEventDao,
    private val profiles: PhoneProfileDao,
    private val prefs: LookupPreferences,
    private val contacts: ContactLookupService,
    private val lookup: ReverseLookupManager,
    private val analyzer: HybridScamAnalyzer,
    private val notifier: AlertNotifier,
    private val policy: CapabilityPolicy,
    private val dispatcher: MethodDispatcher,
    private val io: CoroutineDispatcher,
    private val subscriptionPrefs: com.example.callguardian.subscriptions.SubscriptionPrefs,
    private val integrationPrefs: com.example.callguardian.integrations.IntegrationPrefs,
    private val quarantineInbox: QuarantineInbox,
    private val ownedStore: OwnedStore,
    private val subscriptionStore: com.example.callguardian.subscriptions.SubscriptionStore,
) {
    private val json = Json { ignoreUnknownKeys = false }
    val verdictValidator by lazy { SchemaValidator(asset("schemas/scam_verdict.schema.json"), "scam_verdict.schema.json") }
    private val aiMutex = Mutex() // AICore enforces per-app quotas; serialize requests.

    private fun asset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }

    fun normalize(number: String?): String? = number?.trim()?.let { n ->
        if (n.contains('@')) n else n.filter { it.isDigit() || it == '+' }.ifBlank { null }
    }


    private suspend fun profileFor(number: String?) = number?.let { n ->
        profiles.get(n) ?: n.let { SenderIds.normalize(it) }.takeIf { it != n }?.let { profiles.get(it) }
    }

    private suspend fun historyCount(number: String?): Int {
        if (number.isNullOrBlank()) return 0
        val a = events.countByNumber(number)
        val norm = SenderIds.normalize(number)
        val b = if (norm != number) events.countByNumber(norm) else 0
        return maxOf(a, b)
    }

    private suspend fun scanInput(channel: Channel, number: String?, body: String?, isContact: Boolean, extra: ScanInput.() -> ScanInput = { this }): ScanInput {
        val owned = ownedStore.matchSender(number)
        val trusted = profileFor(number)?.trusted == true
        val shortHist = SenderIds.isShortCode(number) && historyCount(number) >= 1
        val sub = subscriptionStore.bySender(number)
        return ScanInput(channel, number, body, isContact = isContact, isTrusted = trusted, shortCodeHistory = shortHist, owned = owned, allOwned = ownedStore.snapshot, subscriptionStatus = sub?.status).extra()
    }

    private fun senderFeatureLine(input: ScanInput, number: String?): String {
        val f = VerdictCalibrator.features(input)
        return listOfNotNull(
            number?.let { "number $it" },
            if (SenderIds.isShortCode(number)) "short code" else null,
            if (input.isContact) "saved contact" else "not in contacts",
            if (input.isTrusted) "trusted" else null,
            if (input.shortCodeHistory) "known short code with prior history" else null,
            input.owned?.let { "owned account ${it.service}" },
            f.officialHost?.let { "official link $it" },
            f.lookalikeHost?.let { "look-alike link $it" },
        ).joinToString("; ")
    }

    private fun linkDomainLine(body: String?): String? {
        val hosts = OfficialDomains.hostsIn(body.orEmpty())
        if (hosts.isEmpty()) return null
        return hosts.joinToString(", ") { h ->
            when {
                OfficialDomains.isAnyOfficialHost(h) -> "$h (official)"
                OfficialDomains.mentionedBrands(body.orEmpty()).any { OfficialDomains.isLookalike(h, it) } -> "$h (look-alike)"
                else -> h
            }
        }
    }

    private fun ownedListLine(): String? {
        val list = ownedStore.snapshot
        if (list.isEmpty()) return null
        return list.joinToString("; ") { a ->
            val senders = a.senders.joinToString("/").ifBlank { "no senders yet" }
            val domains = a.domains.joinToString("/").ifBlank { "no domains yet" }
            "${a.service} [senders $senders, domains $domains]"
        }
    }

    // ---------------------------------------------------------------- SMS

    suspend fun screenSms(
        rawNumber: String?, body: String, isTest: Boolean = false, runAi: Boolean = true,
        source: MessageSource = if (isTest) MessageSource.LAB_SAMPLE else MessageSource.SMS, senderName: String? = null,
        integration: String? = if (source == MessageSource.SMS && !isTest) "phone_sms" else null,
        smsUri: String? = null,
        applyActions: Boolean = true,
    ): Long = withContext(io) {
        // Notification sources (Google Voice, WhatsApp) may give a name instead of a number.
        val number = normalize(rawNumber)?.takeIf { it.contains('@') || it.trimStart('+').length >= 3 }
        val muted = (number ?: senderName)?.let { it in subscriptionPrefs.current().muted } == true ||
            rawNumber?.trim()?.let { it in subscriptionPrefs.current().muted } == true
        val contact = number?.let { runCatching { contacts.lookupContact(it) }.getOrNull() }
        val profile = profileFor(number)
        val input = scanInput(Channel.SMS, number, body, isContact = contact != null)
        val scan = HeuristicScanner.scan(input)
        val cal = VerdictCalibrator.fuse(scan, VerdictCalibrator.features(input), channel = Channel.SMS)
        val blocked = profile?.shouldBlockMessages() == true
        val wantsAi = runAi && !(cal.band == VerdictCalibrator.Band.CLEAR && (input.owned != null || cal.codeLabel != null)) && (cal.risk >= RiskLevel.LOW || contact == null)

        val id = events.insert(ScreeningEventEntity(
            channel = Channel.SMS.name, number = number, displayName = contact?.displayName ?: profile?.displayName ?: senderName,
            body = body, isContact = contact != null, isTest = isTest, source = source.wire, integration = integration,
            rulesScore = scan.score, rulesRisk = scan.riskLevel.name, rulesFlags = scan.reasons.joinToString("\n"), rulesMicros = scan.elapsedMicros,
            aiStatus = if (wantsAi) AiStatus.PENDING else AiStatus.NOT_RUN,
            risk = (if (blocked) maxOf(cal.risk, RiskLevel.HIGH) else cal.risk).name, category = cal.category.name,
            confidence = cal.confidence, reasons = cal.reasons.joinToString("\n"), advice = cal.advice,
            userAction = if (blocked) "BLOCKED_BY_LIST" else null, quarantined = blocked, smsUri = smsUri,
        ))
        if (id <= 0L) return@withContext 0L
        if (com.example.callguardian.subscriptions.UnsubscribeConfirmation.matches(body)) {
            val rec = subscriptionStore.bySender(number)
            if (rec != null && rec.status != com.example.callguardian.subscriptions.SubscriptionStatus.CANCELED) {
                subscriptionStore.markUnsubscribed(number ?: rec.sender, eventId = id, reason = "Unsubscribe confirmation text")
            }
        }
        if (applyActions && !blocked) decideAndDispatch(id, MethodProposal(cal.method, cal.risk, cal.confidence, cal.reasons, VerdictSource.RULES))
        val saved = events.get(id) ?: return@withContext id
        if (blocked) quarantineInbox.hide(saved)
        val alert = !isTest && !muted
        if (alert) notifier.notifyEvent(saved, aiPending = wantsAi)
        if (wantsAi) {
            // Never call Nano from a receiver: analyzeOnDevice routes to cloud or queues Unprocessed.
            analyzeOnDevice(id, fromBackground = !AppForeground.isResumed, notify = alert)
        }
        id
    }

    /** A new message read from another app's notification (Google Voice, WhatsApp…): same pipeline, typed source. */
    suspend fun screenNotificationMessage(integrationId: String, source: MessageSource, sender: String, conversation: String?, text: String): Long? {
        if (!integrationPrefs.enabled(integrationId)) return null
        integrationPrefs.seen(integrationId)
        val digits = sender.filter { it.isDigit() || it == '+' }
        val looksLikeNumber = digits.trimStart('+').length >= 7 && sender.count { it.isLetter() } == 0
        val label = if (conversation != null && conversation != sender) "$sender in $conversation" else sender
        return screenSms(if (looksLikeNumber) digits else null, text, source = source, senderName = if (looksLikeNumber) null else label.take(80), integration = integrationId)
    }

    // ---------------------------------------------------------------- subscriptions (on-device confirm)

    private val subscriptionValidator by lazy { SchemaValidator(asset("schemas/subscription_check.schema.json"), "subscription_check.schema.json") }

    data class SubscriptionConfirm(val isSubscription: Boolean, val link: com.example.callguardian.subscriptions.LinkCandidate?, val model: String?, val service: String? = null)

    /** Gemini Nano 4 confirms a rule-detected subscription sender and picks the unsubscribe link. ONLY_ON_DEVICE. */
    suspend fun confirmSubscription(g: com.example.callguardian.subscriptions.SubscriptionGroup, recent: List<String>): SubscriptionConfirm? = withContext(io) {
        if (!AppForeground.isResumed) return@withContext null
        val run = runCatching {
            aiMutex.withLock { withTimeout(20_000) { analyzer.subscriptionCheck(Prompts.subscription(g.service, g.senders, g.source.label, recent, g.links.map { it.url }), prefs.current().modelChoice) } }
        }.getOrElse { Timber.w(it, "Subscription check failed"); return@withContext null }
        val report = subscriptionValidator.validate(run.rawText)
        if (!report.valid) return@withContext null
        val c = runCatching { json.decodeFromString(com.example.callguardian.ai.SubscriptionCheck.serializer(), report.json) }.getOrNull() ?: return@withContext null
        SubscriptionConfirm(c.isSubscription, g.links.getOrNull(c.unsubscribeLink - 1), run.modelName, c.service.trim().takeIf { it.isNotEmpty() })
    }

    // ---------------------------------------------------------------- Calls

    /** Must finish well inside CallScreeningService's 5 s budget. Rules + lookup only. */
    suspend fun screenCall(rawNumber: String?, callerIdHidden: Boolean, startedAt: Long = System.currentTimeMillis()): Pair<CallDecision, Long> = withContext(io) {
        if (!integrationPrefs.enabled("phone_calls")) return@withContext CallDecision(false, false, "Calls integration is off") to -1L
        integrationPrefs.seen("phone_calls")
        val number = normalize(rawNumber)
        val profile = number?.let { profiles.get(it) }
        val contact = number?.let { runCatching { contacts.lookupContact(it) }.getOrNull() }
        val settings = prefs.current()

        if (profile?.shouldBlockCalls() == true) {
            val id = events.insert(ScreeningEventEntity(
                channel = Channel.CALL.name, number = number, displayName = profile.displayName, risk = RiskLevel.HIGH.name, source = MessageSource.CALL_SCREENING.wire,
                proposedAction = GuardianMethod.REJECT_CALL.alias, finalAction = GuardianMethod.REJECT_CALL.alias, policyDispatch = Dispatch.AUTO.name,
                autoApplied = true, gateNotes = "Sender is on your block list.", userAction = "BLOCKED_BY_LIST",
            ))
            dispatcher.dispatch(GuardianMethod.REJECT_CALL,
                MethodBinder.bind(GuardianMethod.REJECT_CALL, BindingContext(id, Channel.CALL, number, profile.displayName, RiskLevel.CRITICAL, ScamCategory.NONE, listOf("On your block list"))),
                id, VerdictSource.RULES, Dispatch.AUTO.name, listOf("Sender is on your block list."), HandlerExtras(System.currentTimeMillis() - startedAt))
            return@withContext CallDecision(true, false, "On your block list") to id
        }

        val lookupResult = if (contact == null && number != null && settings.anyLookupConfigured) lookup.lookup(number, 1_500) else null
        val recent = number?.let { events.countCallsSince(it, System.currentTimeMillis() - 15 * 60_000) } ?: 0
        val scan = HeuristicScanner.scan(ScanInput(
            channel = Channel.CALL, number = number, isContact = contact != null, callerIdHidden = callerIdHidden,
            lookupSpamScore = lookupResult?.spamScore, lookupLineType = lookupResult?.lineType,
            lookupTags = lookupResult?.tags.orEmpty(), recentCallsFromNumber = recent,
        ))
        val summary = lookupResult?.let { listOfNotNull(it.displayName, it.carrier, it.region, it.lineType, it.spamScore?.let { s -> "spam score $s" }).joinToString(" · ") }
        val wantsAi = contact == null && scan.riskLevel >= RiskLevel.LOW
        val id = events.insert(ScreeningEventEntity(
            channel = Channel.CALL.name, number = number, displayName = contact?.displayName ?: lookupResult?.displayName,
            isContact = contact != null, lookupSummary = summary, source = MessageSource.CALL_SCREENING.wire,
            rulesScore = scan.score, rulesRisk = scan.riskLevel.name, rulesFlags = scan.reasons.joinToString("\n"), rulesMicros = scan.elapsedMicros,
            aiStatus = if (wantsAi) AiStatus.PENDING else AiStatus.NOT_RUN,
            risk = scan.riskLevel.name, category = scan.category.name, confidence = scan.score, reasons = scan.reasons.joinToString("\n"),
        ))
        var proposal = MethodProposal(scan.method, scan.riskLevel, scan.score, scan.reasons, VerdictSource.RULES)
        if (proposal.method == GuardianMethod.REJECT_CALL && !settings.silenceSuspiciousCalls) proposal = proposal.copy(method = GuardianMethod.NONE)
        val results = decideAndDispatch(id, proposal, HandlerExtras(System.currentTimeMillis() - startedAt))
        val e = events.get(id)!!
        val blockedNow = results.any { it.first == GuardianMethod.BLOCK_NUMBER && it.second.ok }
        val rejectOut = results.firstOrNull { it.first == GuardianMethod.REJECT_CALL && it.second.ok }?.second?.output?.toString()
        val reject = blockedNow || rejectOut?.contains("\"REJECT\"") == true
        val silence = !reject && rejectOut?.contains("\"SILENCE\"") == true
        CallDecision(reject, silence, e.gateNotes.lineSequence().firstOrNull().orEmpty()) to id
    }

    suspend fun afterCall(id: Long) {
        val e = events.get(id) ?: return
        if (e.isContact && e.finalAction == GuardianMethod.NONE.alias) return
        if (RiskLevel.valueOf(e.risk) >= RiskLevel.LOW || e.userAction == "BLOCKED_BY_LIST") notifier.notifyEvent(e, aiPending = e.aiStatus == AiStatus.PENDING)
    }

    // ---------------------------------------------------------------- policy + dispatch

    private suspend fun policyContext(e: ScreeningEventEntity): PolicyContext {
        val s = prefs.current()
        val trusted = e.number?.let { profiles.get(it)?.trusted } == true
        return PolicyContext(Channel.valueOf(e.channel), e.isContact, trusted, s.autoBlock, s.autoBlockThreshold, FirebaseBootstrap.cloudConfigured)
    }

    fun binding(e: ScreeningEventEntity) = BindingContext(
        eventId = e.id, channel = Channel.valueOf(e.channel), number = e.number, displayName = e.displayName,
        risk = runCatching { RiskLevel.valueOf(e.risk) }.getOrDefault(RiskLevel.SAFE),
        category = runCatching { ScamCategory.valueOf(e.category) }.getOrDefault(ScamCategory.NONE),
        reasons = e.reasons.lines().filter { it.isNotBlank() },
        redactedText = redactedPreview(e),
        onDeviceOpinion = if (e.aiStatus == AiStatus.DONE) "${e.risk}, ${e.category}, confidence ${e.confidence}" else null,
    )

    /** Policy-check a proposal, persist the decision, auto-run AUTO methods, record DENY receipts. */
    private suspend fun decideAndDispatch(id: Long, p: MethodProposal, extras: HandlerExtras = HandlerExtras()): List<Pair<GuardianMethod, DispatchResult>> {
        val e = events.get(id) ?: return emptyList()
        val d: PolicyDecision = policy.decide(p, policyContext(e))
        events.update(e.copy(
            decidedBy = p.source.name, proposedAction = d.proposed.alias, finalAction = d.method.alias,
            policyDispatch = d.dispatch.name, gateNotes = d.notes.joinToString("\n"), autoApplied = d.dispatch == Dispatch.AUTO,
        ))
        val ctx = binding(events.get(id)!!)
        val results = mutableListOf<Pair<GuardianMethod, DispatchResult>>()
        if (d.proposed != GuardianMethod.NONE && (d.dispatch == Dispatch.DENY || d.substituted)) {
            dispatcher.recordDenied(d.proposed, MethodBinder.bind(d.proposed, ctx), id, p.source, d.notes)
        }
        val canAuto = d.dispatch == Dispatch.AUTO && d.method != GuardianMethod.NONE && (!e.isTest || d.method.isSafeForTests())
        if (canAuto) {
            results += d.method to dispatcher.dispatch(d.method, MethodBinder.bind(d.method, ctx), id, p.source, Dispatch.AUTO.name, d.notes, extras)
        }
        return results
    }

    /** Test-lab events must not block real numbers. */
    private fun GuardianMethod.isSafeForTests() = this == GuardianMethod.QUARANTINE_SMS

    /** A user-confirmed dispatch from the schema-rendered confirmation sheet. */
    suspend fun runUserMethod(eventId: Long?, method: GuardianMethod, input: JsonObject): DispatchResult = withContext(io) {
        val r = dispatcher.dispatch(method, input, eventId, VerdictSource.USER, "USER_CONFIRMED")
        if (r.ok && eventId != null) {
            events.get(eventId)?.let { e ->
                events.update(e.copy(userAction = method.alias))
                if (method != GuardianMethod.EXPLAIN_CLOUD) notifier.cancel(eventId)
            }
        }
        r
    }

    /** Policy view of a proposal without dispatching (used to gate the cloud's suggested method). */
    suspend fun evaluate(e: ScreeningEventEntity, method: GuardianMethod, source: VerdictSource): PolicyDecision =
        policy.decide(MethodProposal(method, runCatching { RiskLevel.valueOf(e.risk) }.getOrDefault(RiskLevel.SAFE), e.confidence,
            e.reasons.lines().filter { it.isNotBlank() }, source), policyContext(e))

    suspend fun runUserMethodForEvent(eventId: Long, method: GuardianMethod): DispatchResult? {
        val e = events.get(eventId) ?: return null
        return runUserMethod(eventId, method, defaultInput(e, method))
    }

    /** List maintenance from Settings (not a model-callable method). */
    suspend fun unlist(number: String) = withContext(io) {
        profiles.get(number)?.let { profiles.upsert(it.copy(blockMode = com.example.callguardian.model.BlockMode.NONE.name, trusted = false, watched = false, updatedAt = System.currentTimeMillis())) }
    }

    fun defaultInput(e: ScreeningEventEntity, method: GuardianMethod): JsonObject = MethodBinder.bind(method, binding(e))

    // ---------------------------------------------------------------- Tier 2: on-device

    suspend fun analyzeOnDevice(id: Long, fromBackground: Boolean = false, notify: Boolean = true, applyActions: Boolean = true): ScreeningEventEntity? = withContext(io) {
        val e = events.get(id) ?: return@withContext null
        val settings = prefs.current()
        val channel = Channel.valueOf(e.channel)
        val input = scanInput(channel, e.number, e.body, e.isContact)
        val rules = HeuristicScanner.scan(input)
        val sender = e.displayName ?: e.number ?: "hidden number"
        val prompt = Prompts.triage(
            channel, sender, e.isContact, e.body, e.lookupSummary, rules,
            senderFeatures = senderFeatureLine(input, e.number),
            linkDomains = linkDomainLine(e.body),
            ownedList = ownedListLine(),
        )

        val route = NanoPolicy.route(AppForeground.isResumed, settings.privateMode)
        if (route == NanoPolicy.Route.QUEUE) {
            return@withContext queueUnprocessed(id, NanoPolicy.WAITING_FOR_APP)
        }
        events.update(e.copy(aiStatus = AiStatus.RUNNING, aiError = null))
        val run = run {
            var last: Throwable? = null
            var result: com.example.callguardian.ai.ModelRun? = null
            // Serialize (aiMutex) and back off on ErrorCode 9 / cloud 429. Pause immediately if we leave RESUMED.
            for (attempt in 0..2) {
                if (NanoPolicy.shouldPauseDrain(AppForeground.isResumed) && route != NanoPolicy.Route.CLOUD) {
                    return@withContext queueUnprocessed(id, NanoPolicy.quietStatus(last))
                }
                if (attempt > 0) kotlinx.coroutines.delay(NanoPolicy.nextBackoffMs(NanoPolicy.MIN_BACKOFF_MS, failed = true) * attempt)
                try {
                    result = aiMutex.withLock { withTimeout(25_000) { analyzer.triage(prompt, settings.modelChoice, settings.privateMode) } }
                    last = null
                    break
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException && t !is TimeoutCancellationException) throw t
                    last = t
                    Timber.w(t, "Triage failed (attempt %s)", attempt + 1)
                    val retry = NanoPolicy.isError9(t) || NanoPolicy.isCloudRateLimited(t) || t is TimeoutCancellationException
                    if (!retry) break
                    if (NanoPolicy.shouldPauseDrain(AppForeground.isResumed)) {
                        return@withContext queueUnprocessed(id, NanoPolicy.quietStatus(t))
                    }
                }
            }
            if (result != null) result
            else {
                val t = last ?: IllegalStateException("inference failed")
                if (NanoPolicy.isError9(t) || NanoPolicy.isCloudRateLimited(t) || !AppForeground.isResumed) {
                    return@withContext queueUnprocessed(id, NanoPolicy.quietStatus(t))
                }
                val msg = com.example.callguardian.ai.HybridScamAnalyzer.friendlyError(t, background = false)
                val updated = events.get(id)!!.copy(aiStatus = AiStatus.FAILED, aiError = msg)
                events.update(updated)
                return@withContext updated
            }
        }

        val filled = VerdictFields.fillOmitted(
            run.rawText,
            VerdictFields.localReputableOfficial(e.body),
            VerdictFields.localHeldAccount(
                isContact = e.isContact,
                ownedSender = ownedStore.matchSender(e.number) != null,
                ownedDomain = VerdictFields.ownedDomainMatch(e.body, ownedStore.snapshot),
            ),
        )
        val report = verdictValidator.validate(filled)
        val base = events.get(id)!!.copy(
            aiSource = run.source.name, aiModel = run.modelName, aiRaw = report.json.ifBlank { run.rawText }, aiLatencyMs = run.latencyMs,
            schemaValid = report.valid, schemaErrors = report.errors.joinToString("\n").ifBlank { null }, aiError = run.fallbackNote,
        )
        val verdict = if (report.valid) runCatching { json.decodeFromString(ScamVerdict.serializer(), report.json) }.getOrNull() else null
        if (verdict == null) {
            // Invalid proposal: discarded entirely. The rules decision stands.
            val rejected = base.copy(aiStatus = AiStatus.REJECTED, schemaValid = false,
                gateNotes = (listOf("Model output failed ${report.schemaId}; proposal discarded, rules decision kept.") + base.gateNotes.lines()).joinToString("\n"))
            events.update(rejected)
            return@withContext rejected
        }
        val signals = VerdictCalibrator.modelSignals(verdict)
        val cal = VerdictCalibrator.fuse(
            rules, VerdictCalibrator.features(input),
            modelConfidence = verdict.confidenceInt, modelRisk = signals.risk,
            modelCategory = signals.category, modelMethod = signals.method,
            modelReasons = verdict.reasons, modelAdvice = signals.advice,
            modelVerdict = verdict.verdict,
            channel = channel,
        )
        events.update(base.copy(
            aiStatus = AiStatus.DONE, risk = cal.risk.name, category = cal.category.name, confidence = cal.confidence,
            reasons = cal.reasons.joinToString("\n"), advice = cal.advice,
        ))
        // Don't re-run a method the rules already executed for this event.
        val alreadyRan = e.autoApplied && e.finalAction == cal.method.alias
        if (applyActions && !alreadyRan) decideAndDispatch(id, MethodProposal(cal.method, cal.risk, cal.confidence, cal.reasons, run.source))
        val final = events.get(id) ?: return@withContext null
        if (notify && !e.isTest && (cal.risk >= RiskLevel.MEDIUM || final.policyDispatch != Dispatch.DENY.name)) notifier.notifyEvent(final, aiPending = false)
        else if (notify && !e.isTest) notifier.cancel(id)
        final
    }


    /** Rules-only review of a historical call-log row. Does not dispatch actions. */
    suspend fun reviewCallLog(
        number: String?, cachedName: String?, isContact: Boolean, callType: String,
        durationSec: Long, callAt: Long, callsFromNumber30d: Int, callsWithin15Min: Int,
    ): Long = withContext(io) {
        val scan = HeuristicScanner.scan(ScanInput(Channel.CALL, number, isContact = isContact, callerIdHidden = number == null,
            recentCallsFromNumber = callsWithin15Min.toInt()))
        val facts = listOfNotNull(
            "call log: ${callType.lowercase()}", "${durationSec} s",
            "$callsFromNumber30d call(s) from this number in 30 days",
            callsWithin15Min.takeIf { it > 0 }?.let { "$it other call(s) in the 15 minutes before" },
            cachedName?.let { "caller ID name \"$it\"" },
        ).joinToString(" · ")
        events.insert(ScreeningEventEntity(
            channel = Channel.CALL.name, number = number, displayName = cachedName, isContact = isContact, isTest = true,
            source = MessageSource.CALL_LOG.wire, receivedAt = callAt, lookupSummary = facts,
            rulesScore = scan.score, rulesRisk = scan.riskLevel.name, rulesFlags = scan.reasons.joinToString("\n"), rulesMicros = scan.elapsedMicros,
            aiStatus = AiStatus.PENDING,
            risk = scan.riskLevel.name, category = scan.category.name, confidence = scan.score, reasons = scan.reasons.joinToString("\n"),
            gateNotes = "Process all / call-log review: analysis only; no action is taken.",
        ))
    }

    private suspend fun queueUnprocessed(id: Long, status: String): ScreeningEventEntity? {
        val updated = events.get(id)?.copy(aiStatus = AiStatus.UNPROCESSED, aiError = status) ?: return null
        events.update(updated)
        return updated
    }

    suspend fun processPending() = drainUnprocessed()

    /**
     * Process queued (PENDING / UNPROCESSED) events while the app is RESUMED.
     * Stops immediately when the process leaves the foreground. Serialized through
     * [aiMutex] with exponential backoff after ErrorCode 9 or a cloud 429.
     */
    suspend fun drainUnprocessed() = withContext(io) {
        if (NanoPolicy.shouldPauseDrain(AppForeground.isResumed)) return@withContext
        var backoff = NanoPolicy.MIN_BACKOFF_MS
        for (item in events.pendingAi()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (NanoPolicy.shouldPauseDrain(AppForeground.isResumed)) return@withContext
            kotlinx.coroutines.delay(backoff)
            if (NanoPolicy.shouldPauseDrain(AppForeground.isResumed)) return@withContext
            val out = analyzeOnDevice(item.id, fromBackground = false, notify = true)
            val failed = out == null || AiStatus.isQueued(out.aiStatus)
            backoff = NanoPolicy.nextBackoffMs(backoff, failed)
        }
    }

    // ---------------------------------------------------------------- Tier 3: cloud (explain_cloud)

    fun redactedPreview(e: ScreeningEventEntity): String = buildString {
        appendLine("Sender: ${Redactor.redactNumber(e.number)}")
        e.body?.let { appendLine(Redactor.redact(it)) }
        if (e.body == null && e.lookupSummary != null) appendLine("Lookup: ${Redactor.redact(e.lookupSummary)}")
    }.trim()
}
