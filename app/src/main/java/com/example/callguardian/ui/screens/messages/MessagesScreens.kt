package com.example.callguardian.ui.screens.messages

import androidx.compose.foundation.ExperimentalFoundationApi

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.callguardian.data.db.ScreeningEventEntity
import com.example.callguardian.privacy.LocalPrivacy
import com.example.callguardian.privacy.PrivateGate
import com.example.callguardian.sms.ConversationPrefs
import com.example.callguardian.sms.ConversationRow
import com.example.callguardian.sms.MessageBadge
import com.example.callguardian.sms.MessagePresentation
import com.example.callguardian.sms.MessagingQueries
import com.example.callguardian.sms.SmsRole
import com.example.callguardian.sms.TelephonySms
import com.example.callguardian.sms.ThreadBubble
import com.example.callguardian.ui.AppViewModel
import com.example.callguardian.ui.SetupActions
import com.example.callguardian.ui.components.GuardianTopBar
import com.example.callguardian.ui.components.SectionCard
import com.example.callguardian.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Conversation list, thread, and compose screens for Call Guardian.
 *
 * UX is adapted from Clear SMS (itsluminous/ClearSMS, Apache-2.0; see
 * third_party/clearsms) and restyled to the terminal theme with WCAG AA contrast.
 * Incoming messages are still judged by our Firebase AI Logic pipeline; badges
 * replace Clear SMS's OTP / Spam folders with Code, Owned, Subscription, Scam, etc.
 *
 * The default SMS role stays opt-in. These screens never request or accept ROLE_SMS
 * on their own — the user has to confirm the system dialog from the banner.
 */

