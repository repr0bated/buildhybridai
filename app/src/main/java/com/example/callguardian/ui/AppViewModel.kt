package com.example.callguardian.ui

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.callguardian.ai.DeviceModelState
import com.example.callguardian.ai.HybridScamAnalyzer
import com.example.callguardian.data.db.PhoneProfileDao
import com.example.callguardian.data.db.ScreeningEventDao
import com.example.callguardian.data.db.ScreeningEventEntity
import com.example.callguardian.data.settings.AppSettings
import com.example.callguardian.data.settings.LookupPreferences
import com.example.callguardian.data.settings.OnDeviceModelChoice
import com.example.callguardian.firebase.FirebaseBootstrap
import com.example.callguardian.data.db.ReceiptDao
import com.example.callguardian.data.db.ReceiptEntity
import com.example.callguardian.methods.DispatchResult
import com.example.callguardian.methods.MethodRegistry
import com.example.callguardian.methods.PolicyDecision
import com.example.callguardian.methods.RegisteredMethod
import com.example.callguardian.model.GuardianMethod
import com.example.callguardian.model.VerdictSource
import com.example.callguardian.schemaui.MethodHeader
import com.example.callguardian.schemaui.SchemaForm
import com.example.callguardian.screening.ScreeningEngine
import com.example.callguardian.accounts.OwnedStore
import com.example.callguardian.accounts.OwnedAccount
import com.example.callguardian.accounts.OwnedCandidates
import com.example.callguardian.accounts.OwnedCandidate
import kotlinx.serialization.json.JsonObject
import com.google.firebase.ai.DownloadStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

data class SetupState(
    val callScreeningRole: Boolean = false,
    val smsPermission: Boolean = false,
    val contactsPermission: Boolean = false,
    val notificationsPermission: Boolean = false,
    val online: Boolean = true,
    val notificationAccess: Boolean = false,
    val listenerAppsInstalled: Boolean = false,
    val smsRole: Boolean = false,
    val previousSmsLabel: String? = null,
) {
    val coreReady get() = callScreeningRole && smsPermission
    val missing get() = listOf(callScreeningRole, smsPermission, contactsPermission, notificationsPermission).count { !it } +
        (if (listenerAppsInstalled && !notificationAccess) 1 else 0)
}

data class DeviceModelUi(
    val state: DeviceModelState = DeviceModelState.UNKNOWN,
    val modelName: String? = null,
    val downloading: Boolean = false,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
)

