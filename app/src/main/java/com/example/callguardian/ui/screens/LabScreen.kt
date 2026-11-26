package com.example.callguardian.ui.screens

import com.example.callguardian.ui.components.GuardianTopBar
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.callguardian.methods.CallLogItem
import com.example.callguardian.methods.DispatchResult
import com.example.callguardian.methods.UserMethod
import com.example.callguardian.methods.UserMethodInputs
import com.example.callguardian.model.MessageSource
import com.example.callguardian.model.RiskLevel
import com.example.callguardian.schemaui.SchemaConfirmSheet
import com.example.callguardian.subscriptions.LinkSafety
import com.example.callguardian.subscriptions.SubscriptionDetector
import com.example.callguardian.subscriptions.SubscriptionGroup
import com.example.callguardian.ui.AppViewModel
import com.example.callguardian.ui.components.SectionCard
import kotlinx.serialization.json.JsonObject

data class LabSample(val title: String, val sender: String, val body: String)

/** A user-only method waiting for the schema-generated confirmation sheet. */
data class PendingUserMethod(val method: UserMethod, val input: JsonObject, val confirmLabel: String, val eventId: Long? = null)

private enum class View { TABS, SUBSCRIPTIONS, SAMPLES }

/**
 * "Activity" (watchlist = false): the phone's own Texts and Calls. "Watchlist" (watchlist = true):
 * notification-based linked apps. Both are one tab per integration in integrations.json that is a message source (phone SMS,
 * calls, Google Voice, WhatsApp, WhatsApp Business), shown when it is enabled and installed.
 * Subscriptions and the demo samples live in the top-bar menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabScreen(vm: AppViewModel, onEvent: (Long) -> Unit, watchlist: Boolean = false, onReceipt: (Long) -> Unit = {}) {
    val state by vm.integrationState.collectAsState()
    val tabs = vm.catalog.all.filter { i ->
        (if (watchlist) i.kind.name == "notification_listener" else i.kind.name == "phone_sms" || i.kind.name == "phone_calls") &&
            (state.enabled[i.id.name] ?: i.defaultEnabled) && (i.packages.isNullOrEmpty() || i.packages!!.any(vm::installed))
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var view by rememberSaveable { mutableStateOf(View.TABS) }
    var menu by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingUserMethod?>(null) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        GuardianTopBar(
            title = { Text(when (view) { View.TABS -> if (watchlist) "Watchlist" else "Activity"; View.SUBSCRIPTIONS -> "Subscriptions"; View.SAMPLES -> "Demo samples" }) },
            navigationIcon = { if (view != View.TABS) IconButton(onClick = { view = View.TABS }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (view == View.TABS) TextButton(onClick = { view = View.SUBSCRIPTIONS }) { Text("Subscriptions") }
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Subscriptions") }, onClick = { menu = false; view = View.SUBSCRIPTIONS })
                    DropdownMenuItem(text = { Text("Demo samples") }, onClick = { menu = false; view = View.SAMPLES })
                }
            },
        )
        when (view) {
            View.SUBSCRIPTIONS -> Subscriptions(vm) { pending = it }
            View.SAMPLES -> Samples(vm, onEvent)
            View.TABS -> {
                if (tabs.isEmpty()) { Box(Modifier.padding(16.dp)) { Hint(if (watchlist) "No linked apps are installed and on. Google Voice, WhatsApp and WhatsApp Business show up here; turn them on in Settings → Watchlist." else "Texts and Calls are off. Turn them on in Settings → Watchlist.") }; return@Column }
                // Activity also has "Processed": the history of everything analyzed or acted on, all sources.
                val count = tabs.size + if (watchlist) 0 else 1
                val sel = tab.coerceIn(0, count - 1)
                ScrollableTabRow(selectedTabIndex = sel, edgePadding = 8.dp) {
                    tabs.forEachIndexed { i, t -> Tab(selected = sel == i, onClick = { tab = i }, text = { Text(if (t.id.name == "phone_sms") "Texts" else t.label) }) }
                    if (!watchlist) Tab(selected = sel == tabs.size, onClick = { tab = tabs.size }, text = { Text("Processed") })
                }
                if (sel == tabs.size) { Processed(vm, onEvent, onReceipt); return@Column }
                val t = tabs[sel]
                when (t.kind.name) {
                    "phone_sms" -> MyTexts(vm, onEvent) { pending = it }
                    "phone_calls" -> Calls(vm) { pending = it }
                    else -> AppMessages(vm, t, onEvent) { pending = it }
                }
            }
        }
    }
    pending?.let { p ->
        UserMethodSheet(vm, p, onDismiss = { pending = null }) { r ->
            pending = null
            r.launch?.let { runCatching { context.startActivity(it) } }
            Toast.makeText(context, if (r.ok) "Done · receipt #${r.receiptId}" else "${r.status}: ${r.error ?: ""}".take(160), Toast.LENGTH_LONG).show()
            if (r.ok && p.method == UserMethod.REVIEW_CALL) {
                (r.output as? JsonObject)?.get("eventId")?.toString()?.toLongOrNull()?.let(onEvent)
            }
            if (r.ok && p.method != UserMethod.REVIEW_CALL) vm.loadSubscriptions()
        }
    }
}

/** Google Voice / WhatsApp tab: messages seen in that app's notifications (new ones only). Tap opens the chat in its app. */
@Composable
private fun AppMessages(vm: AppViewModel, i: com.example.callguardian.integrations.Integration, onEvent: (Long) -> Unit, open: (PendingUserMethod) -> Unit) {
    val context = LocalContext.current
    val list by vm.integrationEvents(i.id.name).collectAsState(initial = null)
    val access = vm.notificationAccessGranted()
    val pkg = i.packages.orEmpty().firstOrNull(vm::installed)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Hint("New ${i.label} messages, read from its notifications and screened on this phone. Tap one to open the chat in ${i.label}; Check shows the rules, Gemini Nano 4 and the actions.") }
        if (!access) item {
            SectionCard {
                Text("Notification access is off", style = MaterialTheme.typography.titleSmall)
                Text("Call Guardian needs notification access to see new ${i.label} messages. Read-only; it never replies.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { vm.openNotificationAccess(context) }) { Text("Open notification access") }
            }
        }
        val l = list
        when {
            l == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            l.isEmpty() -> item { Hint("Nothing yet. Only messages that arrive from now on show up here.") }
            else -> items(l, key = { it.id }) { e ->
                SectionCard(Modifier.clickable {
                    val number = e.number?.filter { it.isDigit() }.orEmpty()
                    val deep = i.chatDeepLink?.takeIf { number.length >= 7 }?.replace("{number}", number)
                    val intent = deep?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)).apply { pkg?.let(::setPackage) } }
                        ?: pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
                    intent?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                }) {
                    Row { Text(e.displayName ?: e.number ?: "Unknown", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); Text(time(e.receivedAt), style = MaterialTheme.typography.labelSmall) }
                    Text(e.body.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Risk ${e.risk.lowercase()}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        if (SubscriptionDetector.isSubscription(e.number ?: e.displayName.orEmpty(), e.body.orEmpty()))
                            SubscriptionButton(vm, e.number ?: e.displayName ?: "", MessageSource.fromWire(e.source), open)
                        TextButton(onClick = { onEvent(e.id) }) { Text("Check") }
                    }
                }
            }
        }
    }
}