@Composable
fun MessagesListRoute(
    vm: AppViewModel,
    actions: SetupActions,
    onOpen: (String) -> Unit,
    onCompose: (String?) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { ConversationPrefs(context) }
    val events by vm.recent.collectAsState()
    val setup by vm.setup.collectAsState()
    var rows by remember { mutableStateOf<List<ConversationRow>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun reload() {
        scope.launch {
            val raw = withContext(Dispatchers.IO) { MessagingQueries.conversations(context) }
            val pinned = prefs.pinned()
            val archived = prefs.archived()
            val muted = prefs.muted()
            rows = raw.map { r ->
                val key = MessagePresentation.threadKey(r.address)
                r.copy(
                    pinned = key in pinned,
                    archived = key in archived,
                    muted = key in muted,
                    badge = badgeFor(r.address, events, null),
                )
            }
        }
    }
    LaunchedEffect(events) { reload() }
    val filtered = rows
        .filter { showArchived == it.archived }
        .filter { MessagePresentation.matchesQuery(it.address, it.snippet, it.displayName, query) }
        .sortedWith(compareByDescending<ConversationRow> { it.pinned }.thenByDescending { it.date })
    MessagesListScreen(
        rows = filtered,
        query = query,
        onQuery = { query = it },
        showArchived = showArchived,
        onToggleArchiveView = { showArchived = !showArchived },
        smsRoleHeld = setup.smsRole,
        previousSmsLabel = setup.previousSmsLabel,
        onRequestSmsRole = actions.requestSmsRole,
        onOpen = onOpen,
        onCompose = { onCompose(null) },
        onPin = { addr -> prefs.togglePinned(MessagePresentation.threadKey(addr)); reload() },
        onArchive = { addr -> prefs.toggleArchived(MessagePresentation.threadKey(addr)); reload() },
        onMute = { addr -> prefs.toggleMuted(MessagePresentation.threadKey(addr)); reload() },
        onDelete = { addr ->
            if (!SmsRole.held(context)) {
                Toast.makeText(context, "Set Call Guardian as the default SMS app to delete conversations.", Toast.LENGTH_SHORT).show()
            } else {
                scope.launch(Dispatchers.IO) { MessagingQueries.deleteThread(context, addr) }.invokeOnCompletion { reload() }
            }
        },
    )
}

@Composable
fun MessagesListScreen(
    rows: List<ConversationRow>,
    query: String,
    onQuery: (String) -> Unit,
    showArchived: Boolean,
    onToggleArchiveView: () -> Unit,
    smsRoleHeld: Boolean,
    previousSmsLabel: String?,
    onRequestSmsRole: () -> Unit,
    onOpen: (String) -> Unit,
    onCompose: () -> Unit,
    onPin: (String) -> Unit,
    onArchive: (String) -> Unit,
    onMute: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        GuardianTopBar(
            title = { Text(if (showArchived) "archived" else "messages") },
            actions = {
                TextButton(onClick = onToggleArchiveView) { Text(if (showArchived) "Inbox" else "Archived", color = Brand.Lime) }
                IconButton(onClick = onCompose) { Icon(Icons.Default.Add, "New message", tint = Brand.Lime) }
            },
        )
        if (!smsRoleHeld) DefaultSmsBanner(previousSmsLabel, onRequestSmsRole)
        OutlinedTextField(
            query, onQuery,
            label = { Text("Search conversations") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (rows.isEmpty()) {
                item {
                    Text(
                        if (showArchived) "No archived conversations." else "No conversations yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(rows, key = { it.address }) { c ->
                ConversationCard(c, onOpen = { onOpen(c.address) }, onPin = { onPin(c.address) }, onArchive = { onArchive(c.address) }, onMute = { onMute(c.address) }, onDelete = { onDelete(c.address) })
            }
        }
    }
}

@Composable
fun DefaultSmsBanner(previousSmsLabel: String?, onRequestSmsRole: () -> Unit) {
    SectionCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), accent = Brand.Amber) {
        Text("Default SMS app is optional", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "Call Guardian can be your SMS app if you choose it. We never take that role automatically. " +
                (previousSmsLabel?.let { "Current default: $it. " } ?: "") +
                "You can keep ${previousSmsLabel ?: "your current app"} and still screen texts from here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = onRequestSmsRole) { Text("Choose default SMS app") }
    }
}

@Composable
private fun ConversationCard(
    c: ConversationRow,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onMute: () -> Unit,
    onDelete: () -> Unit,
) {
    val hidden = LocalPrivacy.current.address(c.address)
    var menu by remember { mutableStateOf(false) }
    PrivateGate(hidden = hidden) {
        SectionCard(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            c.displayName ?: c.address,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (c.pinned) Text("PIN", style = MaterialTheme.typography.labelSmall, color = Brand.Lime)
                        if (c.muted) Text("MUTE", style = MaterialTheme.typography.labelSmall, color = Brand.InkDim)
                    }
                    Text(
                        c.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        c.badge?.let { InlineBadge(it) }
                        if (c.unread > 0) UnreadMark(c.unread)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Conversation actions", tint = Brand.InkDim) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(if (c.pinned) "Unpin" else "Pin") }, onClick = { menu = false; onPin() })
                            DropdownMenuItem(text = { Text(if (c.archived) "Move to inbox" else "Archive") }, onClick = { menu = false; onArchive() })
                            DropdownMenuItem(text = { Text(if (c.muted) "Unmute" else "Mute") }, onClick = { menu = false; onMute() })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InlineBadge(badge: MessageBadge) {
    val (bg, fg) = when (badge.id) {
        MessageBadge.SCAM -> Brand.Threat to Color(0xFF1A0000)
        MessageBadge.SUSPICIOUS -> Brand.Amber to Color(0xFF241A00)
        MessageBadge.CODE -> Brand.Cloud to Color(0xFF00202C)
        MessageBadge.OWNED -> Brand.Lime to Brand.OnLime
        MessageBadge.SUBSCRIPTION -> Brand.Indigo to Color(0xFF101428)
        MessageBadge.STOPPED, MessageBadge.CANCELED -> Brand.InkDim to Brand.Void
        else -> Brand.Glass2 to Brand.Ink
    }
    Text(
        badge.label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun UnreadMark(count: Int) {
    Box(
        Modifier.size(20.dp).clip(RoundedCornerShape(3.dp)).background(Brand.Lime),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 9) "9+" else count.toString(), style = MaterialTheme.typography.labelSmall, color = Brand.OnLime)
    }
}

@Composable
fun MessageThreadRoute(
    address: String,
    vm: AppViewModel,
    actions: SetupActions,
    onBack: () -> Unit,
    onForward: (String) -> Unit,
    onSubscriptions: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { ConversationPrefs(context) }
    val events by vm.recent.collectAsState()
    val setup by vm.setup.collectAsState()
    val key = remember(address) { MessagePresentation.threadKey(address) }
    var bubbles by remember { mutableStateOf<List<ThreadBubble>>(emptyList()) }
    var body by remember { mutableStateOf(prefs.draft(key)) }
    val scope = rememberCoroutineScope()
    fun reload() {
        scope.launch {
            val raw = withContext(Dispatchers.IO) { MessagingQueries.thread(context, address) }
            bubbles = raw.map { b ->
                b.copy(badge = if (b.incoming) badgeFor(address, events, b.body) else null)
            }
        }
    }
    LaunchedEffect(address, events) { reload() }
    val hidden = LocalPrivacy.current.address(address)
    MessageThreadScreen(
        address = address,
        bubbles = bubbles,
        body = body,
        onBody = { body = it; prefs.saveDraft(key, it) },
        smsRoleHeld = setup.smsRole,
        previousSmsLabel = setup.previousSmsLabel,
        onRequestSmsRole = actions.requestSmsRole,
        onBack = onBack,
        onSend = {
            val text = body.trim()
            if (text.isBlank()) return@MessageThreadScreen
            if (sendSms(context, address, text)) {
                body = ""; prefs.saveDraft(key, ""); reload()
            }
        },
        onCopy = { text ->
            context.getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("message", text))
        },
        onForward = onForward,
        onDelete = { id ->
            scope.launch(Dispatchers.IO) { MessagingQueries.deleteMessage(context, id) }.invokeOnCompletion { reload() }
        },
        onSubscriptions = onSubscriptions,
        privateHidden = hidden,
        canStop = bubbles.any { it.badge?.id == MessageBadge.SUBSCRIPTION || it.badge?.id == MessageBadge.CANCELED },
    )
}

@Composable
fun MessageThreadScreen(
    address: String,
    bubbles: List<ThreadBubble>,
    body: String,
    onBody: (String) -> Unit,
    smsRoleHeld: Boolean,
    previousSmsLabel: String?,
    onRequestSmsRole: () -> Unit,
    onBack: () -> Unit,
    onSend: () -> Unit,
    onCopy: (String) -> Unit,
    onForward: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onSubscriptions: () -> Unit,
    privateHidden: Boolean,
    canStop: Boolean,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(bubbles.size) { if (bubbles.isNotEmpty()) listState.scrollToItem(bubbles.lastIndex) }
    val segs = MessagePresentation.segments(body)
    Column(Modifier.fillMaxSize()) {
        GuardianTopBar(
            title = { Text(address, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Brand.Ink) } },
        )
        if (!smsRoleHeld) DefaultSmsBanner(previousSmsLabel, onRequestSmsRole)
        if (canStop) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSubscriptions) { Text("STOP / Manage") }
            }
        }
        PrivateGate(hidden = privateHidden, modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(bubbles, key = { _, b -> b.id }) { i, m ->
                    val prev = bubbles.getOrNull(i - 1)?.date
                    if (MessagePresentation.shouldShowDateDivider(prev, m.date)) {
                        Text(
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m.date)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                    BubbleRow(m, onCopy, onForward, onDelete, canDelete = smsRoleHeld)
                }
            }
        }
        ComposeBar(body, onBody, segs.label, enabled = body.isNotBlank() && smsRoleHeld, onSend = onSend, hint = if (smsRoleHeld) "Message" else "Set as default SMS app to send")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BubbleRow(
    m: ThreadBubble,
    onCopy: (String) -> Unit,
    onForward: (String) -> Unit,
    onDelete: (Long) -> Unit,
    canDelete: Boolean,
) {
    var menu by remember { mutableStateOf(false) }
    val status = when {
        m.incoming -> null
        m.status == Telephony.Sms.STATUS_FAILED -> "Failed"
        m.status == Telephony.Sms.STATUS_PENDING -> "Sending"
        else -> "Sent"
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (m.incoming) Alignment.Start else Alignment.End,
    ) {
        Box {
            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (m.incoming) Brand.Glass2 else Brand.Lime.copy(alpha = 0.16f))
                    .combinedClickable(onClick = {}, onLongClick = { menu = true })
                    .padding(10.dp),
            ) {
                Text(m.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.date)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    m.badge?.let { InlineBadge(it) }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; onCopy(m.body) })
                DropdownMenuItem(text = { Text("Forward") }, onClick = { menu = false; onForward(m.body) })
                if (canDelete) DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete(m.id) })
            }
        }
    }
}

