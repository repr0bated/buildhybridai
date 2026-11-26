package com.example.callguardian

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.callguardian.firebase.FirebaseBootstrap
import com.example.callguardian.integrations.IntegrationPrefs
import com.example.callguardian.notifications.AlertNotifier
import com.example.callguardian.screening.OfficialDomains
import com.example.callguardian.screening.ScreeningEngine
import com.example.callguardian.sms.BlockedNumbers
import com.example.callguardian.sms.ContactDirectory
import com.example.callguardian.sms.ConversationNotifier
import com.example.callguardian.sms.MessagePresentation
import com.example.callguardian.sms.MessagingGateway
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class CallGuardianApplication : Application(), Configuration.Provider {

    @Inject lateinit var notifier: AlertNotifier
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var gateway: MessagingGateway
    @Inject lateinit var screening: ScreeningEngine
    @Inject lateinit var integrations: IntegrationPrefs
    @Inject lateinit var conversationNotifier: ConversationNotifier

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        app.clearsms.HostPackage.init(this)
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        FirebaseBootstrap.init(this)
        notifier.ensureChannels()
        runCatching { assets.open("schemas/official_domains.json").bufferedReader().use { OfficialDomains.load(it.readText()) } }
        gateway.startEngineMaintenance()
        app.clearsms.HostIncoming.mmsListener = app.clearsms.IncomingMmsListener { sender, body, _, recipients ->
            appScope.launch {
                val others = recipients.filter { it.isNotBlank() && MessagePresentation.threadKey(it) != MessagePresentation.threadKey(sender) }
                val address = if (others.isNotEmpty()) MessagePresentation.joinRecipients(listOf(sender) + others) else sender
                if (integrations.enabled("phone_sms")) {
                    integrations.seen("phone_sms")
                    screening.screenSms(sender, body, smsUri = null)
                }
                if (!BlockedNumbers.isBlocked(this@CallGuardianApplication, sender)) {
                    conversationNotifier.notifyIncoming(address, body, ContactDirectory.displayName(this@CallGuardianApplication, address))
                }
            }
        }
        appScope.launch {
            gateway.enableDeliveryReports()
            gateway.rearmScheduled()
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