/** Opens the subscription actions for one sender (same card as the Subscriptions view). */
@Composable
private fun SubscriptionButton(vm: AppViewModel, sender: String, source: MessageSource, open: (PendingUserMethod) -> Unit) {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = true; if (vm.subscriptions.value == null) vm.loadSubscriptions() }) { Text("Unsubscribe…") }
    if (show) {
        val groups by vm.subscriptions.collectAsState()
        val confirm by vm.subscriptionConfirm.collectAsState()
        val g = groups?.firstOrNull { it.sender == sender.trim() && it.source == source }
        AlertDialog(onDismissRequest = { show = false }, confirmButton = { TextButton(onClick = { show = false }) { Text("Close") } },
            text = { if (g == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else SubscriptionCard(vm, g, confirm[g.sender], null) { show = false; open(it) } })
    }
}

/** Confirmation sheet generated from the method's input schema; dispatch writes a receipt either way. */
@Composable
fun UserMethodSheet(vm: AppViewModel, p: PendingUserMethod, onDismiss: () -> Unit, onResult: (DispatchResult) -> Unit) {
    val reg = vm.registry.byUser(p.method) ?: return
    SchemaConfirmSheet(header = vm.header(reg), form = vm.inputForm(reg), initial = p.input, confirmLabel = p.confirmLabel,
        onDismiss = onDismiss, onConfirm = { v -> vm.runUser(p.method, v, p.eventId, onResult) })
}

