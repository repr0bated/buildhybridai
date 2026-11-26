package com.example.callguardian.sms

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.callguardian.ui.AppViewModel
import com.example.callguardian.ui.SetupActions
import com.example.callguardian.ui.screens.messages.ComposeMessageRoute
import com.example.callguardian.ui.screens.messages.MessageThreadRoute
import com.example.callguardian.ui.screens.messages.MessagesListRoute
import com.example.callguardian.ui.screens.messages.smsRoleRequest
import com.example.callguardian.ui.theme.CallGuardianTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * SENDTO entry point required for ROLE_SMS (sms/smsto/mms/mmsto).
 * Uses the same Messages routes as the in-app tab so contact picker,
 * attachments, dual-SIM, scheduled send, smart replies, and Room drafts
 * are available here too. The default SMS role is never taken automatically.
 */
@AndroidEntryPoint
class ComposeSmsActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val dest = destinationFrom(intent)
        val prefill = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.data?.getQueryParameter("body")
        val actions = SetupActions(
            requestScreeningRole = {},
            requestSms = { permissionLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)) },
            requestContacts = { permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS)) },
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            },
            openAppSettings = {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            },
            requestSmsRole = { smsRoleRequest(this) },
        )
        setContent {
            CallGuardianTheme {
                var mode by remember {
                    mutableStateOf(
                        when {
                            !prefill.isNullOrBlank() -> "compose"
                            !dest.isNullOrBlank() -> "thread"
                            else -> "list"
                        },
                    )
                }
                var address by remember { mutableStateOf(dest.orEmpty()) }
                var composeTo by remember { mutableStateOf(dest.orEmpty()) }
                var composeBody by remember { mutableStateOf(prefill.orEmpty()) }
                com.example.callguardian.ui.lookup.ReverseLookupHost(vm) {
                when (mode) {
                    "compose" -> ComposeMessageRoute(
                        initialTo = composeTo.ifBlank { null },
                        initialBody = composeBody.ifBlank { null },
                        vm = vm,
                        actions = actions,
                        onBack = { if (dest.isNullOrBlank() && prefill.isNullOrBlank()) mode = "list" else finish() },
                        onSent = { dests ->
                            address = dests
                            mode = "thread"
                            composeBody = ""
                        },
                    )
                    "thread" -> MessageThreadRoute(
                        address = address,
                        vm = vm,
                        actions = actions,
                        onBack = { if (dest.isNullOrBlank()) mode = "list" else finish() },
                        onForward = { text ->
                            composeTo = ""
                            composeBody = text
                            mode = "compose"
                        },
                        onSubscriptions = {
                            startActivity(
                                Intent(this, com.example.callguardian.MainActivity::class.java).apply {
                                    putExtra("open", "subscriptions")
                                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                },
                            )
                        },
                    )
                    else -> MessagesListRoute(
                        vm = vm,
                        actions = actions,
                        onOpen = { address = it; mode = "thread" },
                        onCompose = { to ->
                            composeTo = to.orEmpty()
                            composeBody = ""
                            mode = "compose"
                        },
                    )
                }
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
