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
import kotlinx.serialization.json.JsonObject
import com.google.firebase.ai.DownloadStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupState(
    val callScreeningRole: Boolean = false,
    val smsPermission: Boolean = false,
    val contactsPermission: Boolean = false,
    val notificationsPermission: Boolean = false,
    val online: Boolean = true,
    val notificationAccess: Boolean = false,
    val listenerAppsInstalled: Boolean = false,
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
) : AndroidViewModel(app) {

    val receipts: StateFlow<List<ReceiptEntity>> = receiptsDao.observeRecent(500).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun receiptsFor(eventId: Long): Flow<List<ReceiptEntity>> = receiptsDao.observeForEvent(eventId)
    fun receipt(id: Long): Flow<ReceiptEntity?> = receiptsDao.observe(id)

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

    private val _setup = MutableStateFlow(SetupState())
    val setup = _setup.asStateFlow()
    private val _model = MutableStateFlow(DeviceModelUi())
    val model = _model.asStateFlow()

    val cloudConfigured get() = FirebaseBootstrap.cloudConfigured

    fun event(id: Long): Flow<ScreeningEventEntity?> = events.observe(id)

    fun refresh() {
        val ctx = app
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        val role = ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val online = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        _setup.value = SetupState(
            callScreeningRole = role,
            smsPermission = granted(Manifest.permission.RECEIVE_SMS),
            contactsPermission = granted(Manifest.permission.READ_CONTACTS),
            notificationsPermission = granted(Manifest.permission.POST_NOTIFICATIONS),
            online = online,
            notificationAccess = notificationAccessGranted(),
            listenerAppsInstalled = catalog.listenerPackages.any { installed(it) },
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

    fun analyze(id: Long) = viewModelScope.launch { engine.analyzeOnDevice(id) }
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
        val groups = com.example.callguardian.subscriptions.SubscriptionDetector.group(all)
        _subs.value = groups
        // Gemini Nano 4 confirms each group on the phone (foreground only), newest first.
        groups.take(25).forEach { g ->
            if (_confirm.value.containsKey(g.sender)) return@forEach
            val recent = all.filter { it.address.trim() == g.sender && it.source == g.source }.sortedByDescending { it.date }.take(3).map { it.body }
            engine.confirmSubscription(g, recent)?.let { c -> _confirm.value = _confirm.value + (g.sender to c) }
        }
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
}