@Composable
private fun PermissionGate(vm: AppViewModel, permission: String, why: String, content: @Composable () -> Unit) {
    var granted by remember { mutableStateOf(vm.granted(permission)) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }
    if (granted) { content(); return }
    val context = LocalContext.current
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            Text("Permission needed", style = MaterialTheme.typography.titleSmall)
            Text(why, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { launcher.launch(permission) }) { Text("Allow") }
            if (denied) {
                Text("Android didn't grant it. On a sideloaded app, open App info → ⋮ → Allow restricted settings, then allow it there.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) { Text("Open App info") }
            }
            Text("The Samples tab works without it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun time(t: Long) = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(t))

@Composable
private fun MyTexts(vm: AppViewModel, onEvent: (Long) -> Unit, open: (PendingUserMethod) -> Unit) = PermissionGate(vm, Manifest.permission.READ_SMS,
    "Read your SMS inbox so you can run any real text through the rules and Gemini Nano 4. It stays on this phone.") {
    val inbox by vm.inbox.collectAsState()
    LaunchedEffect(Unit) { vm.loadInbox() }
    val list = inbox
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Hint("Tap a text to check it: rules → Gemini Nano 4 (built on Gemma 4) → schema → policy → receipt. Reviews never block anyone or post alerts. Cloud Explain is still opt-in and redacted.") }
        when {
            list == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            list.isEmpty() -> item { Hint("No texts in the inbox.") }
            else -> items(list, key = { it.id }) { m ->
                SectionCard(Modifier.clickable { vm.reviewText(m, onEvent) }) {
                    Row { Text(m.address, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); Text(time(m.date), style = MaterialTheme.typography.labelSmall) }
                    Text(m.body, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (SubscriptionDetector.isSubscription(m.address, m.body)) SubscriptionButton(vm, m.address, MessageSource.SMS, open)
                }
            }
        }
    }
}

@Composable
private fun Calls(vm: AppViewModel, open: (PendingUserMethod) -> Unit) = PermissionGate(vm, Manifest.permission.READ_CALL_LOG,
    "Read your call log so you can review a call: number, caller name, contact status, how often it called, and the rules. No audio. Stays on this phone.") {
    val calls by vm.calls.collectAsState()
    LaunchedEffect(Unit) { vm.loadCalls() }
    val list = calls
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Hint("Tap a call to review it with guardian.call.review@v1. You'll see exactly what is checked first.") }
        when {
            list == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            list.isEmpty() -> item { Hint("No calls in the call log.") }
            else -> items(list, key = { it.id }) { c -> CallRow(c) { open(PendingUserMethod(UserMethod.REVIEW_CALL, UserMethodInputs.reviewCall(c), "Review call")) } }
        }
    }
}

