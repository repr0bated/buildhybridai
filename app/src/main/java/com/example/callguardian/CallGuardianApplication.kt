package com.example.callguardian

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.callguardian.firebase.FirebaseBootstrap
import com.example.callguardian.screening.OfficialDomains
import com.example.callguardian.notifications.AlertNotifier
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class CallGuardianApplication : Application(), Configuration.Provider {

    @Inject lateinit var notifier: AlertNotifier
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        app.clearsms.HostPackage.init(this)
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        FirebaseBootstrap.init(this)
        notifier.ensureChannels()
        runCatching { assets.open("schemas/official_domains.json").bufferedReader().use { OfficialDomains.load(it.readText()) } }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
