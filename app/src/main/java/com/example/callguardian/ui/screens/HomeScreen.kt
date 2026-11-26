package com.example.callguardian.ui.screens

import com.example.callguardian.ui.components.GuardianTopBar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.callguardian.ai.DeviceModelState
import com.example.callguardian.data.db.ScreeningEventEntity
import com.example.callguardian.model.RiskLevel
import com.example.callguardian.ui.AppViewModel
import com.example.callguardian.ui.SetupActions
import com.example.callguardian.ui.components.EventRow
import com.example.callguardian.ui.components.Pill
import com.example.callguardian.ui.components.SectionCard
import com.example.callguardian.ui.components.SectionTitle
import com.example.callguardian.ui.components.prettyModel
import com.example.callguardian.ui.theme.Brand

@Composable
fun HomeScreen(vm: AppViewModel, actions: SetupActions, onEvent: (Long) -> Unit, onTab: (String) -> Unit) {
    val setup by vm.setup.collectAsState()
    val model by vm.model.collectAsState()
    val recent by vm.recent.collectAsState()
    val receipts by vm.receipts.collectAsState()
    val settings by vm.settings.collectAsState()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Hero(setup.coreReady, setup.online, settings.privateMode) }
        if (setup.missing > 0) item {
            SectionCard {
                SectionTitle("Finish setup")
                SetupRow(Icons.Default.Call, "Screen incoming calls", "Make Call Guardian your call-screening app", setup.callScreeningRole, actions.requestScreeningRole)
                SetupRow(Icons.Default.Sms, "Check incoming texts", "Read new SMS to spot scams (never sent anywhere)", setup.smsPermission, actions.requestSms)
                SetupRow(Icons.Default.Contacts, "Recognize contacts", "Never block people you know", setup.contactsPermission, actions.requestContacts)
                SetupRow(Icons.Default.Notifications, "Scam alerts", "Warn you when something is flagged", setup.notificationsPermission, actions.requestNotifications)
                if (setup.listenerAppsInstalled) SetupRow(Icons.Default.Forum, "Google Voice & WhatsApp", "Notification access: screen new messages from those apps (read-only)", setup.notificationAccess, actions.openNotificationAccess)
                if (!setup.smsPermission) Text("SMS permission greyed out? On Android 15, open App info → ⋮ → Allow restricted settings (needed for sideloaded apps).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!setup.smsPermission) TextButton(onClick = actions.openAppSettings) { Text("Open App info") }
            }
        }
        item { ModelCard(vm, model) }
        item { Stats(recent, receipts.count { it.status == "OK" }) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Recent", Modifier.weight(1f))
                if (recent.isNotEmpty()) TextButton(onClick = { onTab("history") }) { Text("See all") }
            }
        }
        if (recent.isEmpty()) item {
            SectionCard {
                Text("Nothing screened yet.", style = MaterialTheme.typography.titleSmall)
                Text("Open Activity → ⋮ → Demo samples to see the on-device model, schema validation and receipts in action.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = { onTab("activity") }) { Icon(Icons.Default.Science, null); Spacer(Modifier.width(8.dp)); Text("Open Activity") }
            }
        }
        items(recent.take(6), key = { it.id }) { e -> EventRow(e) { onEvent(e.id) } }
    }
}

@Composable
private fun Hero(ready: Boolean, online: Boolean, privateMode: Boolean) {
    val accent = if (ready) Brand.Lime else Brand.Amber
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.16f), Brand.Glass.copy(alpha = 0.9f), Brand.Void)))
            .border(1.dp, Brush.linearGradient(listOf(accent.copy(alpha = 0.7f), Brand.Line)), RoundedCornerShape(14.dp))
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (ready) "[ armed ]" else "[ setup ]", style = MaterialTheme.typography.labelMedium, color = accent)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ready) Icons.Default.VerifiedUser else Icons.Default.GppMaybe, null, tint = accent, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text("call_guardian", style = MaterialTheme.typography.headlineMedium, color = Brand.Ink, fontWeight = FontWeight.ExtraBold)
            }
            Text(if (ready) "Protection is on. Calls and texts are checked on this phone first." else "Finish setup to start screening calls and texts.",
                style = MaterialTheme.typography.bodyMedium, color = Brand.InkDim)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(if (privateMode) "Private mode" else "Hybrid mode", accent, Icons.Default.Lock)
                Pill(if (online) "Online" else "Offline · on-device", if (online) Brand.Cloud else Brand.InkDim, if (online) Icons.Default.Wifi else Icons.Default.AirplanemodeActive)
            }
        }
    }
}

