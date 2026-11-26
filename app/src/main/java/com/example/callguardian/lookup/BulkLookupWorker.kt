package com.example.callguardian.lookup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import timber.log.Timber

/** WorkManager job: run one provider over every uncached contact and SMS/MMS sender. */
@HiltWorker
class BulkLookupWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val collector: LookupNumberCollector,
    private val manager: ReverseLookupManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val sourceId = inputData.getString(KEY_SOURCE) ?: return Result.failure()
        val provider = LookupProvider.fromId(sourceId) ?: return Result.failure()
        val numbers = collector.pending(sourceId)
        val total = numbers.size
        var done = 0
        var failed = 0
        var queried = 0
        val skipped = 0
        publish(sourceId, provider.displayName, done, total, failed, queried, skipped)
        setForeground(foreground(provider.displayName, done, total))
        for (number in numbers) {
            if (isStopped) break
            delay(provider.minIntervalMs)
            if (isStopped) break
            val ok = runCatching { manager.lookupOne(number, sourceId) }
                .fold(
                    onSuccess = { r ->
                        queried++
                        r != null
                    },
                    onFailure = { e ->
                        if (e is LookupRateLimited) {
                            delay(e.retryAfterMs)
                            runCatching { manager.lookupOne(number, sourceId) }.getOrNull() != null
                        } else {
                            Timber.w("Bulk lookup failed for a number via %s", sourceId)
                            false
                        }
                    },
                )
            if (!ok) failed++
            done++
            publish(sourceId, provider.displayName, done, total, failed, queried, skipped)
            setForeground(foreground(provider.displayName, done, total))
        }
        return Result.success(
            workDataOf(
                KEY_SOURCE to sourceId,
                KEY_DONE to done,
                KEY_TOTAL to total,
                KEY_FAILED to failed,
                KEY_QUERIED to queried,
            ),
        )
    }

    private suspend fun publish(sourceId: String, label: String, done: Int, total: Int, failed: Int, queried: Int, skipped: Int) {
        setProgress(
            workDataOf(
                KEY_SOURCE to sourceId,
                KEY_LABEL to label,
                KEY_DONE to done,
                KEY_TOTAL to total,
                KEY_FAILED to failed,
                KEY_QUERIED to queried,
                KEY_SKIPPED to skipped,
            ),
        )
    }

    private fun foreground(label: String, done: Int, total: Int): ForegroundInfo {
        ensureChannel()
        val text = if (total == 0) "Nothing new to look up" else "Looked up $done of $total"
        val notification = NotificationCompat.Builder(appContext, CHANNEL)
            .setContentTitle("$label lookup")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        val id = NOTIFICATION_BASE + (label.hashCode() and 0x7fff)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Bulk reverse lookup", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val KEY_SOURCE = "source"
        const val KEY_LABEL = "label"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FAILED = "failed"
        const val KEY_QUERIED = "queried"
        const val KEY_SKIPPED = "skipped"
        const val CHANNEL = "lookup_bulk"
        private const val NOTIFICATION_BASE = 71400

        fun uniqueName(sourceId: String) = "bulk_lookup_$sourceId"

        fun enqueue(context: Context, sourceId: String) {
            val req = OneTimeWorkRequestBuilder<BulkLookupWorker>()
                .setInputData(workDataOf(KEY_SOURCE to sourceId))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("bulk_lookup")
                .addTag(uniqueName(sourceId))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName(sourceId), ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, sourceId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(sourceId))
        }
    }
}