@Composable
fun ComposeMessageRoute(
    initialTo: String?,
    initialBody: String?,
    vm: AppViewModel,
    actions: SetupActions,
    onBack: () -> Unit,
    onSent: (String) -> Unit,
) {
    val context = LocalContext.current
    val setup by vm.setup.collectAsState()
    var to by remember { mutableStateOf(initialTo.orEmpty()) }
    var body by remember { mutableStateOf(initialBody.orEmpty()) }
    ComposeMessageScreen(
        to = to,
        body = body,
        onTo = { to = it },
        onBody = { body = it },
        smsRoleHeld = setup.smsRole,
        previousSmsLabel = setup.previousSmsLabel,
        onRequestSmsRole = actions.requestSmsRole,
        onBack = onBack,
        onSend = {
            val dest = to.trim()
            val text = body.trim()
            if (dest.isBlank() || text.isBlank()) return@ComposeMessageScreen
            if (sendSms(context, dest, text)) onSent(dest)
        },
    )
}

@Composable
fun ComposeMessageScreen(
    to: String,
    body: String,
    onTo: (String) -> Unit,
    onBody: (String) -> Unit,
    smsRoleHeld: Boolean,
    previousSmsLabel: String?,
    onRequestSmsRole: () -> Unit,
    onBack: () -> Unit,
    onSend: () -> Unit,
) {
    val segs = MessagePresentation.segments(body)
    Column(Modifier.fillMaxSize()) {
        GuardianTopBar(
            title = { Text("compose") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Brand.Ink) } },
        )
        if (!smsRoleHeld) DefaultSmsBanner(previousSmsLabel, onRequestSmsRole)
        Column(Modifier.padding(16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                to, onTo,
                label = { Text("To (number or short code)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Group MMS and attachments are on the way. RCS is not available to third-party Android apps.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                body, onBody,
                label = { Text(if (smsRoleHeld) "Message" else "Set as default SMS app to send") },
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).height(180.dp),
            )
        }
        ComposeBar(body, onBody, segs.label, enabled = to.isNotBlank() && body.isNotBlank() && smsRoleHeld, onSend = onSend, hint = "Message")
    }
}

