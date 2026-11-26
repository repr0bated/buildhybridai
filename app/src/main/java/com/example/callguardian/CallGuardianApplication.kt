package com.example.callguardian

import android.app.Application
import com.example.callguardian.firebase.FirebaseBootstrap
import com.example.callguardian.screening.OfficialDomains
import com.example.callguardian.notifications.AlertNotifier
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class CallGuardianApplication : Application() {

    @Inject lateinit var notifier: AlertNotifier

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        FirebaseBootstrap.init(this)
        notifier.ensureChannels()
        runCatching { assets.open("schemas/official_domains.json").bufferedReader().use { OfficialDomains.load(it.readText()) } }
    }
}
