package com.example.callguardian.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.callguardian.privacy.PrivateGate
import com.example.callguardian.ui.AppViewModel
import com.example.callguardian.ui.SearchChip
import com.example.callguardian.ui.SearchHit
import com.example.callguardian.ui.SearchKind
import com.example.callguardian.ui.components.EventRow
import com.example.callguardian.ui.components.GuardianTopBar
import com.example.callguardian.ui.components.relativeTime
import com.example.callguardian.ui.theme.Brand
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onEvent: (Long) -> Unit,
    onReceipt: (Long) -> Unit,
    onSubscriptions: () -> Unit,
) {
    val query by vm.searchQuery.collectAsState()
    val chips by vm.searchChips.collectAsState()
    val hits by vm.searchHits.collectAsState()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        focus.requestFocus()
        vm.refreshSearch()
    }
    Column(Modifier.fillMaxSize()) {
        GuardianTopBar(
            title = { Text("search") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        OutlinedTextField(
            value = query,
            onValueChange = { vm.setSearchQuery(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
            placeholder = { Text("number, name, body, service, verdict") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { vm.setSearchQuery("") }) { Icon(Icons.Default.Close, "Clear") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); scope.launch { vm.refreshSearch() } }),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SearchChip.entries) { chip ->
                FilterChip(
                    selected = chip in chips,
                    onClick = { vm.toggleSearchChip(chip) },
                    label = { Text(chip.label) },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hits.isEmpty()) {
                item {
                    Text(
                        if (query.isBlank() && chips.isEmpty()) "Type to search calls, texts, Voice, WhatsApp, receipts and subscriptions."
                        else "No matches.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.InkDim,
                    )
                }
            } else {
                item { Text("${hits.size} match${if (hits.size == 1) "" else "es"}", style = MaterialTheme.typography.labelSmall, color = Brand.InkDim) }
            }
            items(hits, key = { "${it.kind}-${it.id}-${it.eventId}-${it.receiptId}" }) { hit ->
                SearchHitRow(vm, hit, onEvent, onReceipt, onSubscriptions)
            }
        }
    }
}

private val SearchChip.label: String
    get() = when (this) {
        SearchChip.CALLS -> "Calls"
        SearchChip.TEXTS -> "Texts"
        SearchChip.SCAM -> "Scam"
        SearchChip.SUBSCRIPTION -> "Subscription"
        SearchChip.QUARANTINED -> "Quarantined"
        SearchChip.PRIVATE -> "Private"
    }

@Composable
private fun SearchHitRow(
    vm: AppViewModel,
    hit: SearchHit,
    onEvent: (Long) -> Unit,
    onReceipt: (Long) -> Unit,
    onSubscriptions: () -> Unit,
) {
    val onClick = {
        when (hit.kind) {
            SearchKind.CALL, SearchKind.TEXT -> hit.eventId?.let(onEvent)
            SearchKind.RECEIPT -> hit.receiptId?.let(onReceipt)
            SearchKind.SUBSCRIPTION -> onSubscriptions()
            SearchKind.QUARANTINED -> hit.eventId?.let(onEvent)
        }
        Unit
    }
    PrivateGate(hidden = hit.private) {
        Column(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hit.kind.name.lowercase(), style = MaterialTheme.typography.labelSmall, color = Brand.Lime)
                Text(hit.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(relativeTime(hit.time), style = MaterialTheme.typography.labelSmall, color = Brand.InkDim)
            }
            Text(hit.snippet, style = MaterialTheme.typography.bodySmall, color = Brand.InkDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Suppress("unused")
private fun eventRowIfPossible(vm: AppViewModel, hit: SearchHit, onEvent: (Long) -> Unit) {
    // Event rows are used on other screens; hits here stay compact so receipts/subs share a layout.
    hit.eventId ?: return
}
