package com.example.callguardian.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.callguardian.data.db.AiStatus
import com.example.callguardian.data.db.ReceiptEntity
import com.example.callguardian.data.db.ScreeningEventEntity
import com.example.callguardian.methods.Dispatch
import com.example.callguardian.methods.DispatchResult
import com.example.callguardian.methods.MethodRegistry
import com.example.callguardian.methods.PolicyDecision
import com.example.callguardian.model.Channel
import com.example.callguardian.model.GuardianMethod
import com.example.callguardian.model.guardianMethodFromAlias
import com.example.callguardian.model.RiskLevel
import com.example.callguardian.model.ScamCategory
import com.example.callguardian.model.VerdictSource
import com.example.callguardian.model.label
import com.example.callguardian.schemaui.SchemaConfirmSheet
import com.example.callguardian.schemaui.SchemaDetails
import com.example.callguardian.ui.AppViewModel
import com.example.callguardian.methods.UserMethod
import com.example.callguardian.ui.components.*
import com.example.callguardian.ui.theme.Brand
import com.example.callguardian.ui.theme.color
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(vm: AppViewModel, id: Long, onBack: () -> Unit, onReceipt: (Long) -> Unit) {
    val e by vm.event(id).collectAsState(initial = null)
    val receipts by vm.receiptsFor(id).collectAsState(initial = emptyList())
    val context = LocalContext.current
    var sheet by remember { mutableStateOf<Pair<GuardianMethod, JsonObject>?>(null) }
    var userSheet by remember { mutableStateOf<PendingUserMethod?>(null) }
    val onResult: (DispatchResult) -> Unit = { r ->
        r.launch?.let { runCatching { context.startActivity(it) } }
        Toast.makeText(context, if (r.ok) "Done · receipt #${r.receiptId}" else "${r.status}: ${r.error ?: ""}".take(140), Toast.LENGTH_LONG).show()
    }

    Column(Modifier.fillMaxSize()) {
        GuardianTopBar(
            title = { Text(e?.let { if (it.channel == "CALL") "Call" else "Text message" } ?: "") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { vm.delete(id); onBack() }) { Icon(Icons.Default.DeleteOutline, "Delete") } },
        )
        val ev = e ?: return@Column
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.example.callguardian.privacy.PrivateGate(hidden = com.example.callguardian.privacy.LocalPrivacy.current.event(ev)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Header(ev)
                    Verdict(ev)
                    ev.body?.let { MessageCard(ev, it) }
                    Pipeline(vm, ev, receipts, onReceipt)
                }
            }
            PrivacyActions(vm, ev) { userSheet = it }
            FeedbackActions(vm, ev) { userSheet = it }
            Actions(vm, ev) { m -> sheet = m to vm.defaultInput(ev, m) }
            CloudCard(vm, ev, receipts, onAsk = { sheet = GuardianMethod.EXPLAIN_CLOUD to vm.defaultInput(ev, GuardianMethod.EXPLAIN_CLOUD) },
                onOffer = { m -> sheet = m to vm.defaultInput(ev, m) })
            JevCard(vm, ev, receipts) { userSheet = PendingUserMethod(UserMethod.JEV_CHECK, vm.jevInput(ev), "Send redacted text to Jev", ev.id) }
            Spacer(Modifier.height(24.dp))
        }
        userSheet?.let { p -> UserMethodSheet(vm, p, onDismiss = { userSheet = null }) { r -> userSheet = null; onResult(r) } }
        sheet?.let { (m, input) ->
            val reg = vm.registry.byMethod(m)
            if (reg != null) SchemaConfirmSheet(
                header = vm.header(reg), form = vm.inputForm(reg), initial = input,
                confirmLabel = if (m == GuardianMethod.EXPLAIN_CLOUD) "Send redacted text" else reg.spec.title,
                onDismiss = { sheet = null },
                onConfirm = { v -> sheet = null; vm.runMethod(ev.id, m, v, onResult) },
            )
        }
    }
}