@Composable
private fun CallRow(c: CallLogItem, onClick: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onClick)) {
        Row {
            Text(c.cachedName ?: c.number ?: "Hidden number", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(time(c.date), style = MaterialTheme.typography.labelSmall)
        }
        Text(listOfNotNull(c.number?.takeIf { c.cachedName != null }, c.type.name.lowercase().replaceFirstChar { it.uppercase() },
            if (c.isContact) "Contact" else "Not in contacts", "${c.callsFromNumber30d}× in 30 days").joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Subscriptions(vm: AppViewModel, open: (PendingUserMethod) -> Unit) = PermissionGate(vm, Manifest.permission.READ_SMS,
    "Read your SMS inbox to find subscription and marketing senders. Google Voice and WhatsApp messages Call Guardian has seen are included too.") {
    val groups by vm.subscriptions.collectAsState()
    val confirm by vm.subscriptionConfirm.collectAsState()
    val marks by vm.subscriptionMarks.collectAsState()
    LaunchedEffect(Unit) { vm.loadSubscriptions() }
    val list = groups
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Hint("Found by the rule engine (short codes, \"Reply STOP\", \"Msg&data rates\", unsubscribe links), then confirmed by Gemini Nano 4 on this phone. Nothing is sent or opened until you confirm.") }
        when {
            list == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            list.isEmpty() -> item { Hint("No subscription senders found.") }
            else -> items(list, key = { it.source.wire + it.sender }) { g ->
                SubscriptionCard(vm, g, confirm[g.sender], marks.let { m -> when (g.sender) { in m.unsubscribed -> "Unsubscribed"; in m.muted -> "Muted"; in m.kept -> "Kept"; else -> null } }, open)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionCard(vm: AppViewModel, g: SubscriptionGroup, c: com.example.callguardian.screening.ScreeningEngine.SubscriptionConfirm?, mark: String?, open: (PendingUserMethod) -> Unit) {
    val context = LocalContext.current
    val integration = vm.catalog.all.firstOrNull { it.source?.name == g.source.wire && it.packages.orEmpty().any(vm::installed) }
        ?: vm.catalog.all.firstOrNull { it.source?.name == g.source.wire }
    val link = c?.link ?: g.bestLink
    val chosenBy = if (c?.link != null) "ON_DEVICE" else "RULES"
    val safety: LinkSafety? = link?.let { SubscriptionDetector.linkSafety(it, g.brand, g.scamRisk) }
    SectionCard {
        Row {
            Column(Modifier.weight(1f)) {
                Text(g.brand?.let { "$it · ${g.sender}" } ?: g.sender, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${g.source.label} · ${g.count} text${if (g.count == 1) "" else "s"} · last ${time(g.lastDate)}", style = MaterialTheme.typography.labelSmall)
            }
            mark?.let { AssistChip(onClick = {}, label = { Text(it) }) }
        }
        Text(g.lastBody, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when {
            c == null -> "Gemini Nano 4: checking on this phone…"
            c.isSubscription -> "Gemini Nano 4: subscription confirmed"
            else -> "Gemini Nano 4: doesn't look like a subscription"
        } + " · rules: ${g.signals.joinToString()}", style = MaterialTheme.typography.labelSmall)
        if (g.scamRisk >= RiskLevel.MEDIUM) Text("These texts also look like a scam. Don't reply to them; block the sender instead.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        link?.let { l ->
            Text("Unsubscribe page: ${l.domain}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            safety?.warning?.let { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (g.canReplyStop && g.scamRisk < RiskLevel.MEDIUM) {
                val kw = g.keyword ?: "STOP"
                Button(onClick = { open(PendingUserMethod(UserMethod.UNSUBSCRIBE_SMS, UserMethodInputs.unsubscribe(g), "Send \"$kw\" to ${g.sender}")) }) { Text("Text $kw") }
            }
            if (g.source.fromNotifications && integration != null) {
                val pkg = integration.packages.orEmpty().firstOrNull(vm::installed)
                OutlinedButton(onClick = {
                    val number = g.sender.filter { it.isDigit() }
                    val deep = integration.chatDeepLink?.takeIf { number.length >= 7 }?.replace("{number}", number)
                    val intent = deep?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)).apply { pkg?.let(::setPackage) } }
                        ?: pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
                    intent?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                }, enabled = pkg != null) { Text(integration.optOutHint ?: "Open ${integration.label}") }
            }
            if (link != null && safety != null) OutlinedButton(onClick = {
                open(PendingUserMethod(UserMethod.MANAGE_SUBSCRIPTION, UserMethodInputs.manageSubscription(g.sender, g.source.wire, link, chosenBy, safety), "Open ${link.domain}"))
            }) { Text("Manage subscription") }
            TextButton(onClick = { open(PendingUserMethod(UserMethod.MUTE_SENDER, UserMethodInputs.mute(g.sender, g.source.wire, "KEEP"), "Save")) }) { Text("Keep") }
            TextButton(onClick = { open(PendingUserMethod(UserMethod.MUTE_SENDER, UserMethodInputs.mute(g.sender, g.source.wire, "MUTE"), "Save")) }) { Text("Mute") }
        }
    }
}

@Composable
private fun Samples(vm: AppViewModel, onEvent: (Long) -> Unit) {
    var sender by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Hint("Built-in demo texts. Test events never block real numbers or post alerts. Works in airplane mode.") }
        items(vm.labSamples) { s ->
            SectionCard(Modifier.clickable { vm.runTest(s.sender, s.body, onEvent) }) {
                Text(s.title, style = MaterialTheme.typography.titleSmall)
                Text(s.body, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SectionCard {
                Text("Your own text", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(sender, { sender = it }, label = { Text("Sender (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(body, { body = it }, label = { Text("Message") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.runTest(sender, body, onEvent) }, enabled = body.isNotBlank()) { Text("Screen it") }
            }
        }
    }
}

@Composable
private fun Hint(s: String) = Text(s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

private data class ProcessedEntry(val time: Long, val source: MessageSource, val title: String, val verdict: String?, val action: String, val eventId: Long?, val receiptId: Long?)

/** Activity → Processed: screening events and receipts from Room, newest first, filterable by source. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Processed(vm: AppViewModel, onEvent: (Long) -> Unit, onReceipt: (Long) -> Unit) {
    val events by vm.recent.collectAsState()
    val receipts by vm.receipts.collectAsState()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val entries = remember(events, receipts) {
        val byEvent = receipts.filter { it.eventId != null }.groupBy { it.eventId!! }
        val fromEvents = events.map { e ->
            val rs = byEvent[e.id].orEmpty().filter { it.status == "OK" }
            val action = e.userAction?.let { com.example.callguardian.ui.screens.actionLabel(it) }
                ?: rs.firstOrNull()?.let { r -> vm.registry.byId(r.methodId)?.spec?.title }
                ?: if (e.finalAction != "none") "${e.finalAction} (${e.policyDispatch.lowercase()})" else "Analyzed"
            ProcessedEntry(e.receivedAt, MessageSource.fromWire(e.source), e.displayName ?: e.number ?: "Hidden",
                "${e.risk.lowercase()}${if (e.aiStatus == "DONE") " · Gemini Nano 4" else ""}", action, e.id, rs.firstOrNull()?.id ?: byEvent[e.id]?.firstOrNull()?.id)
        }
        val standalone = receipts.filter { it.eventId == null }.map { r ->
            val input = runCatching { com.example.callguardian.methods.MethodRegistry.json.parseToJsonElement(r.inputJson) as JsonObject }.getOrNull()
            fun f(k: String) = (input?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content
            val title = f("recipient") ?: f("sender") ?: f("integration") ?: r.methodId
            val src = f("source")?.let(MessageSource::fromWire) ?: if (r.methodId.startsWith("guardian.integration")) null else MessageSource.SMS
            val method = vm.registry.byId(r.methodId)?.spec?.title ?: r.methodId
            val action = when (r.methodId) {
                "guardian.sms.unsubscribe@v1" -> "Unsubscribed (texted ${f("keyword")})"
                "guardian.sms.manage_subscription@v1" -> "Opened ${f("domain")}"
                "guardian.sms.mute@v1" -> if (f("mode") == "KEEP") "Kept" else if (f("mode") == "MUTE") "Muted" else "Cleared"
                else -> method
            } + if (r.status != "OK") " · ${r.status.lowercase()}" else ""
            src?.let { ProcessedEntry(r.createdAt, it, title, null, action, null, r.id) }
        }.filterNotNull()
        (fromEvents + standalone).sortedByDescending { it.time }
    }
    val sources = entries.map { it.source }.distinct()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(filter == null, { filter = null }, label = { Text("All") })
                sources.forEach { s -> FilterChip(filter == s.wire, { filter = s.wire }, label = { Text(s.label) }) }
            }
        }
        val shown = entries.filter { filter == null || it.source.wire == filter }
        if (shown.isEmpty()) item { Hint("Nothing processed yet.") }
        items(shown, key = { "${it.eventId}-${it.receiptId}-${it.time}" }) { p ->
            SectionCard(Modifier.clickable { p.eventId?.let(onEvent) ?: p.receiptId?.let(onReceipt) }) {
                Row { Text(p.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); Text(time(p.time), style = MaterialTheme.typography.labelSmall) }
                Text(listOfNotNull(p.source.label, p.verdict?.let { "verdict $it" }, p.action).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                p.receiptId?.let { id -> TextButton(onClick = { onReceipt(id) }) { Text("Receipt #$id") } }
            }
        }
    }
}

fun actionLabel(alias: String): String = when (alias) {
    "block_number", "BLOCKED_BY_LIST" -> "Blocked"
    "allow_number" -> "Trusted"
    "quarantine_sms" -> "Quarantined"
    "reject_call" -> "Silenced"
    "report_scam" -> "Reported"
    "save_contact" -> "Saved contact"
    "explain_cloud" -> "Explained (cloud)"
    else -> alias
}
