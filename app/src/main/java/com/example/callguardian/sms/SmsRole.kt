package com.example.callguardian.sms

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.provider.Telephony

/** Helpers for the optional default-SMS role. The app never grants itself the role. */
object SmsRole {
    const val ROLE = RoleManager.ROLE_SMS

    fun held(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(ROLE) == true

    fun available(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleAvailable(ROLE) == true

    fun currentPackage(context: Context): String? = Telephony.Sms.getDefaultSmsPackage(context)

    /** Null when the role is unavailable or we already hold it. */
    fun requestIntent(context: Context): Intent? {
        val rm = context.getSystemService(RoleManager::class.java) ?: return null
        if (!rm.isRoleAvailable(ROLE) || rm.isRoleHeld(ROLE)) return null
        return rm.createRequestRoleIntent(ROLE)
    }

    /** Opens the system picker so the user can switch back to the previous default. We cannot set it ourselves. */
    fun manageIntent(): Intent =
        if (Build.VERSION.SDK_INT >= 31)
            Intent("android.settings.MANAGE_DEFAULT_APP").putExtra("android.provider.extra.ROLE_NAME", ROLE)
        else Intent(Settings.ACTION_SETTINGS)

    fun labelFor(context: Context, pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull() ?: pkg
    }

    /**
     * Keep the previous default only when it isn't this app. Pure so it can be unit-tested.
     * [current] is the package that holds ROLE_SMS now; [self] is Call Guardian.
     */
    fun rememberPrevious(current: String?, self: String, alreadyStored: String?): String? = when {
        !current.isNullOrBlank() && current != self -> current
        else -> alreadyStored
    }
}

data class InboxMatch(val id: Long, val address: String, val body: String, val date: Long)

/**
 * Picks which telephony inbox row to hide for a quarantine. Prefer an exact URI id when we
 * have one, otherwise address+body with the closest date.
 */
object InboxMatcher {
    fun digits(s: String) = s.filter { it.isDigit() }

    fun pick(rows: List<InboxMatch>, address: String, body: String, date: Long, uriId: Long? = null): InboxMatch? {
        if (uriId != null) rows.firstOrNull { it.id == uriId }?.let { return it }
        val want = digits(address)
        val candidates = rows.filter { digits(it.address) == want && it.body == body }
        if (candidates.isEmpty()) return rows.filter { digits(it.address) == want && it.body.take(40) == body.take(40) }
            .minByOrNull { kotlin.math.abs(it.date - date) }
        return candidates.minByOrNull { kotlin.math.abs(it.date - date) }
    }
}