@HiltViewModel
class AppViewModel @Inject constructor(
    private val app: Application,
    private val engine: ScreeningEngine,
    private val events: ScreeningEventDao,
    private val profilesDao: PhoneProfileDao,
    private val prefs: LookupPreferences,
    private val analyzer: HybridScamAnalyzer,
    private val receiptsDao: ReceiptDao,
    val registry: MethodRegistry,
    val catalog: com.example.callguardian.integrations.IntegrationCatalog,
    private val integrationPrefs: com.example.callguardian.integrations.IntegrationPrefs,
    private val phone: com.example.callguardian.phone.PhoneData,
    private val subscriptionPrefs: com.example.callguardian.subscriptions.SubscriptionPrefs,
    private val dispatcher: com.example.callguardian.methods.MethodDispatcher,
    private val privacyStore: com.example.callguardian.privacy.PrivacyStore,
    private val privacySession: com.example.callguardian.privacy.PrivacySession,
    private val quarantineInbox: com.example.callguardian.sms.QuarantineInbox,
    private val smsRolePrefs: com.example.callguardian.sms.SmsRolePrefs,
    private val ownedStore: OwnedStore,
    private val subscriptionStore: com.example.callguardian.subscriptions.SubscriptionStore,
    private val jevSecrets: com.example.callguardian.jev.JevSecrets,
    private val remoteConfig: com.example.callguardian.firebase.GuardianRemoteConfig,
) : AndroidViewModel(app) {

    val receipts: StateFlow<List<ReceiptEntity>> = receiptsDao.observeRecent(500).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun receiptsFor(eventId: Long): Flow<List<ReceiptEntity>> = receiptsDao.observeForEvent(eventId)
    fun receipt(id: Long): Flow<ReceiptEntity?> = receiptsDao.observe(id)

    val privacyKeys: StateFlow<Set<String>> = privacyStore.keys.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val privacyUnlocked: StateFlow<Boolean> = privacySession.unlocked
    val quarantined = quarantineInbox.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val quarantineCount = quarantineInbox.observeCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    fun isPrivate(e: ScreeningEventEntity): Boolean =
        com.example.callguardian.privacy.PrivacyKeys.coversEvent(privacyKeys.value, e.id, e.number ?: e.displayName, e.channel == "CALL")
    fun unlockPrivate() = privacySession.unlock()
    fun restoreQuarantine(id: Long) = viewModelScope.launch { quarantineInbox.restore(id) }


    private val forms = mutableMapOf<String, SchemaForm>()
    /** Schema → UI model, cached per schema file. */
    fun inputForm(m: RegisteredMethod): SchemaForm = forms.getOrPut(m.inputSchemaId) { SchemaForm.parse(m.inputSchemaJson) }
    fun outputForm(m: RegisteredMethod): SchemaForm = forms.getOrPut(m.outputSchemaId) { SchemaForm.parse(m.outputSchemaJson) }
    fun header(m: RegisteredMethod) = MethodHeader(m.id, m.spec.title, m.spec.description.orEmpty(), m.spec.capability, m.spec.execution.name, registry.version)

    fun defaultInput(e: ScreeningEventEntity, m: GuardianMethod): JsonObject = engine.defaultInput(e, m)
    suspend fun evaluate(e: ScreeningEventEntity, m: GuardianMethod, source: VerdictSource): PolicyDecision = engine.evaluate(e, m, source)

    /** User-confirmed dispatch from a schema-rendered sheet. */
    fun runMethod(eventId: Long?, m: GuardianMethod, input: JsonObject, onResult: (DispatchResult) -> Unit) = viewModelScope.launch {
        onResult(engine.runUserMethod(eventId, m, input))
    }
    fun clearReceipts() = viewModelScope.launch { receiptsDao.clear() }

    val settings: StateFlow<AppSettings> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val recent: StateFlow<List<ScreeningEventEntity>> = events.observeRecent(300).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val listed = profilesDao.observeListed().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val ownedAccounts: StateFlow<List<OwnedAccount>> = ownedStore.accounts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val jevKeySaved: StateFlow<Boolean> = jevSecrets.hasKey
    fun ownedFor(number: String?) = ownedStore.matchSender(number)

    private val _processAll = MutableStateFlow(ProcessAllState())
    val processAll: StateFlow<ProcessAllState> = _processAll.asStateFlow()

    private val _pendingCancelAsk = MutableStateFlow<com.example.callguardian.subscriptions.SubscriptionRecord?>(null)
    val pendingCancelAsk: StateFlow<com.example.callguardian.subscriptions.SubscriptionRecord?> = _pendingCancelAsk.asStateFlow()

    fun refreshCancelAsk() = viewModelScope.launch {
        _pendingCancelAsk.value = subscriptionStore.pendingCancelAsks().firstOrNull()
    }
    fun confirmCanceled(alsoUnmarkOwned: Boolean) = viewModelScope.launch {
        val rec = _pendingCancelAsk.value ?: return@launch
        subscriptionStore.confirmCanceled(rec.id)
        // Canceling a subscription does not remove the Owned account unless the user chooses to.
        if (alsoUnmarkOwned) {
            ownedStore.matchSender(rec.sender)?.let { ownedStore.delete(it.id) }
        }
        _pendingCancelAsk.value = subscriptionStore.pendingCancelAsks().firstOrNull()
    }
    fun dismissCancelAsk() = viewModelScope.launch {
        val rec = _pendingCancelAsk.value ?: return@launch
        subscriptionStore.dismissCancelAsk(rec.id)
        _pendingCancelAsk.value = subscriptionStore.pendingCancelAsks().firstOrNull()
    }
    private var processJob: kotlinx.coroutines.Job? = null

    fun cancelProcessAll() {
        processJob?.cancel()
        _processAll.value = _processAll.value.copy(running = false, cancelled = true, label = null)
    }

    /**
     * Analyze every unprocessed screening event with the default pipeline: rules, then Firebase
     * AI Logic (Nano on-device, then cloud Gemini). When [includeLogs] is true, also reviews inbox
     * texts and call-log rows that have not been analyzed yet. Never sends STOP and never
     * auto-applies a method. Jev is not part of this path unless the user later turns it on.
     */
    fun startProcessAll(includeLogs: Boolean) {
        if (_processAll.value.running) return
        processJob?.cancel()
        processJob = viewModelScope.launch {
            val evs = recent.value
            data class Item(val kind: String, val id: Long? = null, val label: String, val text: com.example.callguardian.subscriptions.InboxSms? = null, val call: com.example.callguardian.methods.CallLogItem? = null)
            val queue = mutableListOf<Item>()
            evs.filter(HomeStats::isUnprocessed).forEach { e ->
                queue += Item("event", e.id, e.displayName ?: e.number ?: "item")
            }
            if (includeLogs) {
                val inbox = runCatching { phone.inbox() }.getOrDefault(emptyList())
                _inbox.value = inbox
                val calls = runCatching { phone.calls() }.getOrDefault(emptyList())
                _calls.value = calls
                val all = (evs + recent.value).distinctBy { it.id }
                inbox.filter { !HomeStats.textAlreadyAnalyzed(it, all) }.forEach { m ->
                    queue += Item("text", label = m.address, text = m)
                }
                calls.filter { !HomeStats.callAlreadyAnalyzed(it, all) }.forEach { c ->
                    queue += Item("call", label = c.cachedName ?: c.number ?: "hidden", call = c)
                }
            }
            if (queue.isEmpty()) {
                _processAll.value = ProcessAllState(total = 0, label = "Nothing to process")
                return@launch
            }
            _processAll.value = ProcessAllState(running = true, total = queue.size, current = 0)
            var done = 0
            var failed = 0
            try {
                queue.forEachIndexed { i, item ->
                    ensureActive()
                    _processAll.value = _processAll.value.copy(current = i, label = item.label.take(40))
                    val ok = runCatching {
                        val id = when (item.kind) {
                            "event" -> item.id!!
                            "text" -> {
                                val m = item.text!!
                                engine.screenSms(m.address, m.body, isTest = true, runAi = false, source = com.example.callguardian.model.MessageSource.SMS, applyActions = false)
                            }
                            else -> {
                                val c = item.call!!
                                engine.reviewCallLog(c.number, c.cachedName, c.isContact, c.type.name, c.durationSec, c.date, c.callsFromNumber30d, c.callsWithin15Min)
                            }
                        }
                        engine.analyzeOnDevice(id, notify = false, applyActions = false)
                    }.isSuccess
                    if (ok) done++ else failed++
                    _processAll.value = _processAll.value.copy(current = i + 1, done = done, failed = failed)
                    // Give AICore a breath so a 500-item startup batch does not hit ErrorCode 9 (quota).
                    kotlinx.coroutines.delay(300)
                    if (!com.example.callguardian.screening.AppForeground.isForeground) {
                        _processAll.value = _processAll.value.copy(running = false, cancelled = true, label = null)
                        return@launch
                    }
                }
            } catch (ce: CancellationException) {
                _processAll.value = _processAll.value.copy(running = false, cancelled = true, done = done, failed = failed, label = null)
                throw ce
            }
            val candidates = OwnedCandidates.suggest(recent.value, ownedStore.snapshot)
            _processAll.value = _processAll.value.copy(running = false, cancelled = false, current = queue.size, done = done, failed = failed, label = null, candidates = candidates)
        }
    }

    fun dismissOwnedCandidates() {
        _processAll.value = _processAll.value.copy(candidates = emptyList())
    }

    fun markOwnedInput(c: OwnedCandidate, eventId: Long? = null) = com.example.callguardian.methods.UserMethodInputs.markOwned(c.service, c.senders, c.domains, eventId)
    fun markOwnedInput(service: String, senders: List<String>, domains: List<String>, eventId: Long? = null) =
        com.example.callguardian.methods.UserMethodInputs.markOwned(service, senders, domains, eventId)
    fun unmarkOwnedInput(a: OwnedAccount) = com.example.callguardian.methods.UserMethodInputs.unmarkOwned(a.id, a.service)
    fun notScamInput(e: com.example.callguardian.data.db.ScreeningEventEntity): kotlinx.serialization.json.JsonObject {
        val number = e.number ?: return kotlinx.serialization.json.buildJsonObject { }
        val hosts = com.example.callguardian.screening.OfficialDomains.hostsIn(e.body.orEmpty())
            .filter { com.example.callguardian.screening.OfficialDomains.isAnyOfficialHost(it) }
        val service = e.displayName
            ?: com.example.callguardian.screening.OfficialDomains.mentionedBrands(e.body.orEmpty()).firstOrNull()?.let {
                when (com.example.callguardian.screening.OfficialDomains.brandKey(it)) {
                    "ezpass", "e-zpass" -> "E-ZPass"
                    "cashapp", "cash-app" -> "Cash App"
                    else -> it.replaceFirstChar { ch -> ch.uppercase() }
                }
            }
        return com.example.callguardian.methods.UserMethodInputs.notScam(
            e.id, number, (e.displayName ?: number).take(80), markOwned = false, service = service, domains = hosts,
        )
    }


    private val _setup = MutableStateFlow(SetupState())
    val setup = _setup.asStateFlow()
    init {
        viewModelScope.launch {
            smsRolePrefs.previousPackage.collect { pkg ->
                _setup.value = _setup.value.copy(previousSmsLabel = com.example.callguardian.sms.SmsRole.labelFor(app, pkg))
            }
        }
        viewModelScope.launch { loadSubscriptions() }
        viewModelScope.launch { refreshCancelAsk() }
        // Once per process: analyze unprocessed messages with rules, then Firebase AI Logic
        // (Nano, then cloud Gemini). Judge only — never sends STOP or runs a method.
        viewModelScope.launch {
            remoteConfig.fetch()
            if (!remoteConfig.startupAutoProcess) return@launch
            recent.drop(1).first()
            startProcessAll(includeLogs = true)
        }
    }

    private val _model = MutableStateFlow(DeviceModelUi())
    val model = _model.asStateFlow()

    val cloudConfigured get() = FirebaseBootstrap.cloudConfigured

    fun event(id: Long): Flow<ScreeningEventEntity?> = events.observe(id)

    fun refresh() {
        refreshCancelAsk()
        val ctx = app
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        val role = ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val online = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val smsHeld = com.example.callguardian.sms.SmsRole.held(ctx)
        _setup.value = SetupState(
            callScreeningRole = role,
            smsPermission = granted(Manifest.permission.RECEIVE_SMS),
            contactsPermission = granted(Manifest.permission.READ_CONTACTS),
            notificationsPermission = granted(Manifest.permission.POST_NOTIFICATIONS),
            online = online,
            notificationAccess = notificationAccessGranted(),
            listenerAppsInstalled = catalog.listenerPackages.any { installed(it) },
            smsRole = smsHeld,
            previousSmsLabel = _setup.value.previousSmsLabel,
        )
        refreshModel()
        viewModelScope.launch { engine.processPending() }
    }

    fun refreshModel() = viewModelScope.launch {
        val choice = prefs.current().modelChoice
        val st = analyzer.deviceState(choice)
        val name = if (st == DeviceModelState.AVAILABLE) analyzer.deviceModelName(choice) else null
        _model.value = _model.value.copy(state = st, modelName = name, error = null)
        if (st == DeviceModelState.AVAILABLE) analyzer.warmUp(choice)
    }

    fun downloadModel() = viewModelScope.launch {
        val choice = prefs.current().modelChoice
        _model.value = _model.value.copy(downloading = true, error = null)
        runCatching {
            analyzer.download(choice).collect { s ->
                _model.value = when (s) {
                    is DownloadStatus.DownloadStarted -> _model.value.copy(totalBytes = s.bytesToDownload, state = DeviceModelState.DOWNLOADING)
                    is DownloadStatus.DownloadInProgress -> _model.value.copy(downloadedBytes = s.totalBytesDownloaded, state = DeviceModelState.DOWNLOADING)
                    is DownloadStatus.DownloadFailed -> _model.value.copy(downloading = false, error = s.exception.message ?: "Download failed")
                    else -> _model.value.copy(downloading = false)
                }
            }
        }.onFailure { _model.value = _model.value.copy(downloading = false, error = it.message) }
        _model.value = _model.value.copy(downloading = false)
        refreshModel()
    }

    fun runTest(sender: String, body: String, onCreated: (Long) -> Unit) = viewModelScope.launch {
        val id = engine.screenSms(sender.ifBlank { "+1 (555) 010-0199" }, body, isTest = true, runAi = false)
        onCreated(id)
        engine.analyzeOnDevice(id, notify = false)
    }

    fun analyze(id: Long) = viewModelScope.launch { runCatching { engine.analyzeOnDevice(id) } }
    fun redactedPreview(e: ScreeningEventEntity) = engine.redactedPreview(e)
    fun delete(id: Long) = viewModelScope.launch { events.delete(id) }
    fun clearHistory() = viewModelScope.launch { events.clear() }
    fun unlist(number: String) = viewModelScope.launch { engine.unlist(number) }

    fun setPrivateMode(v: Boolean) = viewModelScope.launch { prefs.setPrivateMode(v) }
    fun setModelChoice(v: OnDeviceModelChoice) = viewModelScope.launch { prefs.setModelChoice(v); refreshModel() }
    fun setAutoBlock(v: Boolean) = viewModelScope.launch { prefs.setAutoBlock(v) }
    fun setThreshold(v: Int) = viewModelScope.launch { prefs.setAutoBlockThreshold(v) }
    fun setSilence(v: Boolean) = viewModelScope.launch { prefs.setSilenceCalls(v) }
    fun saveLookup(num: String, abs: String, url: String, h: String, hv: String) = viewModelScope.launch {
        prefs.updateNumLookupKey(num); prefs.updateAbstractKey(abs); prefs.updateCustomEndpoint(url, h, hv)
    }
    fun saveJevKey(key: String) = viewModelScope.launch { jevSecrets.setApiKey(key) }
    fun clearJevKey() = viewModelScope.launch { jevSecrets.clear() }

    // ---------------------------------------------------------------- integrations (Settings)

    val integrationState = integrationPrefs.state.stateIn(viewModelScope, SharingStarted.Eagerly,
        com.example.callguardian.integrations.IntegrationState(emptyMap(), emptyMap()))

    fun installed(pkg: String): Boolean = runCatching { app.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    fun granted(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED
    fun notificationAccessGranted(): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)

    /** Live status line for an integration row (generated from integrations.json). */
    fun integrationStatus(i: com.example.callguardian.integrations.Integration): com.example.callguardian.integrations.IntegrationStatus {
        val kind = i.kind.name
        val pkgs = i.packages.orEmpty()
        val installedPkgs = pkgs.filter { installed(it) }
        val available = pkgs.isEmpty() || installedPkgs.isNotEmpty()
        val missingPerms = i.permissions.orEmpty().filter { !granted(it) }
        val access = when (kind) {
            "notification_listener" -> notificationAccessGranted()
            "cloud_function" -> cloudConfigured
            else -> missingPerms.isEmpty()
        }
        val parts = buildList {
            if (pkgs.isNotEmpty()) add(if (available) "Installed" else "Not installed")
            when (kind) {
                "notification_listener" -> add(if (access) "Notification access on" else "Notification access off")
                "cloud_function" -> add(if (cloudConfigured) "Firebase configured" else "Firebase not configured")
                else -> add(if (missingPerms.isEmpty()) "Permissions granted" else "Needs ${missingPerms.joinToString { it.substringAfterLast('.') }}")
            }
        }
        return com.example.callguardian.integrations.IntegrationStatus(available, access, missingPerms, parts.joinToString(" · "))
    }

    fun setIntegration(i: com.example.callguardian.integrations.Integration, on: Boolean, onResult: (DispatchResult) -> Unit) = viewModelScope.launch {
        val st = integrationStatus(i)
        onResult(dispatcher.dispatchUser(com.example.callguardian.methods.UserMethod.SET_INTEGRATION,
            com.example.callguardian.methods.UserMethodInputs.setIntegration(i.id.name, on, st.line), null))
    }

    // ---------------------------------------------------------------- Lab: my texts, calls, subscriptions

    val labSamples: List<com.example.callguardian.ui.screens.LabSample> by lazy {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(app.assets.open("lab_samples.json").bufferedReader().use { it.readText() })
        (root as kotlinx.serialization.json.JsonObject)["samples"]!!.let { it as kotlinx.serialization.json.JsonArray }.map { e ->
            val o = e as kotlinx.serialization.json.JsonObject
            fun f(k: String) = (o[k] as kotlinx.serialization.json.JsonPrimitive).content
            com.example.callguardian.ui.screens.LabSample(f("title"), f("sender"), f("body"))
        }
    }

    private val _inbox = MutableStateFlow<List<com.example.callguardian.subscriptions.InboxSms>?>(null)
    val inbox = _inbox.asStateFlow()
    private val _calls = MutableStateFlow<List<com.example.callguardian.methods.CallLogItem>?>(null)
    val calls = _calls.asStateFlow()
    private val _subs = MutableStateFlow<List<com.example.callguardian.subscriptions.SubscriptionGroup>?>(null)
    val subscriptions = _subs.asStateFlow()
    private val _confirm = MutableStateFlow<Map<String, ScreeningEngine.SubscriptionConfirm>>(emptyMap())
    val subscriptionConfirm = _confirm.asStateFlow()
    val subscriptionMarks = subscriptionPrefs.marks.stateIn(viewModelScope, SharingStarted.Eagerly, com.example.callguardian.subscriptions.SubscriptionMarks())

    fun integrationEvents(id: String) = events.observeIntegration(id)
    fun openNotificationAccess(ctx: android.content.Context) {
        runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun loadInbox() = viewModelScope.launch { _inbox.value = phone.inbox() }
    fun loadCalls() = viewModelScope.launch { _calls.value = phone.calls() }

    fun loadSubscriptions() = viewModelScope.launch {
        val sms = phone.inbox(1000)
        val fromApps = events.bySource(listOf("google_voice", "whatsapp"), 500).map {
            com.example.callguardian.subscriptions.InboxSms(it.id, it.number ?: it.displayName ?: "unknown", it.body.orEmpty(), it.receivedAt,
                com.example.callguardian.model.MessageSource.fromWire(it.source))
        }
        val all = sms + fromApps
        val detector = com.example.callguardian.subscriptions.SubscriptionDetector
        var groups = detector.group(all)
        _subs.value = groups
        val confirms = _confirm.value.toMutableMap()
        suspend fun confirmMissing() {
            groups.take(25).forEach { g ->
                if (confirms.containsKey(g.serviceKey)) return@forEach
                val recent = all.filter { it.source == g.source && it.address.trim() in g.senders }
                    .sortedByDescending { it.date }.take(5).map { "${it.address}: ${it.body}" }
                engine.confirmSubscription(g, recent)?.let { confirms[g.serviceKey] = it }
            }
        }
        confirmMissing()
        val named = groups.map { g ->
            confirms[g.serviceKey]?.service?.let { detector.withService(g, it) } ?: g
        }
        val merged = detector.mergeByService(named)
        groups = merged
        _subs.value = groups
        // Re-key confirms after Nano-driven merges, then confirm any new rows.
        val reindexed = mutableMapOf<String, com.example.callguardian.screening.ScreeningEngine.SubscriptionConfirm>()
        merged.forEach { g ->
            confirms[g.serviceKey]?.let { reindexed[g.serviceKey] = it }
            confirms.values.firstOrNull { c ->
                val s = c.service?.let(detector::squash).orEmpty()
                s.length >= 3 && s == detector.squash(g.service)
            }?.let { reindexed[g.serviceKey] = it }
        }
        confirms.clear(); confirms.putAll(reindexed)
        confirmMissing()
        _subs.value = detector.mergeByService(groups.map { g ->
            confirms[g.serviceKey]?.service?.let { detector.withService(g, it) } ?: g
        })
        _confirm.value = confirms
    }

    /** SMS inbox item → same pipeline as a new text (rules + on-device model), as a review event. */
    fun reviewText(m: com.example.callguardian.subscriptions.InboxSms, onCreated: (Long) -> Unit) = viewModelScope.launch {
        val id = engine.screenSms(m.address, m.body, isTest = true, runAi = false, source = com.example.callguardian.model.MessageSource.SMS)
        onCreated(id)
        engine.analyzeOnDevice(id, notify = false)
    }

    fun runUser(m: com.example.callguardian.methods.UserMethod, input: JsonObject, eventId: Long?, onResult: (DispatchResult) -> Unit) = viewModelScope.launch {
        val r = dispatcher.dispatchUser(m, input, eventId)
        onResult(r)
        if (r.ok && m == com.example.callguardian.methods.UserMethod.REVIEW_CALL) {
            val o = r.output as? JsonObject
            val ev = (o?.get("eventId") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
            val queued = (o?.get("onDeviceQueued") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
            if (ev != null && queued) engine.analyzeOnDevice(ev, notify = false)
        }
    }

    fun jevInput(e: ScreeningEventEntity): JsonObject = com.example.callguardian.methods.UserMethodInputs.jevCheck(
        e.id, e.number, e.isContact, redactedPreview(e), if (e.source == "sms" || e.source == "google_voice" || e.source == "whatsapp") e.source else "lab_sample")

    fun privacyLabel(e: ScreeningEventEntity): String = (e.displayName ?: e.number ?: "this item").take(80)
    fun setPrivateInput(e: ScreeningEventEntity) = com.example.callguardian.methods.UserMethodInputs.setPrivate(
        "EVENT", "Hide ${privacyLabel(e)}", eventId = e.id, address = e.number, source = e.source)
    fun clearPrivateInput(e: ScreeningEventEntity) = com.example.callguardian.methods.UserMethodInputs.clearPrivate(
        "EVENT", "Show ${privacyLabel(e)} again", eventId = e.id, address = e.number, source = e.source)
    fun setThreadPrivateInput(address: String, source: String = "sms") = com.example.callguardian.methods.UserMethodInputs.setPrivate(
        "THREAD", "Hide texts from $address", address = address, source = source)
    fun clearThreadPrivateInput(address: String, source: String = "sms") = com.example.callguardian.methods.UserMethodInputs.clearPrivate(
        "THREAD", "Show texts from $address again", address = address, source = source)
    fun setContactPrivateInput(address: String, source: String = "sms") = com.example.callguardian.methods.UserMethodInputs.setPrivate(
        "CONTACT", "Hide all from $address", address = address, source = source)
    fun clearContactPrivateInput(address: String, source: String = "sms") = com.example.callguardian.methods.UserMethodInputs.clearPrivate(
        "CONTACT", "Show all from $address again", address = address, source = source)
    fun setCallPrivateInput(c: com.example.callguardian.methods.CallLogItem) = com.example.callguardian.methods.UserMethodInputs.setPrivate(
        "CALL", "Hide call with ${c.cachedName ?: c.number ?: "hidden number"}", address = c.number, callLogId = c.id, source = "call_log")
    fun clearCallPrivateInput(c: com.example.callguardian.methods.CallLogItem) = com.example.callguardian.methods.UserMethodInputs.clearPrivate(
        "CALL", "Show call with ${c.cachedName ?: c.number ?: "hidden number"} again", address = c.number, callLogId = c.id, source = "call_log")

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    private val _searchChips = MutableStateFlow(emptySet<SearchChip>())
    val searchChips = _searchChips.asStateFlow()
    private val _searchHits = MutableStateFlow(emptyList<SearchHit>())
    val searchHits = _searchHits.asStateFlow()

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
        viewModelScope.launch { refreshSearch() }
    }

    fun toggleSearchChip(chip: SearchChip) {
        val cur = _searchChips.value
        _searchChips.value = if (chip in cur) cur - chip else cur + chip
        viewModelScope.launch { refreshSearch() }
    }

    suspend fun refreshSearch() {
        val q = _searchQuery.value
        val chips = _searchChips.value
        val marks = privacyKeys.value
        val fts = SearchMatcher.ftsQuery(q)
        val digits = SearchMatcher.digitQuery(q)
        val found = buildList {
            if (fts != null) addAll(runCatching { events.searchFts(fts) }.getOrDefault(emptyList()))
            if (digits.length >= 3) addAll(runCatching { events.searchNumberOrName(digits, q.trim(), 200) }.getOrDefault(emptyList()))
            if (fts == null && digits.length < 3 && q.isNotBlank()) addAll(recent.value)
            if (q.isBlank()) addAll(recent.value)
        }.distinctBy { it.id }
        val subs = subscriptions.value.orEmpty()
        val subIds = subs.flatMap { it.senders + listOfNotNull(it.service, it.brand, it.sender) }.flatMap { listOf(it, SearchMatcher.digits(it)) }.toSet()
        _searchHits.value = SearchMatcher.merge(
            query = q,
            chips = chips,
            marks = marks,
            events = found,
            receipts = receipts.value,
            subscriptions = subs,
            quarantined = quarantined.value,
            inbox = inbox.value.orEmpty(),
            calls = calls.value.orEmpty(),
            methodTitle = { id -> registry.byId(id)?.spec?.title ?: id },
            subscriptionIds = subIds,
        )
    }
}