@Composable
private fun SetupRow(icon: ImageVector, title: String, subtitle: String, done: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (done) Icon(Icons.Default.CheckCircle, "Done", tint = Brand.Teal) else FilledTonalButton(onClick = onClick) { Text("Allow") }
    }
}

@Composable
private fun ModelCard(vm: AppViewModel, m: com.example.callguardian.ui.DeviceModelUi) {
    val settings by vm.settings.collectAsState()
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Memory, null, tint = Brand.Device)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("On-device model", style = MaterialTheme.typography.titleMedium)
                Text(settings.modelChoice.title + " · Firebase AI Logic", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val (label, color) = when (m.state) {
                DeviceModelState.AVAILABLE -> "Ready" to Brand.Teal
                DeviceModelState.DOWNLOADING -> "Downloading" to Brand.Cloud
                DeviceModelState.DOWNLOADABLE -> "Not downloaded" to MaterialTheme.colorScheme.tertiary
                DeviceModelState.UNAVAILABLE -> "Unavailable" to MaterialTheme.colorScheme.error
                DeviceModelState.UNKNOWN -> "Checking" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Pill(label, color)
        }
        m.modelName?.let { Text("Running ${prettyModel(it)} ($it)", style = MaterialTheme.typography.bodySmall) }
        if (m.downloading || m.state == DeviceModelState.DOWNLOADING) {
            val p = if (m.totalBytes > 0) (m.downloadedBytes.toFloat() / m.totalBytes).coerceIn(0f, 1f) else null
            if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            if (m.totalBytes > 0) Text("${m.downloadedBytes / 1_048_576} / ${m.totalBytes / 1_048_576} MB", style = MaterialTheme.typography.labelSmall)
        }
        when (m.state) {
            DeviceModelState.DOWNLOADABLE -> Button(onClick = vm::downloadModel, enabled = !m.downloading) { Text("Download model") }
            DeviceModelState.UNAVAILABLE -> Text(
                "Gemma 4 / Gemini Nano 4 preview needs a supported phone (e.g. Galaxy S25 Ultra) with AICore Developer Preview: " +
                    "Developer options → AICore Settings → Enable AICore Developer Preview, then update AICore. Until then, rules screen everything and cloud analysis is available on request.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {}
        }
        m.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { vm.refreshModel() }) { Text("Re-check") }
    }
}

@Composable
private fun Stats(events: List<ScreeningEventEntity>, executed: Int) {
    val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
    val week = events.filter { it.receivedAt > weekAgo && !it.isTest }
    val flagged = week.count { runCatching { RiskLevel.valueOf(it.risk) }.getOrDefault(RiskLevel.SAFE) >= RiskLevel.MEDIUM }
    val onDevice = events.count { it.aiSource == "ON_DEVICE" }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat("Screened\nthis week", week.size.toString(), Modifier.weight(1f))
        Stat("Flagged\nthis week", flagged.toString(), Modifier.weight(1f))
        Stat("On-device\nverdicts", onDevice.toString(), Modifier.weight(1f))
        Stat("Methods\nexecuted", executed.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(vm: AppViewModel, onEvent: (Long) -> Unit) {
    val recent by vm.recent.collectAsState()
    var filter by remember { mutableStateOf("All") }
    val shown = when (filter) {
        "Flagged" -> recent.filter { runCatching { RiskLevel.valueOf(it.risk) }.getOrDefault(RiskLevel.SAFE) >= RiskLevel.MEDIUM }
        "Calls" -> recent.filter { it.channel == "CALL" }
        "Texts" -> recent.filter { it.channel == "SMS" }
        else -> recent
    }
    Column(Modifier.fillMaxSize()) {
        GuardianTopBar(title = { Text("History") })
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Flagged", "Calls", "Texts").forEach { f -> FilterChip(filter == f, { filter = f }, label = { Text(f) }) }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (shown.isEmpty()) item { Text("No events.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(shown, key = { it.id }) { e -> EventRow(e) { onEvent(e.id) } }
        }
    }
}