@Composable
private fun ComposeBar(
    body: String,
    onBody: (String) -> Unit,
    counter: String,
    enabled: Boolean,
    onSend: () -> Unit,
    hint: String,
) {
    Column(Modifier.fillMaxWidth().background(Brand.Void).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                body, onBody,
                label = { Text(hint) },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSend, enabled = enabled) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (enabled) Brand.Lime else Brand.InkDim)
            }
        }
        Text(counter, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun sendSms(context: android.content.Context, to: String, body: String): Boolean {
    if (!SmsRole.held(context)) {
        Toast.makeText(context, "Call Guardian is not the default SMS app.", Toast.LENGTH_SHORT).show()
        return false
    }
    return runCatching {
        @Suppress("DEPRECATION")
        val sms = if (android.os.Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sms.sendTextMessage(to, null, body, null, null)
        TelephonySms.insertSent(context, to, body)
        true
    }.getOrElse {
        Toast.makeText(context, "Couldn't send the text.", Toast.LENGTH_SHORT).show()
        false
    }
}

internal fun badgeFor(address: String, events: List<ScreeningEventEntity>, body: String?): MessageBadge? {
    val key = MessagePresentation.threadKey(address)
    val match = events
        .filter { MessagePresentation.threadKey(it.number.orEmpty()) == key }
        .filter { body == null || it.body == body }
        .maxByOrNull { it.receivedAt } ?: events.filter { MessagePresentation.threadKey(it.number.orEmpty()) == key }.maxByOrNull { it.receivedAt }
        ?: return null
    return MessagePresentation.badge(
        risk = match.risk,
        category = match.category,
        reasons = match.reasons,
        advice = match.advice,
    )
}

/** Exported so ComposeSmsActivity (SENDTO) can reuse the same screens without the nav graph. */
fun smsRoleRequest(context: android.content.Context) {
    SmsRole.requestIntent(context)?.let { context.startActivity(it) }
        ?: Toast.makeText(context, "The default SMS role is not available on this device.", Toast.LENGTH_SHORT).show()
}
