package com.example.callguardian.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.callguardian.ai.DeviceModelState
import com.example.callguardian.data.db.ReceiptEntity
import com.example.callguardian.privacy.LocalPrivacy
import com.example.callguardian.privacy.PrivateGate
import com.example.callguardian.ui.AppViewModel
import com.example.callguardian.ui.HomeStats
import com.example.callguardian.ui.ProcessAllState
import com.example.callguardian.ui.SetupActions
import com.example.callguardian.ui.components.EventRow
import com.example.callguardian.ui.components.GuardianTopBar
import com.example.callguardian.ui.components.SectionCard
import com.example.callguardian.ui.components.SectionTitle
import com.example.callguardian.ui.components.relativeTime
import com.example.callguardian.ui.theme.Brand

@Composable
fun HomeScreen(vm: AppViewModel, actions: SetupActions, onEvent: (Long) -> Unit, onTab: (String) -> Unit, onSearch: () -> Unit = {}) {
    val setup by vm.setup.collectAsState()
    val model by vm.model.collectAsState()
    val recent by vm.recent.collectAsState()
    val receipts by vm.receipts.collectAsState()
    val settings by vm.settings.collectAsState()
    val quarantined by vm.quarantined.collectAsState()
    val groups by vm.subscriptions.collectAsState()
    val process by vm.processAll.collectAsState()
    var pending by remember { mutableStateOf<PendingUserMethod?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val counts = remember(recent, quarantined) { HomeStats.counts(recent, quarantined, System.currentTimeMillis()) }
    val attention = remember(recent) { HomeStats.needsAttention(recent, 5) }
    val timeline = remember(receipts) { HomeStats.recentReceipts(receipts, 6) }

    Column(Modifier.fillMaxSize()) {
        GuardianTopBar(title = { Text("home") }, onSearch = onSearch)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                StatusStrip(
                    protectionOn = setup.coreReady,
                    screenerOn = setup.callScreeningRole,
                    nano = model.state,
                    privateMode = settings.privateMode,
                    onSetup = if (setup.missing > 0) ({ onTab("settings") }) else null,
                    missing = setup.missing,
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("blocked today", counts.blockedToday.toString(), Brand.Threat, Modifier.weight(1f)) { onTab("activity/processed") }
                    StatCard("scams flagged", counts.scamsFlagged.toString(), Brand.Amber, Modifier.weight(1f)) { onTab("activity/processed") }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("unprocessed", counts.unprocessed.toString(), if (counts.unprocessed > 0) Brand.Lime else Brand.InkDim, Modifier.weight(1f)) { onTab("activity") }
                    StatCard("quarantined", counts.quarantined.toString(), Brand.InkDim, Modifier.weight(1f)) { onTab("activity/quarantined") }
                }
            }
            item { ProcessAllButton(process, onStart = { vm.startProcessAll(includeLogs = true) }, onCancel = vm::cancelProcessAll) }
            if (process.candidates.isNotEmpty()) item { OwnedCandidateList(vm, process) { pending = it } }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Needs attention", Modifier.weight(1f))
                    if (counts.unprocessed > attention.size) TextButton(onClick = { onTab("activity") }) { Text("See all") }
                }
            }
            if (attention.isEmpty()) item {
                SectionCard {
                    Text("Queue is clear.", style = MaterialTheme.typography.titleSmall)
                    Text("New calls and texts show up here when they need a look.", style = MaterialTheme.typography.bodySmall, color = Brand.InkDim)
                }
            } else {
                items(attention, key = { "att-${it.id}" }) { e -> EventRow(e) { onEvent(e.id) } }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Recent receipts", Modifier.weight(1f))
                    if (timeline.isNotEmpty()) TextButton(onClick = { onTab("activity/processed") }) { Text("See all") }
                }
            }
            if (timeline.isEmpty()) item {
                Text("No receipts yet.", style = MaterialTheme.typography.bodySmall, color = Brand.InkDim)
            } else {
                items(timeline, key = { "rcpt-${it.id}" }) { r ->
                    ReceiptRow(vm.registry, r, event = recent.firstOrNull { it.id == r.eventId }) { onTab("receipt/${r.id}") }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Subscriptions", Modifier.weight(1f))
                    if (!groups.isNullOrEmpty()) TextButton(onClick = { onTab("subscriptions") }) { Text("See all") }
                }
            }
            val list = groups
            when {
                list == null -> item { Text("Scanning inbox…", style = MaterialTheme.typography.bodySmall, color = Brand.InkDim) }
                list.isEmpty() -> item { Text("No subscription senders found.", style = MaterialTheme.typography.bodySmall, color = Brand.InkDim) }
                else -> items(list.take(6), key = { "sub-${it.serviceKey}" }) { g ->
                    val priv = LocalPrivacy.current
                    val hidden = g.senders.any { priv.address(it) } || priv.address(g.service)
                    PrivateGate(hidden = hidden) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable { onTab("subscriptions") }
                                .background(Brand.Glass.copy(alpha = 0.8f))
                                .border(1.dp, Brand.Line, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(g.service.ifBlank { g.sender }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${g.count}", style = MaterialTheme.typography.labelMedium, color = Brand.Lime)
                        }
                    }
                }
            }
            if (setup.missing > 0) item {
                Text(
                    "Setup · ${setup.missing} step${if (setup.missing == 1) "" else "s"} left. Open Settings to finish.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.Amber,
                    modifier = Modifier.clickable { actions.openAppSettings(); onTab("settings") },
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
    pending?.let { p ->
        UserMethodSheet(vm, p, onDismiss = { pending = null }) { r ->
            pending = null
            android.widget.Toast.makeText(context, if (r.ok) "Done · receipt #${r.receiptId}" else "${r.status}: ${r.error ?: ""}".take(160), android.widget.Toast.LENGTH_LONG).show()
            if (r.ok) vm.dismissOwnedCandidates()
        }
    }
}

@Composable
private fun StatusStrip(
    protectionOn: Boolean,
    screenerOn: Boolean,
    nano: DeviceModelState,
    privateMode: Boolean,
    onSetup: (() -> Unit)?,
    missing: Int,
) {
    val nanoLabel = when (nano) {
        DeviceModelState.AVAILABLE -> "nano:ready"
        DeviceModelState.DOWNLOADING -> "nano:downloading"
        DeviceModelState.UNAVAILABLE, DeviceModelState.DOWNLOADABLE -> "cloud fallback"
        DeviceModelState.UNKNOWN -> "nano:…"
        else -> "cloud fallback"
    }
    val nanoOk = nano == DeviceModelState.AVAILABLE
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(if (protectionOn) "prot:on" else "prot:off", if (protectionOn) Brand.Lime else Brand.Amber)
        StatusChip(if (screenerOn) "screener:on" else "screener:off", if (screenerOn) Brand.Lime else Brand.InkDim)
        StatusChip(nanoLabel, if (nanoOk) Brand.Lime else Brand.Cloud)
        if (privateMode) StatusChip("private", Brand.Lime)
        if (onSetup != null) StatusChip("setup:$missing", Brand.Amber, onSetup)
    }
}

@Composable
private fun StatusChip(text: String, color: Color, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.45f), shape)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        maxLines = 1,
    )
}

@Composable
private fun StatCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(Brand.Glass.copy(alpha = 0.9f))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Brand.InkDim, maxLines = 1)
    }
}

@Composable
fun ProcessAllButton(state: ProcessAllState, onStart: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.running) {
            LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth(), color = Brand.Lime, trackColor = Brand.Line)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${state.current}/${state.total}" + (state.label?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = Brand.InkDim,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Lime, contentColor = Brand.OnLime),
            ) { Text("Process all", fontWeight = FontWeight.Bold) }
            if (state.cancelled) Text("Cancelled · ${state.done} done.", style = MaterialTheme.typography.labelSmall, color = Brand.InkDim)
            else if (state.total > 0 && state.done + state.failed > 0) {
                Text("Last run · ${state.done} analyzed" + if (state.failed > 0) ", ${state.failed} failed" else "", style = MaterialTheme.typography.labelSmall, color = Brand.InkDim)
            }
        }
    }
}
