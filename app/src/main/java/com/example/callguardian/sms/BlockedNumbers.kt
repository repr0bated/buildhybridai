package com.example.callguardian.sms

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import timber.log.Timber

/**
 * Integrates Android's [BlockedNumberContract]. Writes only succeed when
 * Call Guardian holds the default SMS or default dialer role; otherwise
 * the calls no-op and the in-app engine blocklist still applies.
 */
object BlockedNumbers {

    fun canBlock(context: Context): Boolean =
        runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(context) }.getOrDefault(false)

    fun isBlocked(context: Context, number: String): Boolean {
        if (number.isBlank()) return false
        if (!canBlock(context)) return false
        return MessagePresentation.splitRecipients(number).any { part ->
            runCatching { BlockedNumberContract.isBlocked(context, part) }.getOrDefault(false)
        }
    }

    fun block(context: Context, number: String): Boolean {
        if (!canBlock(context)) return false
        var ok = false
        MessagePresentation.splitRecipients(number).forEach { part ->
            val values = ContentValues().apply {
                put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, part)
            }
            ok = runCatching {
                context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values) != null
            }.onFailure { Timber.w(it, "BlockedNumberContract insert failed") }.getOrDefault(false) || ok
        }
        return ok
    }

    fun unblock(context: Context, number: String): Boolean {
        if (!canBlock(context)) return false
        var ok = false
        MessagePresentation.splitRecipients(number).forEach { part ->
            ok = runCatching {
                val deleted = context.contentResolver.delete(
                    BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                    "${BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER}=?",
                    arrayOf(part),
                )
                deleted > 0
            }.onFailure { Timber.w(it, "BlockedNumberContract delete failed") }.getOrDefault(false) || ok
        }
        return ok
    }
}
