package com.example.callguardian.sms

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.example.callguardian.ui.screens.messages.ComposeMessageScreen
import com.example.callguardian.ui.screens.messages.MessageThreadScreen
import com.example.callguardian.ui.screens.messages.MessagesListScreen
import com.example.callguardian.ui.screens.messages.sendSms
import com.example.callguardian.ui.screens.messages.smsRoleRequest
import com.example.callguardian.ui.theme.CallGuardianTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SENDTO entry point required for ROLE_SMS (sms/smsto/mms/mmsto).
 * UI is the same Messages screens as the in-app tab (Clear SMS-inspired, terminal theme).
 */
class ComposeSmsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val dest = destinationFrom(intent)
        val prefill = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.data?.getQueryParameter("body")
        setContent {
            CallGuardianTheme {
                val held = remember { mutableStateOf(SmsRole.held(this)) }
                val prefs = remember { ConversationPrefs(this) }
                var mode by remember { mutableStateOf(if (dest.isNullOrBlank()) "list" else "thread") }
                var address by remember { mutableStateOf(dest.orEmpty()) }
                var composeTo by remember { mutableStateOf(dest.orEmpty()) }
                var composeBody by remember { mutableStateOf(prefill.orEmpty()) }
                var rows by remember { mutableStateOf<List<ConversationRow>>(emptyList()) }
                var bubbles by remember { mutableStateOf<List<ThreadBubble>>(emptyList()) }
                var query by remember { mutableStateOf("") }
                var showArchived by remember { mutableStateOf(false) }
                var threadBody by remember { mutableStateOf("") }
                val scope = rememberCoroutineScope()
                fun reloadList() {
                    scope.launch {
                        val raw = withContext(Dispatchers.IO) { MessagingQueries.conversations(this@ComposeSmsActivity) }
                        rows = raw.map {
                            val key = MessagePresentation.threadKey(it.address)
                            it.copy(pinned = prefs.isPinned(key), archived = prefs.isArchived(key), muted = prefs.isMuted(key))
                        }
                    }
                }
                fun reloadThread(addr: String) {
                    scope.launch { bubbles = withContext(Dispatchers.IO) { MessagingQueries.thread(this@ComposeSmsActivity, addr) } }
                }
                LaunchedEffect(mode, address) {
                    if (mode == "list") reloadList()
                    if (mode == "thread" && address.isNotBlank()) {
                        threadBody = prefs.draft(MessagePresentation.threadKey(address))
                        reloadThread(address)
                    }
                }
                val filtered = rows
                    .filter { showArchived == it.archived }
                    .filter { MessagePresentation.matchesQuery(it.address, it.snippet, it.displayName, query) }
                    .sortedWith(compareByDescending<ConversationRow> { it.pinned }.thenByDescending { it.date })
                when (mode) {
                    "compose" -> ComposeMessageScreen(
                        to = composeTo, body = composeBody,
                        onTo = { composeTo = it }, onBody = { composeBody = it },
                        smsRoleHeld = held.value,
                        previousSmsLabel = null,
                        onRequestSmsRole = { smsRoleRequest(this) },
                        onBack = { if (dest.isNullOrBlank()) mode = "list" else finish() },
                        onSend = {
                            if (sendSms(this, composeTo.trim(), composeBody.trim())) {
                                address = composeTo.trim(); mode = "thread"; composeBody = ""
                            }
                        },
                    )
                    "thread" -> MessageThreadScreen(
                        address = address,
                        bubbles = bubbles,
                        body = threadBody,
                        onBody = {
                            threadBody = it
                            prefs.saveDraft(MessagePresentation.threadKey(address), it)
                        },
                        smsRoleHeld = held.value,
                        previousSmsLabel = null,
                        onRequestSmsRole = { smsRoleRequest(this) },
                        onBack = { if (dest.isNullOrBlank()) mode = "list" else finish() },
                        onSend = {
                            val text = threadBody.trim()
                            if (text.isNotBlank() && sendSms(this, address, text)) {
                                threadBody = ""; prefs.saveDraft(MessagePresentation.threadKey(address), ""); reloadThread(address)
                            }
                        },
                        onCopy = { text ->
                            getSystemService(android.content.ClipboardManager::class.java)
                                ?.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
                        },
                        onForward = { text -> composeTo = ""; composeBody = text; mode = "compose" },
                        onDelete = { id ->
                            scope.launch(Dispatchers.IO) { MessagingQueries.deleteMessage(this@ComposeSmsActivity, id) }
                                .invokeOnCompletion { reloadThread(address) }
                        },
                        onSubscriptions = {
                            startActivity(Intent(this, com.example.callguardian.MainActivity::class.java).apply {
                                putExtra("open", "subscriptions")
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            })
                        },
                        privateHidden = false,
                        canStop = false,
                    )
                    else -> MessagesListScreen(
                        rows = filtered, query = query, onQuery = { query = it },
                        showArchived = showArchived, onToggleArchiveView = { showArchived = !showArchived },
                        smsRoleHeld = held.value, previousSmsLabel = null,
                        onRequestSmsRole = { smsRoleRequest(this) },
                        onOpen = { address = it; mode = "thread" },
                        onCompose = { composeTo = ""; composeBody = ""; mode = "compose" },
                        onPin = { prefs.togglePinned(MessagePresentation.threadKey(it)); reloadList() },
                        onArchive = { prefs.toggleArchived(MessagePresentation.threadKey(it)); reloadList() },
                        onMute = { prefs.toggleMuted(MessagePresentation.threadKey(it)); reloadList() },
                        onDelete = { addr ->
                            scope.launch(Dispatchers.IO) { MessagingQueries.deleteThread(this@ComposeSmsActivity, addr) }
                                .invokeOnCompletion { reloadList() }
                        },
                    )
                }
            }
        }
    }

    companion object {
        fun destinationFrom(intent: Intent?): String? {
            val data = intent?.data
            return MessagePresentation.destinationFromUri(data?.schemeSpecificPart, intent?.getStringExtra("address"))
        }
    }
}
