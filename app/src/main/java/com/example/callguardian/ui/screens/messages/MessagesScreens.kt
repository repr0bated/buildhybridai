@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

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

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import com.example.callguardian.ai.SmartReplies
import com.example.callguardian.sms.BlockedNumbers
import com.example.callguardian.sms.ContactDirectory
import com.example.callguardian.sms.ContactHit
import com.example.callguardian.sms.MessagingGateway
import com.example.callguardian.sms.MessagingGatewayEntryPoint
import com.example.callguardian.sms.MmsPartRef
import dagger.hilt.android.EntryPointAccessors
import java.util.Calendar
import kotlin.math.roundToInt

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
        onBlock = { addr ->
            scope.launch {
                withContext(Dispatchers.IO) { MessagingGateway.from(context).block(addr) }
                Toast.makeText(context, "Blocked $addr", Toast.LENGTH_SHORT).show()
                reload()
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
    onBlock: (String) -> Unit = {},
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
                SwipeableConversation(
                    c = c,
                    onOpen = { onOpen(c.address) },
                    onPin = { onPin(c.address) },
                    onArchive = { onArchive(c.address) },
                    onMute = { onMute(c.address) },
                    onDelete = { onDelete(c.address) },
                    onBlock = { onBlock(c.address) },
                )
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
    onBlock: () -> Unit = {},
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
                        if (c.isGroup) Text("GROUP", style = MaterialTheme.typography.labelSmall, color = Brand.Cloud)
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
                            DropdownMenuItem(text = { Text("Block") }, onClick = { menu = false; onBlock() })
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
    val gateway = remember { MessagingGateway.from(context) }
    val smart = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, MessagingGatewayEntryPoint::class.java).smartReplies()
    }
    val events by vm.recent.collectAsState()
    val setup by vm.setup.collectAsState()
    val key = remember(address) { MessagePresentation.threadKey(address) }
    val recipients = remember(address) { MessagePresentation.splitRecipients(address) }
    val title = remember(address) { ContactDirectory.displayName(context, address) }
    var bubbles by remember { mutableStateOf<List<ThreadBubble>>(emptyList()) }
    var body by remember { mutableStateOf(prefs.draft(key)) }
    var attachments by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var sims by remember { mutableStateOf(gateway.activeSims()) }
    var subscriptionId by remember { mutableStateOf(gateway.defaultSmsSubscriptionId()) }
    var replies by remember { mutableStateOf<List<String>>(emptyList()) }
    var blocked by remember { mutableStateOf(gateway.isBlocked(address)) }
    val threadId = remember(address) { MessagingQueries.threadIdFor(context, recipients) }
    val scope = rememberCoroutineScope()
    val pickAttachments = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        attachments = (attachments + uris).distinct()
    }
    fun reload() {
        scope.launch {
            val raw = withContext(Dispatchers.IO) { MessagingQueries.thread(context, address) }
            bubbles = raw.map { b ->
                b.copy(badge = if (b.incoming) badgeFor(address, events, b.body) else null)
            }
        }
    }
    LaunchedEffect(address) {
        threadId?.let { id ->
            val room = gateway.draftFor(id)
            if (!room.isNullOrBlank()) body = room
        }
    }
    LaunchedEffect(address, events) { reload() }
    LaunchedEffect(bubbles) {
        replies = emptyList()
        val last = bubbles.lastOrNull() ?: return@LaunchedEffect
        if (!last.incoming) return@LaunchedEffect
        replies = withContext(Dispatchers.IO) {
            smart.suggest(bubbles.takeLast(8).map { it.body }, incomingLast = true)
        }
    }
    val hidden = LocalPrivacy.current.address(address)
    MessageThreadScreen(
        address = address,
        title = title,
        isGroup = recipients.size > 1,
        bubbles = bubbles,
        body = body,
        onBody = { text ->
            body = text
            prefs.saveDraft(key, text)
            threadId?.let { id -> scope.launch { gateway.saveDraft(id, text) } }
        },
        smsRoleHeld = setup.smsRole,
        previousSmsLabel = setup.previousSmsLabel,
        onRequestSmsRole = actions.requestSmsRole,
        onBack = onBack,
        onSend = {
            val text = body.trim()
            if (text.isBlank() && attachments.isEmpty()) return@MessageThreadScreen
            scope.launch {
                val result = gateway.send(recipients, text, attachments, subscriptionId)
                if (result.ok) {
                    body = ""; prefs.saveDraft(key, ""); threadId?.let { gateway.saveDraft(it, "") }
                    attachments = emptyList()
                    reload()
                } else {
                    Toast.makeText(context, result.error ?: "Couldn't send.", Toast.LENGTH_SHORT).show()
                }
            }
        },
        onCopy = { text ->
            context.getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("message", text))
        },
        onForward = onForward,
        onDelete = { id ->
            scope.launch(Dispatchers.IO) { MessagingQueries.deleteMessage(context, id, false) }.invokeOnCompletion { reload() }
        },
        onDeleteWithMms = { id, isMms ->
            scope.launch(Dispatchers.IO) { MessagingQueries.deleteMessage(context, id, isMms) }.invokeOnCompletion { reload() }
        },
        onSubscriptions = onSubscriptions,
        privateHidden = hidden,
        canStop = bubbles.any { it.badge?.id == MessageBadge.SUBSCRIPTION || it.badge?.id == MessageBadge.CANCELED },
        attachments = attachments,
        onAttach = { pickAttachments.launch(arrayOf("image/*", "video/*", "audio/*", "*/*")) },
        onRemoveAttachment = { uri -> attachments = attachments.filterNot { it == uri } },
        sims = sims.map { Triple(it.subscriptionId, "SIM ${it.slotIndex + 1}", it.displayName) },
        selectedSim = subscriptionId,
        onSim = { subscriptionId = it },
        onSchedule = {
            val text = body.trim()
            if (text.isBlank()) {
                Toast.makeText(context, "Write a message to schedule.", Toast.LENGTH_SHORT).show()
                return@MessageThreadScreen
            }
            pickDateTime(context) { at ->
                scope.launch {
                    val result = gateway.send(recipients, text, emptyList(), subscriptionId, scheduledAtMs = at)
                    if (result.ok) {
                        body = ""; prefs.saveDraft(key, ""); threadId?.let { gateway.saveDraft(it, "") }
                        Toast.makeText(context, "Scheduled.", Toast.LENGTH_SHORT).show()
                        reload()
                    } else {
                        Toast.makeText(context, result.error ?: "Couldn't schedule.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        },
        smartReplies = replies,
        onPickReply = { body = it },
        blocked = blocked,
        onBlock = {
            scope.launch {
                if (blocked) gateway.unblock(address) else gateway.block(address)
                blocked = gateway.isBlocked(address)
                Toast.makeText(context, if (blocked) "Blocked" else "Unblocked", Toast.LENGTH_SHORT).show()
            }
        },
        onOpenAttachment = { part ->
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(part.uri, part.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        },
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
    onDelete: (Long) -> Unit = {},
    onSubscriptions: () -> Unit,
    privateHidden: Boolean,
    canStop: Boolean,
    title: String? = null,
    isGroup: Boolean = false,
    onDeleteMms: (Long, Boolean) -> Unit = { id, _ -> onDelete(id) },
    attachments: List<android.net.Uri> = emptyList(),
    onAttach: () -> Unit = {},
    onRemoveAttachment: (android.net.Uri) -> Unit = {},
    sims: List<Triple<Int, String, String>> = emptyList(),
    selectedSim: Int? = null,
    onSim: (Int) -> Unit = {},
    onSchedule: () -> Unit = {},
    smartReplies: List<String> = emptyList(),
    onPickReply: (String) -> Unit = {},
    blocked: Boolean = false,
    onBlock: () -> Unit = {},
    onOpenAttachment: (MmsPartRef) -> Unit = {},
    onDeleteWithMms: ((Long, Boolean) -> Unit)? = null,
) {
    // Prefer the (id, isMms) delete path when callers supply it via the route overload name.
    val deleteMessage: (Long, Boolean) -> Unit = { id, isMms ->
        if (onDeleteWithMms != null) onDeleteWithMms(id, isMms) else onDeleteMms(id, isMms)
    }
    val segs = MessagePresentation.segments(body)
    val heading = title ?: address
    Column(Modifier.fillMaxSize()) {
        GuardianTopBar(
            title = {
                Column {
                    Text(heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isGroup) Text("${MessagePresentation.splitRecipients(address).size} recipients", style = MaterialTheme.typography.labelSmall, color = Brand.Cloud)
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Brand.Ink) } },
            actions = {
                if (canStop) TextButton(onClick = onSubscriptions) { Text("STOP / Manage", color = Brand.Lime) }
                TextButton(onClick = onBlock) { Text(if (blocked) "Unblock" else "Block", color = Brand.Amber) }
            },
        )
        if (!smsRoleHeld) DefaultSmsBanner(previousSmsLabel, onRequestSmsRole)
        if (isGroup) {
            Text(
                MessagePresentation.joinRecipients(MessagePresentation.splitRecipients(address)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val listState = rememberLazyListState()
        LaunchedEffect(bubbles.size) { if (bubbles.isNotEmpty()) listState.animateScrollToItem(bubbles.lastIndex) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (bubbles.isEmpty()) {
                item { Text("No messages in this thread.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            itemsIndexed(bubbles, key = { _, b -> "${b.isMms}-${b.id}" }) { index, b ->
                val prev = bubbles.getOrNull(index - 1)?.date
                if (MessagePresentation.shouldShowDateDivider(prev, b.date)) {
                    Text(
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(b.date)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
                BubbleRow(b, privateHidden, onCopy, onForward, onDelete = { deleteMessage(b.id, b.isMms) }, onOpenAttachment = onOpenAttachment)
            }
        }
        if (smartReplies.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(smartReplies, key = { it }) { reply ->
                    AssistChip(onClick = { onPickReply(reply) }, label = { Text(reply) })
                }
            }
        }
        AttachmentStrip(attachments, onRemoveAttachment)
        SimPickerRow(sims, selectedSim, onSim)
        ComposeBar(
            body, onBody, segs.label,
            enabled = (body.isNotBlank() || attachments.isNotEmpty()) && smsRoleHeld,
            onSend = onSend,
            hint = if (smsRoleHeld) "Message" else "Set as default SMS app to send",
            onAttach = onAttach,
            onSchedule = onSchedule,
            attachEnabled = smsRoleHeld,
        )
    }
}

@Composable
private fun BubbleRow(
    b: ThreadBubble,
    privateHidden: Boolean,
    onCopy: (String) -> Unit,
    onForward: (String) -> Unit,
    onDelete: () -> Unit,
    onOpenAttachment: (MmsPartRef) -> Unit = {},
) {
    val incoming = b.incoming
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End,
    ) {
        Column(Modifier.widthIn(max = 320.dp), horizontalAlignment = if (incoming) Alignment.Start else Alignment.End) {
            PrivateGate(hidden = privateHidden && incoming) {
                Box {
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (incoming) Brand.Glass2 else Brand.Lime.copy(alpha = 0.16f))
                            .combinedClickable(onClick = {}, onLongClick = { menu = true })
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        if (b.body.isNotBlank()) {
                            Text(b.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                        if (b.attachments.isNotEmpty()) {
                            b.attachments.forEach { part ->
                                Text(
                                    when {
                                        part.mime.startsWith("image/") -> "Photo"
                                        part.mime.startsWith("video/") -> "Video"
                                        part.mime.startsWith("audio/") -> "Audio"
                                        else -> part.name ?: "Attachment"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Brand.Lime,
                                    modifier = Modifier.clickable { onOpenAttachment(part) }.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; onCopy(b.body) })
                        DropdownMenuItem(text = { Text("Forward") }, onClick = { menu = false; onForward(b.body) })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                b.badge?.let { InlineBadge(it) }
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(b.date)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                b.deliveryLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Brand.Cloud)
                }
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
    val gateway = remember { MessagingGateway.from(context) }
    var recipients by remember { mutableStateOf(MessagePresentation.splitRecipients(initialTo.orEmpty()).toMutableList()) }
    var toText by remember { mutableStateOf(if (recipients.isEmpty()) initialTo.orEmpty() else "") }
    var body by remember { mutableStateOf(initialBody.orEmpty()) }
    var attachments by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var contactQuery by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<ContactHit>>(emptyList()) }
    var sims by remember { mutableStateOf(gateway.activeSims()) }
    var subscriptionId by remember { mutableStateOf(gateway.defaultSmsSubscriptionId()) }
    val scope = rememberCoroutineScope()
    val pickAttachments = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        attachments = (attachments + uris).distinct()
    }
    LaunchedEffect(contactQuery, toText) {
        val q = contactQuery.ifBlank { toText }
        hits = withContext(Dispatchers.IO) { ContactDirectory.search(context, q) }
            .filter { hit -> recipients.none { MessagePresentation.threadKey(it) == MessagePresentation.threadKey(hit.number) } }
    }
    fun allDestinations(): List<String> {
        val typed = MessagePresentation.splitRecipients(toText)
        return (recipients + typed).distinct()
    }
    ComposeMessageScreen(
        to = toText,
        body = body,
        onTo = { toText = it; contactQuery = it },
        onBody = { body = it },
        smsRoleHeld = setup.smsRole,
        previousSmsLabel = setup.previousSmsLabel,
        onRequestSmsRole = actions.requestSmsRole,
        onBack = onBack,
        onSend = {
            val dests = allDestinations()
            val text = body.trim()
            if (dests.isEmpty() || (text.isBlank() && attachments.isEmpty())) return@ComposeMessageScreen
            scope.launch {
                val result = gateway.send(dests, text, attachments, subscriptionId)
                if (result.ok) onSent(MessagePresentation.joinRecipients(dests))
                else Toast.makeText(context, result.error ?: "Couldn't send.", Toast.LENGTH_SHORT).show()
            }
        },
        recipients = recipients,
        onAddRecipient = { number ->
            if (recipients.none { MessagePresentation.threadKey(it) == MessagePresentation.threadKey(number) }) {
                recipients = (recipients + number).toMutableList()
            }
            toText = ""
            contactQuery = ""
        },
        onRemoveRecipient = { number -> recipients = recipients.filterNot { it == number }.toMutableList() },
        contactHits = hits,
        attachments = attachments,
        onAttach = { pickAttachments.launch(arrayOf("image/*", "video/*", "audio/*", "*/*")) },
        onRemoveAttachment = { uri -> attachments = attachments.filterNot { it == uri } },
        sims = sims.map { Triple(it.subscriptionId, "SIM ${it.slotIndex + 1}", it.displayName) },
        selectedSim = subscriptionId,
        onSim = { subscriptionId = it },
        onSchedule = {
            val dests = allDestinations()
            val text = body.trim()
            if (dests.size != 1 || text.isBlank()) {
                Toast.makeText(context, "Scheduled send is SMS-only (one recipient, no attachments).", Toast.LENGTH_SHORT).show()
                return@ComposeMessageScreen
            }
            pickDateTime(context) { at ->
                scope.launch {
                    val result = gateway.send(dests, text, emptyList(), subscriptionId, scheduledAtMs = at)
                    if (result.ok) onSent(MessagePresentation.joinRecipients(dests))
                    else Toast.makeText(context, result.error ?: "Couldn't schedule.", Toast.LENGTH_SHORT).show()
                }
            }
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
    recipients: List<String> = emptyList(),
    onAddRecipient: (String) -> Unit = {},
    onRemoveRecipient: (String) -> Unit = {},
    contactHits: List<ContactHit> = emptyList(),
    attachments: List<android.net.Uri> = emptyList(),
    onAttach: () -> Unit = {},
    onRemoveAttachment: (android.net.Uri) -> Unit = {},
    sims: List<Triple<Int, String, String>> = emptyList(),
    selectedSim: Int? = null,
    onSim: (Int) -> Unit = {},
    onSchedule: () -> Unit = {},
) {
    val segs = MessagePresentation.segments(body)
    val canSend = (recipients.isNotEmpty() || to.isNotBlank()) && (body.isNotBlank() || attachments.isNotEmpty()) && smsRoleHeld
    Column(Modifier.fillMaxSize()) {
        GuardianTopBar(
            title = { Text("compose") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Brand.Ink) } },
        )
        if (!smsRoleHeld) DefaultSmsBanner(previousSmsLabel, onRequestSmsRole)
        Column(Modifier.padding(16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (recipients.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recipients.forEach { r ->
                        AssistChip(
                            onClick = { onRemoveRecipient(r) },
                            label = { Text(ContactDirectory.lookupName(LocalContext.current, r) ?: r, maxLines = 1) },
                            trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(14.dp)) },
                        )
                    }
                }
            }
            OutlinedTextField(
                to, onTo,
                label = { Text(if (recipients.isEmpty()) "To (name or number)" else "Add another recipient") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (contactHits.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                    contactHits.take(8).forEach { hit ->
                        Text(
                            hit.display,
                            modifier = Modifier.fillMaxWidth().clickable { onAddRecipient(hit.number) }.padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Text(
                "Attach a picture, video, or audio to send as MMS. Several recipients go as group MMS. RCS is not available to third-party Android apps.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                body, onBody,
                label = { Text(if (smsRoleHeld) "Message" else "Set as default SMS app to send") },
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).height(180.dp),
            )
            AttachmentStrip(attachments, onRemoveAttachment)
            SimPickerRow(sims, selectedSim, onSim)
        }
        ComposeBar(body, onBody, segs.label, enabled = canSend, onSend = onSend, hint = "Message", onAttach = onAttach, onSchedule = onSchedule, attachEnabled = smsRoleHeld)
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
    onAttach: () -> Unit = {},
    onSchedule: () -> Unit = {},
    attachEnabled: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().background(Brand.Void).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onAttach, enabled = attachEnabled) {
                Icon(Icons.Default.AttachFile, "Attach", tint = if (attachEnabled) Brand.Lime else Brand.InkDim)
            }
            IconButton(onClick = onSchedule, enabled = attachEnabled) {
                Icon(Icons.Default.Schedule, "Schedule", tint = if (attachEnabled) Brand.Cloud else Brand.InkDim)
            }
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

@Composable
private fun AttachmentStrip(attachments: List<android.net.Uri>, onRemove: (android.net.Uri) -> Unit) {
    if (attachments.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { uri ->
            AssistChip(
                onClick = { onRemove(uri) },
                label = { Text(uri.lastPathSegment ?: "attachment", maxLines = 1) },
                trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(14.dp)) },
            )
        }
    }
}

@Composable
private fun SimPickerRow(sims: List<Triple<Int, String, String>>, selectedSim: Int?, onSim: (Int) -> Unit) {
    if (sims.size < 2) return
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sims.forEach { (id, slot, name) ->
            FilterChip(
                selected = selectedSim == id,
                onClick = { onSim(id) },
                label = { Text("$slot · $name") },
            )
        }
    }
}

@Composable
private fun SwipeableConversation(
    c: ConversationRow,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onMute: () -> Unit,
    onDelete: () -> Unit,
    onBlock: () -> Unit = {},
) {
    var offset by remember { mutableFloatStateOf(0f) }
    val shown by animateFloatAsState(targetValue = offset, label = "swipe")
    val max = 280f
    Box {
        Row(
            Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { offset = 0f; onPin() }) { Text("Pin", color = Brand.Lime) }
            TextButton(onClick = { offset = 0f; onArchive() }) { Text("Archive", color = Brand.Cloud) }
            TextButton(onClick = { offset = 0f; onMute() }) { Text("Mute", color = Brand.Amber) }
            TextButton(onClick = { offset = 0f; onDelete() }) { Text("Delete", color = Brand.Threat) }
        }
        Box(
            Modifier
                .offset { IntOffset(shown.roundToInt(), 0) }
                .pointerInput(c.address) {
                    detectHorizontalDragGestures(
                        onDragEnd = { offset = if (offset < -80f) -max else 0f },
                        onHorizontalDrag = { _, dx -> offset = (offset + dx).coerceIn(-max, 0f) },
                    )
                },
        ) {
            ConversationCard(c, onOpen, onPin, onArchive, onMute, onDelete, onBlock)
        }
    }
}

private fun pickDateTime(context: android.content.Context, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, 10) }
    DatePickerDialog(
        context,
        { _, y, m, d ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    cal.set(Calendar.YEAR, y)
                    cal.set(Calendar.MONTH, m)
                    cal.set(Calendar.DAY_OF_MONTH, d)
                    cal.set(Calendar.HOUR_OF_DAY, hour)
                    cal.set(Calendar.MINUTE, minute)
                    cal.set(Calendar.SECOND, 0)
                    if (cal.timeInMillis <= System.currentTimeMillis()) {
                        Toast.makeText(context, "Pick a time in the future.", Toast.LENGTH_SHORT).show()
                    } else {
                        onPicked(cal.timeInMillis)
                    }
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                false,
            ).show()
        },
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH),
    ).show()
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
