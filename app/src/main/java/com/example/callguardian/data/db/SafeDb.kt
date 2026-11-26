package com.example.callguardian.data.db

import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** Catch SQLite / Room failures so a bad trigger or disk error cannot crash the UI thread. */
object SafeDb {
    suspend fun <T> write(label: String, block: suspend () -> T): T? = try {
        block()
    } catch (t: CancellationException) {
        throw t
    } catch (t: Throwable) {
        Timber.e(t, "DB write failed: %s", label)
        null
    }
}
