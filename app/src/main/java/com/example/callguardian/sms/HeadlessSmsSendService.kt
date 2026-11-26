package com.example.callguardian.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.SmsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * RESPOND_VIA_MESSAGE — required of a default SMS app so other apps can send a text through us.
 */
class HeadlessSmsSendService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val dest = intent?.data?.schemeSpecificPart?.substringBefore('?')?.trim().orEmpty()
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.getStringExtra("sms_body")
            ?: ""
        if (dest.isBlank() || body.isBlank() || !SmsRole.held(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                @Suppress("DEPRECATION")
                val sms = if (android.os.Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                sms.sendTextMessage(dest, null, body, null, null)
                TelephonySms.insertSent(this@HeadlessSmsSendService, dest, body)
            } catch (t: Throwable) {
                Timber.w(t, "RESPOND_VIA_MESSAGE send failed")
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
}
