package com.example.callguardian.sms

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.smsRoleStore by preferencesDataStore("sms_role")

@Singleton
class SmsRolePrefs @Inject constructor(@ApplicationContext private val context: Context) {
    private val previousKey = stringPreferencesKey("previous_default_package")
    val previousPackage = context.smsRoleStore.data.map { it[previousKey] }

    suspend fun currentPrevious(): String? = previousPackage.first()

    /** Call just before launching the ROLE_SMS request so we can offer a way back. */
    suspend fun rememberCurrentDefault() {
        val stored = currentPrevious()
        val next = SmsRole.rememberPrevious(SmsRole.currentPackage(context), context.packageName, stored)
        if (next != stored && next != null) context.smsRoleStore.edit { it[previousKey] = next }
    }
}
