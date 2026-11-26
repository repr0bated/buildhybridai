package com.example.callguardian

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.callguardian.sms.SmsRole
import com.example.callguardian.sms.SmsRolePrefs
import com.example.callguardian.ui.AppViewModel
import com.example.callguardian.ui.CallGuardianApp
import com.example.callguardian.ui.SetupActions
import com.example.callguardian.ui.theme.CallGuardianTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val vm: AppViewModel by viewModels()
    private val openEvent = mutableStateOf<Long?>(null)
    private val openAddress = mutableStateOf<String?>(null)

    @Inject lateinit var smsRolePrefs: SmsRolePrefs

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refresh() }
    private val smsRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        val actions = SetupActions(
            requestScreeningRole = ::requestScreeningRole,
            requestSms = { permissionLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS)) },
            requestContacts = { permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS)) },
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            },
            openAppSettings = {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            },
            requestPermissions = { perms -> if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray()) },
            openNotificationAccess = {
                val cn = android.content.ComponentName(this, com.example.callguardian.notify.MessageNotificationListener::class.java)
                val detail = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString()) else null
                runCatching { startActivity(detail ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    .onFailure { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            },
            requestSmsRole = ::requestSmsRole,
            openSmsRoleSettings = { startActivity(SmsRole.manageIntent()) },
            unlockPrivate = ::unlockPrivate,
            signInCompanion = {
                lifecycleScope.launch {
                    runCatching { vm.signInCompanion(this@MainActivity) }
                        .onFailure { Toast.makeText(this@MainActivity, "Could not sign in for web companion alerts.", Toast.LENGTH_LONG).show() }
                }
            },
            signOutCompanion = {
                lifecycleScope.launch { vm.signOutCompanion(this@MainActivity) }
            },
        )
        setContent {
            CallGuardianTheme {
                CallGuardianApp(vm, actions, openEvent.value, onEventOpened = { openEvent.value = null }, openAddress = openAddress.value, onAddressOpened = { openAddress.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refresh() // also drains on-device analysis queued while we were in the background
    }

    private fun handleIntent(i: Intent?) {
        val id = i?.getLongExtra(EXTRA_EVENT_ID, -1) ?: -1
        if (id > 0) openEvent.value = id
        val address = i?.getStringExtra(com.example.callguardian.sms.ConversationNotifier.EXTRA_OPEN_ADDRESS)
        if (!address.isNullOrBlank()) openAddress.value = address
    }

    private fun requestScreeningRole() {
        val rm = getSystemService(RoleManager::class.java) ?: return
        if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
        }
    }

    /** Opt-in only. Remembers the previous default first; never grants the role itself. */
    private fun requestSmsRole() {
        val intent = SmsRole.requestIntent(this) ?: return
        lifecycleScope.launch {
            smsRolePrefs.rememberCurrentDefault()
            smsRoleLauncher.launch(intent)
        }
    }

    private fun unlockPrivate() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        when (BiometricManager.from(this).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> {
                Toast.makeText(this, "Set a screen lock (PIN, password, fingerprint or face) in system settings first.", Toast.LENGTH_LONG).show()
                return
            }
            else -> {
                Toast.makeText(this, "Can't unlock private items on this device right now.", Toast.LENGTH_LONG).show()
                return
            }
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                vm.unlockPrivate()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED) return
                Toast.makeText(this@MainActivity, errString, Toast.LENGTH_LONG).show()
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock private items")
                .setSubtitle("Use fingerprint, face, or your device PIN")
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
    }
}
