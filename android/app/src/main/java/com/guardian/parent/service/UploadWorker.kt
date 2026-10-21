package com.guardian.parent.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.guardian.parent.core.Config
import com.guardian.parent.core.Logger
import com.guardian.parent.data.LocalStore
import com.guardian.parent.data.Repo
import java.util.concurrent.TimeUnit

/**
 * Periodic safety net.
 *
 * The foreground service uploads immediately, but Android can suspend a
 * background app for a long time. This worker makes sure that whatever is
 * still queued is retried at least every 15 minutes (the shortest period
 * WorkManager allows).
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = LocalStore(applicationContext)
        if (!store.isEnrolled) {
            Logger.d("The device is not paired, nothing to upload")
            return Result.success()
        }

        return try {
            val repo = Repo(applicationContext)
            val result = repo.flushQueue()
            when {
                result.authFailed -> {
                    // The token is not valid anymore. Retrying will not help,
                    // the UI has to ask the parent to pair the device again.
                    Logger.w("Upload rejected: the device token is not valid anymore")
                    Result.failure()
                }

                result.failed > 0 -> Result.retry()
                else -> Result.success()
            }
        } catch (t: Throwable) {
            Logger.e("The periodic upload failed", t)
            Result.retry()
        }
    }

    companion object {
        /** Schedules the periodic job; repeated calls keep the existing one. */
        fun schedule(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = PeriodicWorkRequestBuilder<UploadWorker>(
                    15,
                    TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    Config.UPLOAD_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            } catch (t: Throwable) {
                Logger.e("Unable to schedule the periodic upload", t)
            }
        }

        /** Removes the periodic job (used when the device is unpaired). */
        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(Config.UPLOAD_WORK_NAME)
            } catch (t: Throwable) {
                Logger.w("Unable to cancel the periodic upload", t)
            }
        }
    }
}
