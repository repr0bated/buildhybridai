package com.example.callguardian.ui.lookup

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.callguardian.data.db.ScreeningEventEntity
import com.example.callguardian.lookup.LookupProvider
import com.example.callguardian.lookup.MergedLookup
import com.example.callguardian.lookup.ReverseLookup
import com.example.callguardian.methods.UserMethod
import com.example.callguardian.model.GuardianMethod
import com.example.callguardian.privacy.LocalPrivacy
import com.example.callguardian.privacy.PrivateGate
import com.example.callguardian.schemaui.SchemaConfirmSheet
import com.example.callguardian.ui.AppViewModel
import com.example.callguardian.ui.components.SectionCard
import com.example.callguardian.ui.screens.PendingUserMethod
import com.example.callguardian.ui.screens.UserMethodSheet
import com.example.callguardian.ui.theme.Brand
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val LocalReverseLookup = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun ReverseLookupHost(vm: AppViewModel, content: @Composable () -> Unit) {
    var number by remember { mutableStateOf<String?>(null) }
    var pendingUser by remember { mutableStateOf<PendingUserMethod?>(null) }
    var pendingBlock by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    CompositionLocalProvider(LocalReverseLookup provides { n -> number = n }) {
        content()
    }
    number?.let { n ->
        ReverseLookupSheet(
            vm = vm,
            number = n,
            onDismiss = { number = null },
            onBlock = {
                pendingBlock = buildJsonObject {
                    put("number", n)
                    put("scope", "ALL")
                    put("reason", "Reverse lookup")
                }
                number = null
            },
            onOwned = { name ->
                pendingUser = PendingUserMethod(
                    UserMethod.MARK_OWNED,
                    vm.markOwnedInput(name.ifBlank { n }, listOf(n), emptyList(), null),
                    "Mark owned",
                )
                number = null
            },
            onNotScam = { ev ->
                pendingUser = PendingUserMethod(UserMethod.NOT_SCAM, vm.notScamInput(ev), "Not a scam", ev.id)
                number = null
            },
        )
    }
    pendingBlock?.let { input ->
        val reg = vm.registry.byMethod(GuardianMethod.BLOCK_NUMBER)
        if (reg == null) {
            pendingBlock = null
        } else {
            SchemaConfirmSheet(
                header = vm.header(reg),
                form = vm.inputForm(reg),
                initial = input,
                confirmLabel = "Block",
                onDismiss = { pendingBlock = null },
                onConfirm = { v ->
                    vm.runMethod(null, GuardianMethod.BLOCK_NUMBER, v) { pendingBlock = null }
                },
            )
        }
    }
    pendingUser?.let { p ->
        UserMethodSheet(vm, p, onDismiss = { pendingUser = null }, onResult = { pendingUser = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReverseLookupSheet(
    vm: AppViewModel,
    number: String,
    onDismiss: () -> Unit,
    onBlock: () -> Unit,
    onOwned: (displayName: String) -> Unit,
    onNotScam: (ScreeningEventEntity) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val settings by vm.settings.collectAsState()
    val twilio by vm.twilioKeySaved.collectAsState()
    val telnyx by vm.telnyxKeySaved.collectAsState()
    val configured = settings.anyLookupConfigured || twilio || telnyx
    val hidden = LocalPrivacy.current.address(number)
    var merged by remember { mutableStateOf<MergedLookup?>(null) }
    var history by remember { mutableStateOf<List<ScreeningEventEntity>>(emptyList()) }
    var watched by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(configured) }
    var watchedFlash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(number) {
        history = vm.historyFor(number)
        watched = vm.isWatched(number)
        merged = vm.cachedLookup(number)
        if (configured) {
            loading = true
            merged = vm.refreshLookup(number)
            loading = false
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Brand.Glass,
        tonalElevation = 0.dp,
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle(color = Brand.Lime.copy(alpha = 0.6f)) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Reverse lookup", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            PrivateGate(hidden = hidden) {
                Text(number, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!configured) {
                    NoKeyCard()
                } else {
                    if (loading && merged?.isEmpty != false) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Brand.Lime)
                            Text("Looking up…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val result = merged
                    if (result != null && !result.isEmpty) {
                        SectionCard {
                            FieldRow("Caller name (CNAM)", result.displayName.value, result.displayName.sourceLabel)
                            FieldRow("Carrier", result.carrier.value, result.carrier.sourceLabel)
                            FieldRow("Line type", result.lineType.value, result.lineType.sourceLabel)
                            FieldRow("Location", result.region.value, result.region.sourceLabel)
                            FieldRow("Validity", result.valid.value, result.valid.sourceLabel)
                            if (loading) {
                                Spacer(Modifier.height(8.dp))
                                Text("Refreshing…", style = MaterialTheme.typography.labelSmall, color = Brand.InkDim)
                            }
                        }
                        if (result.contributions.isNotEmpty()) {
                            SectionCard {
                                Text("Sources", style = MaterialTheme.typography.titleSmall)
                                result.contributions.forEach { c ->
                                    val label = LookupProvider.fromId(c.resolvedSourceId)?.displayName ?: c.source
                                    val bits = listOfNotNull(c.displayName, c.carrier, ReverseLookup.lineTypeLabel(c.lineType) ?: c.lineType)
                                    Text(
                                        "$label · ${bits.joinToString(" · ").ifBlank { "No name, carrier, or line type" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } else if (!loading) {
                        Text("No lookup result for this number.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HistoryCard(history)
                watchedFlash?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Lime) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBlock) { Text("Block") }
                    OutlinedButton(onClick = {
                        vm.setWatched(number, true) {
                            watched = true
                            watchedFlash = "Added to watchlist."
                        }
                    }, enabled = !watched) { Text(if (watched) "On watchlist" else "Add to Watchlist") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOwned(merged?.displayName?.value ?: "") }) { Text("Mark Owned") }
                    val ev = history.firstOrNull()
                    OutlinedButton(onClick = { ev?.let(onNotScam) }, enabled = ev != null) { Text("Not a scam") }
                }
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String?, source: String?) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Brand.InkDim)
        Text(value?.takeIf { it.isNotBlank() } ?: "Not available", style = MaterialTheme.typography.bodyMedium)
        if (!source.isNullOrBlank() && !value.isNullOrBlank()) {
            Text(source, style = MaterialTheme.typography.labelSmall, color = Brand.Cloud)
        }
    }
}

@Composable
private fun HistoryCard(history: List<ScreeningEventEntity>) {
    val fmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    SectionCard {
        Text("Our history", style = MaterialTheme.typography.titleSmall)
        val blocks = ReverseLookup.blockCount(history)
        Text(
            "${history.size} event${if (history.size == 1) "" else "s"} · blocked $blocks time${if (blocks == 1) "" else "s"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (history.isEmpty()) {
            Text("No screening events for this number yet.", style = MaterialTheme.typography.bodySmall, color = Brand.InkDim)
        } else {
            history.take(8).forEach { e ->
                val line = listOfNotNull(
                    e.risk.takeIf { it.isNotBlank() },
                    e.category.takeIf { it.isNotBlank() && it != "NONE" },
                    e.finalAction.takeIf { it.isNotBlank() && it != "none" },
                    e.channel,
                    runCatching { fmt.format(Date(e.receivedAt)) }.getOrNull(),
                ).joinToString(" · ")
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NoKeyCard() {
    val context = LocalContext.current
    SectionCard {
        Text(
            "Add a lookup key in Settings to query a provider. Existing history and actions still work.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LookupProvider.entries.filter { it.getKeyUrl.isNotBlank() }.forEach { p ->
            TextButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.getKeyUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("Get a ${p.displayName} key") }
        }
    }
}
