package com.example.callguardian.ui.screens

import com.example.callguardian.ui.components.GuardianTopBar
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.work.WorkManager
import com.example.callguardian.lookup.BulkLookupWorker
import com.example.callguardian.lookup.LookupCostEstimate
import com.example.callguardian.lookup.LookupProvider
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.callguardian.BuildConfig
import com.example.callguardian.data.settings.OnDeviceModelChoice
import com.example.callguardian.firebase.AppCheckInstaller
import com.example.callguardian.ui.AppViewModel
import com.example.callguardian.ui.SetupActions
import com.example.callguardian.ui.components.SectionCard
import com.example.callguardian.ui.components.SectionTitle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, actions: SetupActions, onLists: () -> Unit) {
    val s by vm.settings.collectAsState()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        GuardianTopBar(title = { Text("Settings") })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IntegrationsCard(vm, actions)
            SectionCard {
                SectionTitle("Privacy")
                ToggleRow("Private mode", "Message content is analyzed only on this phone (ONLY_ON_DEVICE). Cloud analysis still needs your tap and only sees redacted text.", s.privateMode, vm::setPrivateMode)
                Text("Make private on a call or text hides the number, body and analysis behind a blur until you unlock with fingerprint, face or your device PIN.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DefaultSmsCard(vm, actions)
            SectionCard {
                SectionTitle("On-device model")
                OnDeviceModelChoice.entries.forEach { c ->
                    Row(Modifier.fillMaxWidth().selectable(s.modelChoice == c) { vm.setModelChoice(c) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(s.modelChoice == c, { vm.setModelChoice(c) })
                        Column { Text(c.title, style = MaterialTheme.typography.bodyLarge); Text(c.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            SectionCard {
                SectionTitle("Protection")
                ToggleRow("Auto-run block_number", "Let guardian.call.block@v1 run without a tap when policy allows.", s.autoBlock, vm::setAutoBlock)
                Text("Minimum model confidence to block: ${s.autoBlockThreshold}%", style = MaterialTheme.typography.bodyMedium)
                Slider(s.autoBlockThreshold.toFloat(), { vm.setThreshold(it.toInt()) }, valueRange = 50f..100f, steps = 9, enabled = s.autoBlock)
                ToggleRow("Silence suspicious calls", "Allow guardian.call.reject@v1 to silence high-risk calls.", s.silenceSuspiciousCalls, vm::setSilence)
                TextButton(onClick = onLists) { Text("Blocked & allowed numbers") }
            }
            SectionCard {
                SectionTitle("Cloud & App Check")
                Text(if (vm.cloudConfigured) "Firebase configured · App Check: ${AppCheckInstaller.providerName}" else "No google-services.json in this build: cloud analysis is off.",
                    style = MaterialTheme.typography.bodyMedium)
                Text("Cloud model: ${BuildConfig.CLOUD_MODEL}", style = MaterialTheme.typography.bodySmall)
                if (BuildConfig.DEBUG) {
                    val token = remember { AppCheckInstaller.debugToken(context) }
                    Text("Debug App Check token (register under Firebase console → App Check → Manage debug tokens):", style = MaterialTheme.typography.bodySmall)
                    Text(token ?: "Not generated yet. Run a cloud analysis once, then reopen Settings.", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    if (token != null) TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("App Check debug token", token))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy token") }
                }
            }
            DeepAnalysisCard(vm)
            CompanionAlertsCard(vm, actions)
            LookupCard(vm)
            JevKeyCard(vm)
            SectionCard {
                SectionTitle("Data")
                OutlinedButton(onClick = { confirmClear = true }) { Text("Clear history and receipts") }
                TextButton(onClick = actions.openAppSettings) { Text("App info & permissions") }
            }
            SectionCard {
                SectionTitle("About")
                Text("Call Guardian ${BuildConfig.VERSION_NAME} · method registry ${vm.registry.version}", style = MaterialTheme.typography.bodyMedium)
                Text("Copyright 2026 Jeremy Hobson. Licensed under the Apache License 2.0.", style = MaterialTheme.typography.bodySmall)
                Text("github.com/repr0bated/callguardian-android", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text("On-device inference by Firebase AI Logic (Gemma 4 / Gemini Nano via Android AICore). Cloud analysis by Gemini via Firebase AI Logic.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear everything?") }, text = { Text("Deletes screened events and method receipts on this phone.") },
        confirmButton = { TextButton(onClick = { vm.clearHistory(); vm.clearReceipts(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked, onChange)
    }
}


@Composable
private fun KeyField(value: String, onValue: (String) -> Unit, label: String, getKeyUrl: String) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value, onValue, label = { Text(label) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getKeyUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }) { Text("Get a key") }
    }
}


@Composable
private fun DeepAnalysisCard(vm: AppViewModel) {
    val saved by vm.geminiKeySaved.collectAsState()
    var value by remember { mutableStateOf("") }
    val project = BuildConfig.FIREBASE_PROJECT_ID.trim().ifBlank { "this app's Firebase project" }
    SectionCard {
        SectionTitle("Deep Analysis")
        Text(
            "Deep Analysis (cloud Gemini) uses Firebase AI Logic in the Firebase project $project. " +
                "It only runs when you ask for it, and the text it sees is redacted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Own Gemini API key (optional, off by default, not used for the hackathon). " +
                "If you save a key it is stored encrypted on this phone (Android Keystore) and is never logged or backed up. " +
                "The key is used only as a fallback when Firebase AI Logic fails.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (saved) Text("A key is saved on this phone. It is not used for the hackathon.", style = MaterialTheme.typography.bodySmall, color = com.example.callguardian.ui.theme.Brand.Lime)
        KeyField(value, { value = it }, "Own Gemini API key", com.example.callguardian.ai.GeminiSecrets.GET_KEY_URL)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveGeminiKey(value); value = "" }, enabled = value.isNotBlank()) { Text("Save") }
            if (saved) TextButton(onClick = { vm.clearGeminiKey(); value = "" }) { Text("Remove key") }
        }
    }
}

@Composable
private fun CompanionAlertsCard(vm: AppViewModel, actions: SetupActions) {
    val signedIn by vm.companionSignedIn.collectAsState()
    SectionCard {
        SectionTitle("Web companion alerts")
        Text(
            "When this phone blocks a scam, a notice can appear in the signed-in web companion. " +
                "The notice includes only the category and whether Nano, cloud, or local rules decided it — never the message text or the phone number. " +
                "The web app listens with Firestore onSnapshot (Spark). Sending those alerts through FCM would need Cloud Functions, which are only on the Blaze plan, so this app does not send FCM from the server.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!vm.companionSignInConfigured()) {
            Text("Google sign-in is not configured in this build.", style = MaterialTheme.typography.bodySmall)
        } else if (signedIn) {
            Text("Signed in${vm.companionEmail()?.let { " as $it" } ?: ""}.", style = MaterialTheme.typography.bodySmall, color = com.example.callguardian.ui.theme.Brand.Lime)
            TextButton(onClick = actions.signOutCompanion) { Text("Sign out") }
        } else {
            Button(onClick = actions.signInCompanion) { Text("Sign in for web alerts") }
        }
    }
}

@Composable
private fun JevKeyCard(vm: AppViewModel) {
    val saved by vm.jevKeySaved.collectAsState()
    var value by remember { mutableStateOf("") }
    SectionCard {
        SectionTitle("TypeSafe Jev API key")
        Text(
            "Optional. Jev is off by default; turn it on under Watchlist when you want a second opinion. " +
                "A key is stored encrypted on this phone (Android Keystore) and is never logged or backed up. " +
                "With a key, the app calls TypeSafe directly; otherwise it uses the Cloudflare Worker.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (saved) Text("A key is saved on this phone.", style = MaterialTheme.typography.bodySmall, color = com.example.callguardian.ui.theme.Brand.Lime)
        KeyField(value, { value = it }, "TypeSafe Jev API key", com.example.callguardian.jev.JevEndpoints.TYPESAFE_GET_KEY_URL)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveJevKey(value); value = "" }, enabled = value.isNotBlank()) { Text("Save") }
            if (saved) TextButton(onClick = { vm.clearJevKey(); value = "" }) { Text("Remove key") }
        }
    }
}

@Composable
private fun LookupCard(vm: AppViewModel) {
    val s by vm.settings.collectAsState()
    val twilio by vm.twilioKeySaved.collectAsState()
    val telnyx by vm.telnyxKeySaved.collectAsState()
    var num by remember(s.numLookupApiKey) { mutableStateOf(s.numLookupApiKey) }
    var abs by remember(s.abstractApiKey) { mutableStateOf(s.abstractApiKey) }
    var url by remember(s.customEndpoint) { mutableStateOf(s.customEndpoint) }
    var hn by remember(s.customHeaderName) { mutableStateOf(s.customHeaderName) }
    var hv by remember(s.customHeaderValue) { mutableStateOf(s.customHeaderValue) }
    SectionCard {
        SectionTitle("Caller lookup (optional)")
        Text(
            "Reverse lookup for unknown callers. Providers run in the order below and their results are merged, each field labeled with its source. " +
                "Twilio and Telnyx keys are stored encrypted on this phone (Android Keystore) and are never logged.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("NumLookupAPI", style = MaterialTheme.typography.titleSmall)
        KeyField(num, { num = it }, "NumLookupAPI key", LookupProvider.NUMLOOKUP.getKeyUrl)
        LookupAllRow(vm, LookupProvider.NUMLOOKUP, enabled = s.hasNumLookupKey)
        Text("Abstract API", style = MaterialTheme.typography.titleSmall)
        KeyField(abs, { abs = it }, "Abstract API key", LookupProvider.ABSTRACT.getKeyUrl)
        LookupAllRow(vm, LookupProvider.ABSTRACT, enabled = s.hasAbstractKey)
        TwilioLookupKeys(vm, twilio)
        LookupAllRow(vm, LookupProvider.TWILIO, enabled = twilio)
        TelnyxLookupKeys(vm, telnyx)
        LookupAllRow(vm, LookupProvider.TELNYX, enabled = telnyx)
        OutlinedTextField(url, { url = it }, label = { Text("Custom endpoint ({number} placeholder)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(hn, { hn = it }, label = { Text("Header") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(hv, { hv = it }, label = { Text("Value") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
        }
        Button(onClick = { vm.saveLookup(num, abs, url, hn, hv) }) { Text("Save NumLookup, Abstract, and custom") }
        Spacer(Modifier.height(8.dp))
        ProviderOrderEditor(vm, s.lookupProviderOrder)
    }
}

@Composable
private fun TwilioLookupKeys(vm: AppViewModel, saved: Boolean) {
    var sid by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    Text("Twilio Lookup v2", style = MaterialTheme.typography.titleSmall)
    Text(
        "Account SID and Auth Token. The app requests caller_name and line_type_intelligence.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (saved) Text("A SID and Auth Token are saved on this phone.", style = MaterialTheme.typography.bodySmall, color = com.example.callguardian.ui.theme.Brand.Lime)
    KeyField(sid, { sid = it }, "Twilio Account SID", LookupProvider.TWILIO.getKeyUrl)
    OutlinedTextField(token, { token = it }, label = { Text("Twilio Auth Token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.saveTwilio(sid, token); sid = ""; token = "" }, enabled = sid.isNotBlank() && token.isNotBlank()) { Text("Save Twilio") }
        if (saved) TextButton(onClick = { vm.clearTwilio(); sid = ""; token = "" }) { Text("Remove Twilio") }
    }
}

@Composable
private fun TelnyxLookupKeys(vm: AppViewModel, saved: Boolean) {
    var key by remember { mutableStateOf("") }
    Text("Telnyx Number Lookup", style = MaterialTheme.typography.titleSmall)
    Text(
        "API key. The app requests carrier and caller-name.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (saved) Text("A key is saved on this phone.", style = MaterialTheme.typography.bodySmall, color = com.example.callguardian.ui.theme.Brand.Lime)
    KeyField(key, { key = it }, "Telnyx API key", LookupProvider.TELNYX.getKeyUrl)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.saveTelnyx(key); key = "" }, enabled = key.isNotBlank()) { Text("Save Telnyx") }
        if (saved) TextButton(onClick = { vm.clearTelnyx(); key = "" }) { Text("Remove Telnyx") }
    }
}

@Composable
private fun LookupAllRow(vm: AppViewModel, provider: LookupProvider, enabled: Boolean) {
    val context = LocalContext.current
    val work by remember(provider.id, context) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(BulkLookupWorker.uniqueName(provider.id))
    }.collectAsState(initial = emptyList())
    val running = work.any { !it.state.isFinished }
    val progress = work.firstOrNull { !it.state.isFinished }?.progress
    var pending by remember { mutableStateOf<LookupCostEstimate?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running) {
            val done = progress?.getInt(BulkLookupWorker.KEY_DONE, 0) ?: 0
            val total = progress?.getInt(BulkLookupWorker.KEY_TOTAL, 0) ?: 0
            Text("Looking up $done of $total…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.cancelBulkLookup(context, provider.id) }) { Text("Cancel") }
        } else {
            TextButton(
                onClick = {
                    loading = true
                    scope.launch {
                        pending = runCatching { vm.previewBulkLookup(provider.id) }.getOrNull()
                        loading = false
                    }
                },
                enabled = enabled && !loading,
            ) { Text(if (loading) "Counting…" else "Look up all") }
        }
    }
    pending?.let { est ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Look up all with ${est.providerLabel}?") },
            text = { Text(est.confirmMessage()) },
            confirmButton = {
                TextButton(onClick = {
                    if (est.toQueryCount > 0) vm.startBulkLookup(context, provider.id)
                    pending = null
                }) { Text(if (est.toQueryCount == 0) "OK" else "Start lookup") }
            },
            dismissButton = { if (est.toQueryCount > 0) TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProviderOrderEditor(vm: AppViewModel, stored: List<String>) {
    val order = remember(stored) { LookupProvider.ordered(stored).toMutableStateList() }
    Text("Provider order", style = MaterialTheme.typography.titleSmall)
    Text(
        "Enabled providers run in this order. The lookup sheet merges fields and labels each one with its source.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    order.forEachIndexed { i, p ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}. ${p.displayName}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = {
                if (i > 0) {
                    val prev = order[i - 1]
                    order[i - 1] = p
                    order[i] = prev
                    vm.updateProviderOrder(order.map { it.id })
                }
            }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move ${p.displayName} up") }
            IconButton(onClick = {
                if (i < order.lastIndex) {
                    val next = order[i + 1]
                    order[i + 1] = p
                    order[i] = next
                    vm.updateProviderOrder(order.map { it.id })
                }
            }, enabled = i < order.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Move ${p.displayName} down") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val listed by vm.listed.collectAsState()
    Column(Modifier.fillMaxSize()) {
        GuardianTopBar(title = { Text("Blocked & allowed") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (listed.isEmpty()) item { Text("No numbers yet. block_number and allow_number receipts add them here.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(listed, key = { it.phoneNumber }) { p ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.displayName ?: p.phoneNumber, style = MaterialTheme.typography.titleSmall)
                            Text(
                                buildList {
                                    if (p.trusted) add("Allowed")
                                    if (p.mode != com.example.callguardian.model.BlockMode.NONE) add("Blocked: ${p.mode.name.lowercase()}")
                                    if (p.watched) add("Watched")
                                }.joinToString(" · ").ifBlank { "Listed" },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { vm.unlist(p.phoneNumber) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

/** Generated from integrations.json: one row per source plus the Jev second opinion. Toggles run guardian.integration.set@v1. */
@Composable
private fun IntegrationsCard(vm: AppViewModel, actions: SetupActions) {
    val state by vm.integrationState.collectAsState()
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    SectionCard {
        SectionTitle("Watchlist")
        Text("Sources Call Guardian watches. Each switch is the typed method guardian.integration.set@v1 and leaves a receipt. Data from every source stays on this phone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        vm.catalog.all.forEach { i ->
            val st = remember(tick, state) { vm.integrationStatus(i) }
            val on = state.enabled[i.id.name] ?: i.defaultEnabled
            val seen = state.lastSeen[i.id.name]?.takeIf { it > 0 }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(i.label, style = MaterialTheme.typography.bodyLarge)
                    Text(i.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (!st.available) "Unavailable · not installed" else st.line + (seen?.let { " · last message " + java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(it)) } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = if (st.available && st.accessGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                Switch(checked = on && st.available, enabled = st.available, onCheckedChange = { v ->
                    vm.setIntegration(i, v) { r -> if (!r.ok) Toast.makeText(context, "${r.status}: ${r.error}", Toast.LENGTH_LONG).show() }
                })
            }
            if (st.available && !st.accessGranted) when (i.kind.name) {
                "notification_listener" -> TextButton(onClick = actions.openNotificationAccess) { Text("Open notification access") }
                "cloud_function" -> {}
                else -> Row {
                    TextButton(onClick = { actions.requestPermissions(st.missingPermissions) }) { Text("Grant permission") }
                    TextButton(onClick = actions.openAppSettings) { Text("App info") }
                }
            }
        }
    }
}


@Composable
private fun DefaultSmsCard(vm: AppViewModel, actions: SetupActions) {
    val setup by vm.setup.collectAsState()
    SectionCard {
        SectionTitle("Default SMS app")
        Text("Optional. The app never requests or takes this role on install, update, or startup. It only opens the system prompt when you tap the button below. When Call Guardian is the default, Quarantine can hide scam texts from the system inbox. You can switch back anytime.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (setup.smsRole) {
            Text("Call Guardian is the default SMS app.", style = MaterialTheme.typography.bodyMedium)
            val prev = setup.previousSmsLabel
            TextButton(onClick = actions.openSmsRoleSettings) { Text(if (prev != null) "Switch back to $prev" else "Change default SMS app") }
        } else {
            TextButton(onClick = actions.requestSmsRole) { Text("Make default SMS app") }
        }
    }
}
