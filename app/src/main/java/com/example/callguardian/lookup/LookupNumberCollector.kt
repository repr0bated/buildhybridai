package com.example.callguardian.lookup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.example.callguardian.data.db.LookupCacheDao
import com.example.callguardian.sms.ContactDirectory
import com.example.callguardian.sms.MessagePresentation
import com.example.callguardian.sms.MessagingQueries
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Collects unique E.164 numbers from contacts and SMS/MMS senders for bulk lookup. */
@Singleton
class LookupNumberCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cache: LookupCacheDao,
) {
    suspend fun preview(sourceId: String): LookupCostEstimate = withContext(Dispatchers.IO) {
        val provider = LookupProvider.fromId(sourceId) ?: error("Unknown provider")
        val contactsOk = has(Manifest.permission.READ_CONTACTS)
        val smsOk = has(Manifest.permission.READ_SMS)
        val unique = collect(includeContacts = contactsOk, includeMessages = smsOk)
        val cached = cache.numbersForSource(sourceId).toHashSet()
        val toQuery = unique.filter { it !in cached && ReverseLookup.cacheKey(it) !in cached }
        LookupCostEstimate(
            providerId = provider.id,
            providerLabel = provider.displayName,
            uniqueCount = unique.size,
            cachedCount = unique.size - toQuery.size,
            toQueryCount = toQuery.size,
            usdPerLookup = provider.usdPerLookup,
            estimatedUsd = toQuery.size * provider.usdPerLookup,
            priceNote = provider.priceNote,
            priceSourceUrl = provider.priceSourceUrl,
            missingContactsPermission = !contactsOk,
            missingSmsPermission = !smsOk,
        )
    }

    /**
     * Unique E.164 numbers not already cached for [sourceId], in stable order.
     */
    suspend fun pending(sourceId: String): List<String> = withContext(Dispatchers.IO) {
        val unique = collect(
            includeContacts = has(Manifest.permission.READ_CONTACTS),
            includeMessages = has(Manifest.permission.READ_SMS),
        )
        val cached = cache.numbersForSource(sourceId).toHashSet()
        unique.filter { it !in cached && ReverseLookup.cacheKey(it) !in cached }
    }

    fun collect(includeContacts: Boolean, includeMessages: Boolean): List<String> {
        val raw = LinkedHashSet<String>()
        if (includeContacts) {
            runCatching { ContactDirectory.allPhones(context).forEach { raw.add(it.number) } }
                .onFailure { Timber.w("Reading contacts for bulk lookup failed") }
        }
        if (includeMessages) {
            runCatching { addMessageAddresses(raw) }
                .onFailure { Timber.w("Reading message addresses for bulk lookup failed") }
        }
        val out = LinkedHashSet<String>()
        raw.forEach { n -> ReverseLookup.toE164(n)?.let { out.add(it) } }
        return out.toList()
    }

    private fun addMessageAddresses(out: MutableSet<String>) {
        MessagingQueries.conversations(context, limit = 10_000).forEach { row ->
            val parts = row.recipients.ifEmpty { MessagePresentation.splitRecipients(row.address) }
            out.addAll(parts)
        }
        val uris = listOf(Telephony.Sms.Inbox.CONTENT_URI, Telephony.Sms.Sent.CONTENT_URI, Telephony.Sms.CONTENT_URI)
        for (uri in uris) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(Telephony.Sms.ADDRESS), null, null, null)?.use { c ->
                    val i = c.getColumnIndex(Telephony.Sms.ADDRESS)
                    if (i < 0) return@use
                    while (c.moveToNext()) {
                        c.getString(i)?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
                    }
                }
            }
        }
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
