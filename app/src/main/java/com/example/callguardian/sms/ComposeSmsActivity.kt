package com.example.callguardian.sms

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.callguardian.ui.components.GuardianTopBar
import com.example.callguardian.ui.components.SectionCard
import com.example.callguardian.ui.theme.CallGuardianTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Basic compose + thread view required for ROLE_SMS (SENDTO sms/smsto/mms/mmsto).
 */
class ComposeSmsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val dest = destinationFrom(intent)
        setContent {
            CallGuardianTheme {
                if (dest.isNullOrBlank()) Inbox(onOpen = { startActivity(Intent(this, ComposeSmsActivity::class.java).setData(Uri.parse("smsto:$it"))) }, onBack = { finish() })
                else Thread(dest, onBack = { finish() }, onSend = { to, body -> send(to, body) })
            }
        }
    }

    private fun send(to: String, body: String): Boolean {
        if (!SmsRole.held(this)) {
            Toast.makeText(this, "Call Guardian is not the default SMS app.", Toast.LENGTH_SHORT).show()
            return false
        }
        return runCatching {
            @Suppress("DEPRECATION")
            val sms = if (android.os.Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sms.sendTextMessage(to, null, body, null, null)
            TelephonySms.insertSent(this, to, body)
            true
        }.getOrElse {
            Toast.makeText(this, "Couldn't send the text.", Toast.LENGTH_SHORT).show()
            false
        }
    }

    companion object {
        fun destinationFrom(intent: Intent?): String? {
            val data = intent?.data ?: return intent?.getStringExtra("address")
            val raw = data.schemeSpecificPart?.substringBefore('?') ?: return null
            return raw.trim().takeIf { it.isNotBlank() }
        }
    }
}

private data class Convo(val address: String, val snippet: String, val date: Long)
private data class Bubble(val body: String, val incoming: Boolean, val date: Long)

@Composable
private fun Inbox(onOpen: (String) -> Unit, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var rows by remember { mutableStateOf<List<Convo>>(emptyList()) }
    var to by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { rows = withContext(Dispatchers.IO) { loadConvos(context) } }
    Column(Modifier.fillMaxSize()) {
        GuardianTopBar(title = { Text("Messages") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(to, { to = it }, label = { Text("New message to") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FilledTonalButton(onClick = { if (to.isNotBlank()) onOpen(to.trim()) }, enabled = to.isNotBlank()) { Text("Compose") }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rows.isEmpty()) item { Text("No conversations yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows, key = { it.address }) { c ->
                SectionCard(Modifier.clickable { onOpen(c.address) }) {
                    Text(c.address, style = MaterialTheme.typography.titleSmall)
                    Text(c.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun Thread(dest: String, onBack: () -> Unit, onSend: (String, String) -> Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var rows by remember { mutableStateOf<List<Bubble>>(emptyList()) }
    var body by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun reload() { scope.launch { rows = withContext(Dispatchers.IO) { loadThread(context, dest) } } }
    LaunchedEffect(dest) { reload() }
    Column(Modifier.fillMaxSize()) {
        GuardianTopBar(title = { Text(dest) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        LazyColumn(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows.size) { i ->
                val m = rows[i]
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.incoming) Alignment.Start else Alignment.End) {
                    Text(m.body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(body, { body = it }, label = { Text("Message") }, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                if (body.isNotBlank() && onSend(dest, body.trim())) { body = ""; reload() }
            }, enabled = body.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}

private fun loadConvos(context: android.content.Context): List<Convo> {
    val seen = LinkedHashMap<String, Convo>()
    val proj = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
    context.contentResolver.query(Telephony.Sms.CONTENT_URI, proj, null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
        val iA = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        val iB = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
        val iD = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
        var n = 0
        while (c.moveToNext() && n++ < 400) {
            val a = c.getString(iA)?.trim().orEmpty()
            if (a.isBlank() || seen.containsKey(a)) continue
            seen[a] = Convo(a, c.getString(iB).orEmpty(), c.getLong(iD))
        }
    }
    return seen.values.toList()
}

private fun loadThread(context: android.content.Context, dest: String): List<Bubble> {
    val want = InboxMatcher.digits(dest)
    val out = mutableListOf<Bubble>()
    val proj = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE)
    context.contentResolver.query(Telephony.Sms.CONTENT_URI, proj, null, null, "${Telephony.Sms.DATE} ASC")?.use { c ->
        val iA = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        val iB = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
        val iD = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
        val iT = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
        while (c.moveToNext()) {
            if (InboxMatcher.digits(c.getString(iA).orEmpty()) != want) continue
            val incoming = c.getInt(iT) == Telephony.Sms.MESSAGE_TYPE_INBOX
            out += Bubble(c.getString(iB).orEmpty(), incoming, c.getLong(iD))
        }
    }
    return out.takeLast(200)
}
