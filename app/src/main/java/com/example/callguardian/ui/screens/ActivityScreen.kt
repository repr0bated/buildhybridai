package com.example.callguardian.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.callguardian.data.db.QuarantinedSmsEntity
import com.example.callguardian.model.MessageSource
import com.example.callguardian.privacy.LocalPrivacy
import com.example.callguardian.privacy.PrivateGate
import com.example.callguardian.ui.AppViewModel
import com.example.callguardian.ui.HomeStats
import com.example.callguardian.ui.components.EventRow
import com.example.callguardian.ui.components.GuardianTopBar
import com.example.callguardian.ui.components.SectionCard
import com.example.callguardian.ui.components.relativeTime
import com.example.callguardian.ui.theme.Brand

@Composable
fun ActivityScreen(vm: AppViewModel, onEvent: (Long) -> Unit, onReceipt: (Long) -> Unit = {}, initialTab: String? = null, onSearch: () -> Unit = {}) {
    val tabs = listOf("unprocessed", "processed", "quarantined")
    var tab by rememberSaveable { mutableIntStateOf(tabs.indexOf(initialTab).let { if (it >= 0) it else 0 }) }
    LaunchedEffect(initialTab) {
        val i = tabs.indexOf(initialTab)
        if (i >= 0) tab = i
    }
    val process by vm.processAll.collectAsState()
    Column(Modifier.fillMaxSize()) {
        GuardianTopBar(title = { Text("activity") }, onSearch = onSearch)
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp, containerColor = Brand.Void, contentColor = Brand.Lime) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tabs[tab]) {
            "unprocessed" -> {
                val recent by vm.recent.collectAsState()
                val open = recent.filter(HomeStats::isUnprocessed)
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { ProcessAllButton(process, onStart = { vm.startProcessAll(includeLogs = false) }, onCancel = vm::cancelProcessAll) }
                    if (open.isEmpty()) item { Text("Nothing waiting. Incoming calls and texts land here until analysis finishes.", style = MaterialTheme.typography.bodySmall, color = Brand.InkDim) }
                    items(open, key = { it.id }) { e -> EventRow(e) { onEvent(e.id) } }
                }
            }
            "processed" -> Processed(vm, onEvent, onReceipt)
            else -> QuarantineList(vm, onEvent)
        }
    }
}

@Composable
private fun QuarantineList(vm: AppViewModel, onEvent: (Long) -> Unit) {
    val rows by vm.quarantined.collectAsState()
    val setup by vm.setup.collectAsState()
    val priv = LocalPrivacy.current
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!setup.smsRole) item {
            Text("Quarantine hides scam texts from the system inbox only when Call Guardian is the default SMS app. Flag-only until then.", style = MaterialTheme.typography.bodySmall, color = Brand.InkDim)
        }
        if (rows.isEmpty()) item { Text(if (setup.smsRole) "Quarantine folder is empty." else "No hidden texts.", style = MaterialTheme.typography.bodySmall, color = Brand.InkDim) }
        items(rows, key = { it.id }) { row -> QuarantineRow(row, priv.address(row.address), onOpen = { row.eventId?.let(onEvent) }, onRestore = { vm.restoreQuarantine(row.id) }, canRestore = setup.smsRole) }
    }
}

@Composable
private fun QuarantineRow(row: QuarantinedSmsEntity, hidden: Boolean, onOpen: () -> Unit, onRestore: () -> Unit, canRestore: Boolean) {
    PrivateGate(hidden = hidden) {
        SectionCard(Modifier.clickable(onClick = onOpen)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.address, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(relativeTime(row.hiddenAt), style = MaterialTheme.typography.labelSmall, color = Brand.InkDim)
            }
            Text(row.body, style = MaterialTheme.typography.bodySmall, color = Brand.InkDim, maxLines = 3)
            if (canRestore) TextButton(onClick = onRestore) { Text("Restore to inbox") }
        }
    }
}

@Composable
private fun Processed(vm: AppViewModel, onEvent: (Long) -> Unit, onReceipt: (Long) -> Unit) {
    val events by vm.recent.collectAsState()
    val receipts by vm.receipts.collectAsState()
    val done = events.filter { !HomeStats.isUnprocessed(it) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (done.isEmpty() && receipts.none { it.eventId == null }) item { Text("Processed calls, texts and receipts show up here.", style = MaterialTheme.typography.bodySmall, color = Brand.InkDim) }
        items(done, key = { "ev-${it.id}" }) { e -> EventRow(e) { onEvent(e.id) } }
        val standalone = receipts.filter { it.eventId == null }
        if (standalone.isNotEmpty()) {
            item { Spacer(Modifier.height(8.dp)); Text("// receipts", style = MaterialTheme.typography.titleSmall, color = Brand.Lime) }
            items(standalone, key = { "r-${it.id}" }) { r ->
                ReceiptRow(vm.registry, r) { onReceipt(r.id) }
            }
        }
    }
}