@Composable
private fun Header(e: ScreeningEventEntity) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val risk = e.riskLevel()
        Box(Modifier.size(52.dp).clip(CircleShape).background(risk.color().copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(if (e.channel == "CALL") Icons.Default.Call else Icons.Default.Sms, null, tint = risk.color(), modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(e.displayName ?: e.number ?: "Hidden number", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                if (com.example.callguardian.ui.components.LocalOwned.current.forSender(e.number) != null) com.example.callguardian.ui.components.OwnedPill()
                if (e.reasons.contains("CLEAR (Code)")) com.example.callguardian.ui.components.CodePill()
            }
            Text(listOfNotNull(com.example.callguardian.model.MessageSource.fromWire(e.source).label, e.number?.takeIf { e.displayName != null }, if (e.isContact) "Contact" else "Not in contacts", fullTime(e.receivedAt)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            e.lookupSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun ScreeningEventEntity.riskLevel() = runCatching { RiskLevel.valueOf(risk) }.getOrDefault(RiskLevel.SAFE)

@Composable
private fun Verdict(e: ScreeningEventEntity) {
    val risk = e.riskLevel()
    val cat = runCatching { ScamCategory.valueOf(e.category) }.getOrDefault(ScamCategory.NONE)
    val src = runCatching { VerdictSource.valueOf(e.decidedBy) }.getOrDefault(VerdictSource.RULES)
    SectionCard(accent = risk.color()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            com.example.callguardian.ui.components.VerdictRing(
                fraction = (if (e.confidence > 0) e.confidence else 0) / 100f, color = risk.color(),
                label = "${risk.label()} risk, confidence ${e.confidence} percent",
            )
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RiskPill(risk)
                    if (e.aiStatus == AiStatus.PENDING || e.aiStatus == AiStatus.RUNNING) Pill("Analyzing", Brand.Device)
                }
                Text(when {
                    risk >= RiskLevel.HIGH -> "Likely scam" + if (cat != ScamCategory.NONE) ": ${cat.label()}" else ""
                    risk == RiskLevel.MEDIUM -> "Suspicious" + if (cat != ScamCategory.NONE) ": ${cat.label()}" else ""
                    else -> if (e.reasons.contains("CLEAR (Code)")) "Looks OK (Code)" else "Looks OK"
                }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = risk.color())
                SourcePill(src, e.aiModel.takeIf { src == VerdictSource.ON_DEVICE })
            }
        }
        e.reasons.lines().filter { it.isNotBlank() }.take(4).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        e.advice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun MessageCard(e: ScreeningEventEntity, body: String) {
    var reveal by remember { mutableStateOf(!e.quarantined) }
    SectionCard {
        SectionTitle("Message")
        if (!reveal) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer).clickable { reveal = true }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VisibilityOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text("Quarantined by guardian.sms.quarantine@v1. Don't tap links. Tap to show.", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            }
        } else Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Pipeline(vm: AppViewModel, e: ScreeningEventEntity, receipts: List<ReceiptEntity>, onReceipt: (Long) -> Unit) {
    var raw by remember { mutableStateOf(false) }
    SectionCard {
        SectionTitle("How this was decided")
        Step(1, "Rules", Brand.Rules, "score ${e.rulesScore} → ${e.rulesRisk.lowercase()} · ${e.rulesMicros} µs",
            e.rulesFlags.lines().filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "No red flags" })
        val cloud = e.aiSource == "IN_CLOUD"
        val aiLine = when (e.aiStatus) {
            AiStatus.DONE, AiStatus.REJECTED -> "${if (cloud) "Cloud fallback" else "On-device"} · ${e.aiModel?.let { prettyModel(it) } ?: "model"} · ${e.aiLatencyMs ?: 0} ms"
            AiStatus.PENDING -> "Queued: runs when the app is open"
            AiStatus.RUNNING -> "Running on this phone…"
            AiStatus.FAILED -> "Failed"
            else -> "Not needed"
        }
        Step(2, if (cloud && e.aiStatus == AiStatus.DONE) "Cloud fallback" else "On-device model",
            if (cloud) Brand.Cloud else Brand.Device, aiLine, e.aiError)
        if (e.aiStatus in listOf(AiStatus.PENDING, AiStatus.FAILED, AiStatus.NOT_RUN))
            FilledTonalButton(onClick = { vm.analyze(e.id) }) { Icon(Icons.Default.Memory, null); Spacer(Modifier.width(6.dp)); Text("Analyze on device") }
        e.schemaValid?.let { ok ->
            Step(3, "Schema check", if (ok) Brand.Teal else MaterialTheme.colorScheme.error,
                if (ok) "scam_verdict.schema.json ✓ valid" else "scam_verdict.schema.json ✗ rejected, proposal discarded", e.schemaErrors)
        }
        Step(4, "Capability policy", MaterialTheme.colorScheme.primary,
            "proposed ${e.proposedAction} → ${e.finalAction} (${e.policyDispatch.lowercase()})", e.gateNotes.ifBlank { null })
        Step(5, "Receipts", Brand.Indigo, if (receipts.isEmpty()) "No methods dispatched" else "${receipts.size} receipt(s)", null)
        receipts.forEach { r -> ReceiptRow(vm.registry, r) { onReceipt(r.id) } }
        e.aiRaw?.let {
            TextButton(onClick = { raw = !raw }) { Text(if (raw) "Hide model output" else "Show raw model output") }
            if (raw) Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun Step(n: Int, title: String, color: Color, line: String, detail: String?) {
    Row {
        Box(Modifier.size(24.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelMedium, color = color)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)

@Composable
private fun FeedbackActions(vm: AppViewModel, e: ScreeningEventEntity, open: (PendingUserMethod) -> Unit) {
    val owned = com.example.callguardian.ui.components.LocalOwned.current.forSender(e.number)
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (e.number != null) {
            FilledTonalButton(onClick = { open(PendingUserMethod(UserMethod.NOT_SCAM, vm.notScamInput(e), "Not a scam", e.id)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Not a scam")
            }
            if (owned == null) OutlinedButton(onClick = {
                val hosts = com.example.callguardian.screening.OfficialDomains.hostsIn(e.body.orEmpty())
                    .filter { com.example.callguardian.screening.OfficialDomains.isAnyOfficialHost(it) }
                val service = e.displayName ?: com.example.callguardian.screening.OfficialDomains.mentionedBrands(e.body.orEmpty()).firstOrNull() ?: "Account"
                open(PendingUserMethod(UserMethod.MARK_OWNED, vm.markOwnedInput(service, listOf(e.number!!), hosts, e.id), "Mark as owned account", e.id))
            }, modifier = Modifier.fillMaxWidth()) { Text("Mark as owned account") }
            else Text("Owned account: ${owned.service}", style = MaterialTheme.typography.bodySmall, color = Brand.Lime)
        }
    }
}

@Composable
private fun PrivacyActions(vm: AppViewModel, e: ScreeningEventEntity, open: (PendingUserMethod) -> Unit) {
    val priv = com.example.callguardian.privacy.LocalPrivacy.current
    val eventPrivate = com.example.callguardian.privacy.PrivacyKeys.event(e.id) in priv.marks
    val threadPrivate = e.number?.let { com.example.callguardian.privacy.PrivacyKeys.thread(it) in priv.marks } == true
    val contactPrivate = e.number?.let { com.example.callguardian.privacy.PrivacyKeys.contact(it) in priv.marks } == true
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (eventPrivate) OutlinedButton(onClick = { open(PendingUserMethod(UserMethod.CLEAR_PRIVATE, vm.clearPrivateInput(e), "Remove privacy", e.id)) }, modifier = Modifier.fillMaxWidth()) { Text("Remove privacy") }
        else OutlinedButton(onClick = { open(PendingUserMethod(UserMethod.SET_PRIVATE, vm.setPrivateInput(e), "Make private", e.id)) }, modifier = Modifier.fillMaxWidth()) { Text("Make private") }
        e.number?.let { n ->
            if (e.channel != "CALL") {
                if (threadPrivate) TextButton(onClick = { open(PendingUserMethod(UserMethod.CLEAR_PRIVATE, vm.clearThreadPrivateInput(n, e.source), "Show this thread", e.id)) }) { Text("Show this thread") }
                else TextButton(onClick = { open(PendingUserMethod(UserMethod.SET_PRIVATE, vm.setThreadPrivateInput(n, e.source), "Hide this thread", e.id)) }) { Text("Make thread private") }
            }
            if (contactPrivate) TextButton(onClick = { open(PendingUserMethod(UserMethod.CLEAR_PRIVATE, vm.clearContactPrivateInput(n, e.source), "Show this contact", e.id)) }) { Text("Show this contact") }
            else TextButton(onClick = { open(PendingUserMethod(UserMethod.SET_PRIVATE, vm.setContactPrivateInput(n, e.source), "Hide this contact", e.id)) }) { Text("Make contact private") }
        }
    }
}

@Composable
private fun Actions(vm: AppViewModel, e: ScreeningEventEntity, open: (GuardianMethod) -> Unit) {
    val channel = Channel.valueOf(e.channel)
    val offered = guardianMethodFromAlias(e.finalAction).takeIf { e.policyDispatch == Dispatch.OFFER.name }
    val methods = vm.registry.proposableMethods.filter { m -> m.method != GuardianMethod.EXPLAIN_CLOUD && m.spec.channels.any { it.name == channel.name } }
    SectionCard {
        SectionTitle("Actions")
        Text("Each button is a typed method from registry ${vm.registry.version}; its confirmation screen is generated from the method's JSON Schema.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        offered?.let { m -> vm.registry.byMethod(m)?.let { reg ->
            Button(onClick = { open(m) }, modifier = Modifier.fillMaxWidth()) { Text("Recommended: ${reg.spec.title}") }
        } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            methods.forEach { m ->
                val needsNumber = m.method in listOf(GuardianMethod.BLOCK_NUMBER, GuardianMethod.ALLOW_NUMBER, GuardianMethod.SAVE_CONTACT)
                OutlinedButton(onClick = { open(m.method) }, enabled = !needsNumber || e.number != null) { Text(m.spec.title) }
            }
        }
    }
}

@Composable
private fun CloudCard(vm: AppViewModel, e: ScreeningEventEntity, receipts: List<ReceiptEntity>, onAsk: () -> Unit, onOffer: (GuardianMethod) -> Unit) {
    val reg = vm.registry.byMethod(GuardianMethod.EXPLAIN_CLOUD) ?: return
    val latest = receipts.firstOrNull { it.methodId == reg.id && it.status == DispatchResult.STATUS_OK }
    val out = latest?.outputJson?.let { runCatching { MethodRegistry.json.parseToJsonElement(it).jsonObject }.getOrNull() }
    val proposed = out?.get("proposedMethod")?.jsonPrimitive?.content?.let { guardianMethodFromAlias(it) } ?: GuardianMethod.NONE
    var decision by remember(latest?.id) { mutableStateOf<PolicyDecision?>(null) }
    LaunchedEffect(latest?.id) { if (proposed != GuardianMethod.NONE) decision = vm.evaluate(e, proposed, VerdictSource.IN_CLOUD) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Cloud, null, tint = Brand.Cloud); Spacer(Modifier.width(8.dp))
            Text("Deep analysis (cloud Gemini)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        when {
            e.deepStatus == AiStatus.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Asking Gemini…")
            }
            out != null -> {
                SchemaDetails(vm.outputForm(reg), out)
                decision?.let { d ->
                    if (d.dispatch != Dispatch.DENY && d.method != GuardianMethod.NONE) vm.registry.byMethod(d.method)?.let { m ->
                        FilledTonalButton(onClick = { onOffer(d.method) }) { Text("Gemini suggests: ${m.spec.title}") }
                    } else Text("Gemini suggested ${proposed.alias}; policy denied it: ${d.notes.joinToString(" ")}", style = MaterialTheme.typography.bodySmall)
                }
            }
            else -> Text("Optional. Only a redacted copy is sent (names, codes, links and numbers masked), and only when you tap. You'll see exactly what is sent.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        e.deepError?.takeIf { e.deepStatus != AiStatus.DONE }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        val cloud = vm.cloudConfigured
        OutlinedButton(onClick = onAsk, enabled = cloud && e.deepStatus != AiStatus.RUNNING) {
            Text(if (out != null) "Ask again" else "Explain with Gemini")
        }
        if (!cloud) Text("Cloud isn't configured in this build (no google-services.json). On-device and rules still work.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Jev second opinion" (guardian.jev.check@v1). Shown when the Jev integration is on (Settings → Watchlist). */
@Composable
private fun JevCard(vm: AppViewModel, e: ScreeningEventEntity, receipts: List<ReceiptEntity>, onAsk: () -> Unit) {
    if (e.channel != "SMS" || e.body.isNullOrBlank()) return
    val state by vm.integrationState.collectAsState()
    if (state.enabled["jev"] != true) return
    val reg = vm.registry.byUser(UserMethod.JEV_CHECK) ?: return
    val latest = receipts.firstOrNull { it.methodId == reg.id }
    val out = latest?.takeIf { it.status == DispatchResult.STATUS_OK }?.outputJson?.let { runCatching { MethodRegistry.json.parseToJsonElement(it).jsonObject }.getOrNull() }
    SectionCard {
        Text("Jev second opinion (TypeSafe)", style = MaterialTheme.typography.titleMedium)
        if (out != null) SchemaDetails(vm.outputForm(reg), out)
        else Text("Nine yes/no scam-signal questions about the redacted text; Call Guardian's own truth table turns the answers into a verdict. You'll see exactly what is sent.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        latest?.takeIf { it.status != DispatchResult.STATUS_OK }?.notes?.let { Text(it.take(240), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onAsk, enabled = vm.cloudConfigured) { Text(if (out != null) "Ask Jev again" else "Ask Jev") }
    }
}
